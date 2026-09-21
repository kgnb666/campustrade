import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:frontend/api/dio_client.dart';
import 'package:frontend/controllers/auth_controller.dart';
import 'package:frontend/models/order.dart';
import 'package:frontend/models/user_model.dart';
import 'package:frontend/pages/goods/goods_detail_page.dart';
import 'package:frontend/pages/goods/goods_list_page.dart';
import 'package:frontend/pages/order/my_orders_page.dart';
import 'package:frontend/routes/app_pages.dart';
import 'package:frontend/routes/app_routes.dart';
import 'package:frontend/services/storage_service.dart';
import 'package:frontend/widgets/home_search_field.dart';
import 'package:frontend/widgets/home_todo_section.dart';
import 'package:get/get.dart' hide Response;

/// 首页（面向用户的可用首页）widget 测试。
///
/// 首页由四块组成：欢迎条 → 搜索框 → 我的待办（仅登录） → 最新商品（6 条）+ 查看全部。
/// 这里逐块覆盖：
/// 1. 未登录：只有欢迎/登录引导与搜索框，不显示待办，且**不发**待办请求；
/// 2. 已登录：三项待办数量正确渲染，且三个入口跳转携带正确的角色/状态参数；
/// 3. 最新商品渲染标题与价格，点击进入商品详情；
/// 4. 待办接口失败：显示"待办加载失败，点击重试"（不静默隐藏），最新商品区块不受影响，
///    重试成功后恢复正常；
/// 5. 搜索框：输入关键词 → 跳转集市页，关键词既带到页面上也带到了请求参数里；
/// 6. 整页下拉刷新：待办与最新商品**两个数据源**都会重新请求。
///
/// mock 方式沿用项目既有约定（`InterceptorsWrapper` 注入 fixture，见
/// `test/stage6_error_state_widget_test.dart`）。
void main() {
  Interceptor? mockInterceptor;

  void installMockApi(Response Function(RequestOptions options) responder) {
    if (mockInterceptor != null) {
      DioClient().dio.interceptors.remove(mockInterceptor);
    }
    mockInterceptor = InterceptorsWrapper(
      onRequest: (options, handler) {
        try {
          return handler.resolve(responder(options));
        } on DioException catch (dioErr) {
          return handler.reject(dioErr);
        } catch (e) {
          return handler.reject(DioException(
            requestOptions: options,
            error: e,
            type: DioExceptionType.unknown,
          ));
        }
      },
    );
    DioClient().dio.interceptors.insert(0, mockInterceptor!);
  }

  Response jsonOk(RequestOptions options, Object? data) => Response(
        requestOptions: options,
        statusCode: 200,
        data: {'code': 200, 'message': 'success', 'data': data},
      );

  Response pageOf(RequestOptions options, List<Map<String, dynamic>> records) =>
      jsonOk(options, {
        'records': records,
        'total': records.length,
        'size': 10,
        'current': 1,
        'pages': 1,
      });

  DioException offline(RequestOptions options) => DioException(
        requestOptions: options,
        type: DioExceptionType.connectionError,
        message: 'connection refused',
      );

  Map<String, dynamic> summaryJson({
    int pendingSellerConfirm = 0,
    int waitMeet = 0,
    int toReview = 0,
  }) =>
      {
        'pendingSellerConfirm': pendingSellerConfirm,
        'waitMeet': waitMeet,
        'toReview': toReview,
      };

  Map<String, dynamic> goods({
    Object id = 501,
    String title = '校园二手电单车',
    double price = 699.00,
    String? coverImage,
  }) =>
      {
        'id': id,
        'sellerId': 2002,
        'schoolId': 1,
        'schoolName': '清华大学',
        'categoryId': 1,
        'categoryName': '教材书籍',
        'title': title,
        'coverImage': coverImage,
        'price': price,
        'conditionLevel': '9成新',
        'status': 'ON_SALE',
        'viewCount': 12,
      };

  final Map<String, dynamic> goodsDetail = {
    ...goods(),
    'description': '九成新，骑行不到一年',
    'images': <String>[],
    'tags': <String>['代步', '电单车'],
    'sellerUsername': 'seller_bike',
    'sellerNickname': '卖车学长',
    'sellerAvatar': null,
    'sellerVerified': true,
    'sellerSchoolName': '清华大学',
    'sellerCreditScore': 120,
    'sellerTradeCount': 3,
    'sellerGoodReviewCount': 3,
  };

  void enlargeWindow(WidgetTester tester) {
    tester.view.physicalSize = const Size(1080, 2400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
  }

  Future<void> pumpHome(WidgetTester tester) async {
    await tester.pumpWidget(GetMaterialApp(
      initialRoute: AppRoutes.home,
      getPages: AppPages.routes,
    ));
    await tester.pumpAndSettle();
  }

  void loginAsBuyer() {
    final auth = Get.find<AuthController>();
    auth.isLoggedIn.value = true;
    auth.currentUser.value = UserProfileModel(
      id: '1001',
      username: 'buyer_test',
      nickname: '买家测试',
      role: 'USER',
      status: 'ACTIVE',
      verifyStatus: 'SUCCESS',
      schoolName: '清华大学',
      credit: UserCreditModel(
        creditScore: 118,
        tradeCount: 2,
        goodReviewCount: 2,
        badReviewCount: 0,
      ),
    );
  }

  setUp(() async {
    Get.reset();
    FlutterSecureStorage.setMockInitialValues({});
    await Get.putAsync(() => StorageService().init());
    Get.put(AuthController());
  });

  tearDown(() {
    if (mockInterceptor != null) {
      DioClient().dio.interceptors.remove(mockInterceptor);
      mockInterceptor = null;
    }
    Get.reset();
  });

  // ==========================================================================
  // 1. 未登录
  // ==========================================================================

  testWidgets('1. 未登录：只有欢迎/登录引导与搜索框，不显示待办，也不请求待办接口',
      (WidgetTester tester) async {
    enlargeWindow(tester);

    int summaryCalls = 0;
    installMockApi((options) {
      if (options.path.endsWith('/orders/summary')) {
        summaryCalls++;
        return jsonOk(options, summaryJson(toReview: 9));
      }
      if (options.path.contains('/goods/list')) {
        return pageOf(options, [goods()]);
      }
      return jsonOk(options, null);
    });

    await pumpHome(tester);

    expect(find.textContaining('登录即可体验完整的校园认证'), findsOneWidget);
    expect(find.widgetWithText(ElevatedButton, '登录'), findsOneWidget);
    expect(find.widgetWithText(OutlinedButton, '注册'), findsOneWidget);
    expect(find.text(HomeSearchField.hintText), findsOneWidget);

    // 待办区块整块不出现
    expect(find.byType(HomeTodoSection), findsNothing);
    expect(find.text('我的待办'), findsNothing);
    expect(find.text('待我确认'), findsNothing);
    // 未登录时不该去请求一个"只返回我自己的数据"的接口（必然 401）
    expect(summaryCalls, 0, reason: '未登录时不应请求 /orders/summary');

    // 最新商品照常展示（未登录也能逛）
    expect(find.text('最新商品'), findsOneWidget);
    expect(find.text('校园二手电单车'), findsOneWidget);
  });

  // ==========================================================================
  // 2. 已登录：待办渲染 + 跳转参数
  // ==========================================================================

  testWidgets('2. 已登录：待办三项数量正确渲染，点击跳转携带正确的角色/状态参数',
      (WidgetTester tester) async {
    enlargeWindow(tester);
    loginAsBuyer();

    final List<Map<String, String>> orderQueries = <Map<String, String>>[];

    installMockApi((options) {
      if (options.path.endsWith('/orders/summary')) {
        // 1 / 2 / 3 三个互不相同的数字，保证每个数字在页面上唯一，断言不会被别的文本命中
        return jsonOk(options, summaryJson(
          pendingSellerConfirm: 1,
          waitMeet: 2,
          toReview: 3,
        ));
      }
      if (options.path.endsWith('/orders/my')) {
        orderQueries.add(options.queryParameters.map(
          (key, value) => MapEntry(key, value.toString()),
        ));
        return pageOf(options, []);
      }
      if (options.path.contains('/goods/list')) {
        return pageOf(options, [goods()]);
      }
      return jsonOk(options, null);
    });

    await pumpHome(tester);

    final Finder todoSection = find.byType(HomeTodoSection);
    expect(todoSection, findsOneWidget);
    expect(find.text('待我确认'), findsOneWidget);
    expect(find.text('待面交'), findsOneWidget);
    expect(find.text('待评价'), findsOneWidget);
    expect(find.descendant(of: todoSection, matching: find.text('1')), findsOneWidget);
    expect(find.descendant(of: todoSection, matching: find.text('2')), findsOneWidget);
    expect(find.descendant(of: todoSection, matching: find.text('3')), findsOneWidget);

    // 待我确认 → 卖家视角 + 待确认
    await tester.tap(find.text('待我确认'));
    await tester.pumpAndSettle();
    expect(find.byType(MyOrdersPage), findsOneWidget);
    expect(orderQueries.last['role'], 'SELLER');
    expect(orderQueries.last['status'], OrderStatus.waitSellerConfirm.code);
    Get.back();
    await tester.pumpAndSettle();

    // 待面交 → 状态待面交（视角沿用页面默认：买家）
    await tester.tap(find.text('待面交'));
    await tester.pumpAndSettle();
    expect(orderQueries.last['status'], OrderStatus.waitMeet.code);
    expect(orderQueries.last['role'], 'BUYER');
    Get.back();
    await tester.pumpAndSettle();

    // 待评价 → 状态已完成
    await tester.tap(find.text('待评价'));
    await tester.pumpAndSettle();
    expect(orderQueries.last['status'], OrderStatus.completed.code);
    expect(orderQueries.last['role'], 'BUYER');
  });

  testWidgets('3. 待办数量为 0：入口仍然存在，只把数字显示为 0',
      (WidgetTester tester) async {
    enlargeWindow(tester);
    loginAsBuyer();

    installMockApi((options) {
      if (options.path.endsWith('/orders/summary')) {
        return jsonOk(options, summaryJson());
      }
      if (options.path.contains('/goods/list')) {
        return pageOf(options, [goods()]);
      }
      return jsonOk(options, null);
    });

    await pumpHome(tester);

    expect(find.text('待我确认'), findsOneWidget);
    expect(find.text('待面交'), findsOneWidget);
    expect(find.text('待评价'), findsOneWidget);
    expect(
      find.descendant(of: find.byType(HomeTodoSection), matching: find.text('0')),
      findsNWidgets(3),
      reason: '0 也要显示出来并把入口留着，用户才知道"这里是空的"而不是"没有这个功能"',
    );
  });

  // ==========================================================================
  // 3. 最新商品
  // ==========================================================================

  testWidgets('4. 最新商品：渲染标题与价格，点击进入商品详情',
      (WidgetTester tester) async {
    enlargeWindow(tester);

    installMockApi((options) {
      if (options.path.contains('/goods/501')) {
        return jsonOk(options, goodsDetail);
      }
      if (options.path.contains('/reviews/goods/')) {
        return pageOf(options, []);
      }
      if (options.path.contains('/goods/list')) {
        return pageOf(options, [
          goods(),
          goods(id: 502, title: '九成新机械键盘', price: 199.00),
        ]);
      }
      return jsonOk(options, null);
    });

    await pumpHome(tester);

    expect(find.text('最新商品'), findsOneWidget);
    expect(find.text('校园二手电单车'), findsOneWidget);
    expect(find.text('¥699.00'), findsOneWidget);
    expect(find.text('九成新机械键盘'), findsOneWidget);
    expect(find.text('¥199.00'), findsOneWidget);

    await tester.tap(find.text('校园二手电单车'));
    await tester.pumpAndSettle();

    expect(find.byType(GoodsDetailPage), findsOneWidget);
    expect(find.text('九成新，骑行不到一年'), findsOneWidget);
  });

  // ==========================================================================
  // 4. 待办失败不静默、也不影响其它区块
  // ==========================================================================

  testWidgets('5. 待办接口失败：显示"加载失败 + 重试"，最新商品区块仍正常',
      (WidgetTester tester) async {
    enlargeWindow(tester);
    loginAsBuyer();

    bool todoOffline = true;
    installMockApi((options) {
      if (options.path.endsWith('/orders/summary')) {
        if (todoOffline) throw offline(options);
        return jsonOk(options, summaryJson(pendingSellerConfirm: 4));
      }
      if (options.path.contains('/goods/list')) {
        return pageOf(options, [goods()]);
      }
      return jsonOk(options, null);
    });

    await pumpHome(tester);

    expect(find.text('待办加载失败，点击重试'), findsOneWidget);
    // 不静默隐藏、也不阻塞其它区块
    expect(find.text('最新商品'), findsOneWidget);
    expect(find.text('校园二手电单车'), findsOneWidget);

    todoOffline = false;
    await tester.tap(find.text('待办加载失败，点击重试'));
    await tester.pumpAndSettle();

    expect(find.text('待办加载失败，点击重试'), findsNothing);
    expect(
      find.descendant(of: find.byType(HomeTodoSection), matching: find.text('4')),
      findsOneWidget,
    );
  });

  // ==========================================================================
  // 5. 搜索框
  // ==========================================================================

  testWidgets('6. 搜索框：输入关键词并提交 → 进入集市页且关键词被带上',
      (WidgetTester tester) async {
    enlargeWindow(tester);

    final List<String> searchedKeywords = <String>[];
    installMockApi((options) {
      if (options.path.contains('/goods/search')) {
        searchedKeywords.add(options.queryParameters['keyword']?.toString() ?? '');
        return pageOf(options, [goods(title: '机械键盘')]);
      }
      if (options.path.contains('/goods/list')) {
        return pageOf(options, [goods()]);
      }
      if (options.path.endsWith('/goods/search/hot') ||
          options.path.endsWith('/goods/search/history')) {
        return jsonOk(options, <dynamic>[]);
      }
      if (options.path.contains('/category/list')) {
        return jsonOk(options, <dynamic>[]);
      }
      return jsonOk(options, null);
    });

    await pumpHome(tester);

    await tester.enterText(find.byType(TextField).first, ' 机械键盘 ');
    await tester.tap(find.byKey(HomeSearchField.submitButtonKey));
    await tester.pumpAndSettle();

    expect(find.byType(GoodsListPage), findsOneWidget);
    // 关键词既显示在集市页的搜索框里（首尾空白已去掉）...
    expect(find.text('机械键盘'), findsWidgets);
    // ...也真的发到了搜索接口上
    expect(searchedKeywords, contains('机械键盘'),
        reason: '首页搜索必须把关键词带到集市页的搜索请求里，而不是只跳个页面');
  });

  // ==========================================================================
  // 6. 下拉刷新
  // ==========================================================================

  testWidgets('7. 下拉刷新：待办与最新商品两个数据源都会被重新请求',
      (WidgetTester tester) async {
    enlargeWindow(tester);
    loginAsBuyer();

    int summaryCalls = 0;
    int listCalls = 0;
    installMockApi((options) {
      if (options.path.endsWith('/orders/summary')) {
        summaryCalls++;
        return jsonOk(options, summaryJson(waitMeet: 1));
      }
      if (options.path.contains('/goods/list')) {
        listCalls++;
        return pageOf(options, [goods()]);
      }
      return jsonOk(options, null);
    });

    await pumpHome(tester);

    expect(summaryCalls, 1);
    expect(listCalls, 1);

    // 下拉刷新的触发阈值是"容器高度的 25%"（RefreshIndicator 的既定行为），
    // 而这里为了能一屏看清四个区块把测试窗口设得很高（2400），
    // 因此手势位移必须大于 600 才会真正"武装"指示器——这不是在迁就实现，
    // 而是把"阈值与窗口尺寸相关"这件事显式写出来。
    await tester.timedDrag(
      find.byType(ListView),
      const Offset(0, 900),
      const Duration(milliseconds: 500),
    );
    await tester.pumpAndSettle();

    expect(summaryCalls, 2, reason: '下拉刷新必须重新拉取待办');
    expect(listCalls, 2, reason: '下拉刷新必须重新拉取最新商品');
  });
}
