import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:frontend/api/dio_client.dart';
import 'package:frontend/controllers/auth_controller.dart';
import 'package:frontend/controllers/order_controller.dart';
import 'package:frontend/models/user_model.dart';
import 'package:frontend/pages/order/order_detail_page.dart';
import 'package:frontend/routes/app_pages.dart';
import 'package:frontend/routes/app_routes.dart';
import 'package:frontend/services/storage_service.dart';
import 'package:get/get.dart' hide Response;

/// 阶段 6：错误态 / 空态区分 与 身份判定的 Widget 回归测试
///
/// 断言要点：
/// - 昵称为空串时个人中心与首页不崩溃（首字回退用户名）；
/// - 断网时列表页显示"错误态 + 点击重试"，而不是"暂无数据"的空态；
/// - 恢复后点击重试能正常加载；
/// - 买家在"我的卖出"标签返回后打开自己的买家订单，看不到卖家专属操作，
///   且评价方向仍然是"买家评价卖家"。
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

  Response page(RequestOptions options, List<Map<String, dynamic>> records,
          {int pages = 1, int current = 1}) =>
      jsonOk(options, {
        'records': records,
        'total': records.length,
        'size': 10,
        'current': current,
        'pages': pages,
      });

  DioException offline(RequestOptions options) => DioException(
        requestOptions: options,
        type: DioExceptionType.connectionError,
        message: 'connection refused',
      );

  final Map<String, dynamic> sampleGoods = {
    'id': 101,
    'sellerId': 2001,
    'schoolId': 1,
    'schoolName': '清华大学',
    'categoryId': 101,
    'categoryName': '教材书籍',
    'title': '校园二手电单车',
    'price': 699.00,
    'conditionLevel': '9成新',
    'status': 'ON_SALE',
  };

  setUp(() async {
    Get.reset();
    FlutterSecureStorage.setMockInitialValues({});
    await Get.putAsync(() => StorageService().init());
    final auth = Get.put(AuthController());
    auth.isLoggedIn.value = true;
    auth.currentUser.value = UserProfileModel(
      id: '1001',
      username: 'test_student',
      // 关键：昵称被清空成空串（后端允许），修复前会让个人中心/首页白屏
      nickname: '',
      role: 'USER',
      status: 'ACTIVE',
      verifyStatus: 'SUCCESS',
      schoolName: '清华大学',
    );

    if (mockInterceptor != null) {
      DioClient().dio.interceptors.remove(mockInterceptor);
      mockInterceptor = null;
    }
  });

  tearDown(() {
    if (mockInterceptor != null) {
      DioClient().dio.interceptors.remove(mockInterceptor);
      mockInterceptor = null;
    }
    Get.reset();
  });

  // ==========================================================================
  // 一、昵称为空串时的页面渲染
  // ==========================================================================

  group('Stage 6-1: 昵称为空串（可复现白屏）', () {
    testWidgets('1. 个人中心：昵称为空串正常渲染，首字回退用户名首字母',
        (WidgetTester tester) async {
      tester.view.physicalSize = const Size(1080, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);

      installMockApi((options) => jsonOk(options, <dynamic>[]));

      await tester.pumpWidget(GetMaterialApp(
        initialRoute: AppRoutes.profile,
        getPages: AppPages.routes,
      ));
      await tester.pumpAndSettle();

      // 修复前这里会因 RangeError（substring(0,1) on ''）整页白屏
      expect(tester.takeException(), isNull);
      expect(find.text('个人中心'), findsOneWidget);
      expect(find.text('T'), findsOneWidget, reason: '空昵称时头像首字应回退为用户名首字母');
      expect(find.text('test_student'), findsOneWidget, reason: '空昵称时展示名应回退为用户名');
      expect(find.text('退出登录'), findsOneWidget);
    });

    testWidgets('2. 首页：昵称为空串正常渲染，欢迎语回退用户名',
        (WidgetTester tester) async {
      tester.view.physicalSize = const Size(1080, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);

      await tester.pumpWidget(GetMaterialApp(
        initialRoute: AppRoutes.home,
        getPages: AppPages.routes,
      ));
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
      expect(find.text('欢迎回来，test_student！'), findsOneWidget);
      expect(find.text('T'), findsOneWidget);
    });
  });

  // ==========================================================================
  // 二、错误态 / 空态 区分
  // ==========================================================================

  group('Stage 6-3: 断网时列表页显示错误态与重试入口', () {
    testWidgets('3. 商品列表：断网显示错误态 + 点击重试，恢复后正常加载',
        (WidgetTester tester) async {
      tester.view.physicalSize = const Size(1080, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);

      bool offlineMode = true;
      int listRequests = 0;

      installMockApi((options) {
        if (options.path.contains('/category/list')) {
          return jsonOk(options, <dynamic>[]);
        }
        if (options.path.contains('/goods/search/hot') ||
            options.path.contains('/goods/search/history')) {
          return jsonOk(options, <dynamic>[]);
        }
        if (options.path.contains('/goods/list')) {
          listRequests++;
          if (offlineMode) throw offline(options);
          return page(options, [sampleGoods]);
        }
        return jsonOk(options, <dynamic>[]);
      });

      await tester.pumpWidget(GetMaterialApp(
        initialRoute: AppRoutes.goodsList,
        getPages: AppPages.routes,
      ));
      await tester.pumpAndSettle();

      // 断网：必须是错误态，且不能伪装成"暂无在售二手商品"
      expect(find.text('商品列表加载失败'), findsOneWidget);
      expect(find.text('点击重试'), findsOneWidget);
      expect(find.text('暂无在售二手商品'), findsNothing,
          reason: '请求失败绝不能与"确实没有数据"同形');

      // 恢复网络后点击重试 -> 正常加载
      offlineMode = false;
      final before = listRequests;
      await tester.tap(find.text('点击重试'));
      await tester.pumpAndSettle();

      expect(listRequests, greaterThan(before));
      expect(find.text('校园二手电单车'), findsOneWidget);
      expect(find.text('699.00'), findsOneWidget);
      expect(find.text('商品列表加载失败'), findsNothing);
    });

    testWidgets('4. 商品列表：接口成功但无数据时显示空态（与错误态可分）',
        (WidgetTester tester) async {
      tester.view.physicalSize = const Size(1080, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);

      installMockApi((options) {
        if (options.path.contains('/goods/list')) {
          return page(options, <Map<String, dynamic>>[]);
        }
        return jsonOk(options, <dynamic>[]);
      });

      await tester.pumpWidget(GetMaterialApp(
        initialRoute: AppRoutes.goodsList,
        getPages: AppPages.routes,
      ));
      await tester.pumpAndSettle();

      expect(find.text('暂无在售二手商品'), findsOneWidget);
      expect(find.text('商品列表加载失败'), findsNothing);
    });

    testWidgets('5. 我的收藏：断网显示错误态 + 重试，不是"还没有收藏"',
        (WidgetTester tester) async {
      tester.view.physicalSize = const Size(1080, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);

      bool offlineMode = true;
      installMockApi((options) {
        if (options.path.contains('/favorite/list')) {
          if (offlineMode) throw offline(options);
          return page(options, [
            {
              'id': 1,
              'goodsId': 201,
              'title': '正在出售的 Kindle',
              'price': 350.00,
              'conditionLevel': '95新',
              'status': 'ON_SALE',
            }
          ]);
        }
        return jsonOk(options, <dynamic>[]);
      });

      await tester.pumpWidget(GetMaterialApp(
        initialRoute: AppRoutes.favorite,
        getPages: AppPages.routes,
      ));
      await tester.pumpAndSettle();

      expect(find.text('收藏列表加载失败'), findsOneWidget);
      expect(find.text('点击重试'), findsOneWidget);
      expect(find.text('还没有收藏任何闲置商品'), findsNothing);

      offlineMode = false;
      await tester.tap(find.text('点击重试'));
      await tester.pumpAndSettle();

      expect(find.text('正在出售的 Kindle'), findsOneWidget);
      expect(find.text('收藏列表加载失败'), findsNothing);
    });

    testWidgets('6. 浏览足迹：断网显示错误态 + 重试，不是"最近还没有浏览过"',
        (WidgetTester tester) async {
      tester.view.physicalSize = const Size(1080, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);

      bool offlineMode = true;
      installMockApi((options) {
        if (options.path.contains('/history/list')) {
          if (offlineMode) throw offline(options);
          return page(options, [
            {
              'id': 1,
              'goodsId': 301,
              'title': '考研政治核心考案',
              'price': 18.00,
              'conditionLevel': '全新',
              'status': 'ON_SALE',
              'browseTime': '2026-09-17 15:30:00',
            }
          ]);
        }
        return jsonOk(options, <dynamic>[]);
      });

      await tester.pumpWidget(GetMaterialApp(
        initialRoute: AppRoutes.history,
        getPages: AppPages.routes,
      ));
      await tester.pumpAndSettle();

      expect(find.text('浏览足迹加载失败'), findsOneWidget);
      expect(find.text('最近还没有浏览过闲置商品'), findsNothing);

      offlineMode = false;
      await tester.tap(find.text('点击重试'));
      await tester.pumpAndSettle();

      expect(find.text('考研政治核心考案'), findsOneWidget);
      expect(find.text('浏览足迹加载失败'), findsNothing);
    });
  });

  // ==========================================================================
  // 三、订单详情的买卖双方身份判定
  // ==========================================================================

  group('Stage 6-5: 订单详情身份判定只看服务端 buyerId/sellerId', () {
    Map<String, dynamic> orderJson(String status) => {
          'id': 101,
          'orderNo': 'ORD20260917000101',
          'goodsId': 801,
          'goodsTitleSnapshot': '考研英语词汇闪过',
          'goodsPriceSnapshot': 28.50,
          'meetLocation': '学生活动中心南门',
          'buyerMessage': '周五中午面交',
          'buyerId': 1001,
          'sellerId': 2002,
          'buyer': {
            'id': 1001,
            'username': 'test_student',
            'nickname': '买家张同学',
          },
          'seller': {
            'id': 2002,
            'username': 'seller_stu',
            'nickname': '卖家大华',
          },
          'orderStatus': status,
          'statusDesc': status == 'COMPLETED' ? '已完成' : '待卖家确认',
          'createdTime': '2026-09-17 10:00:00',
        };

    testWidgets('7. 买家（"我的卖出"标签返回后）打开自己的买家订单：不显示卖家操作',
        (WidgetTester tester) async {
      tester.view.physicalSize = const Size(1080, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);

      // 订单列表页的标签页停留在"我的卖出"（跨页面共享的 currentRole）
      final orderController = Get.put(OrderController());
      orderController.currentRole.value = 'SELLER';

      installMockApi((options) {
        if (options.path.contains('/orders/101')) {
          return jsonOk(options, orderJson('WAIT_SELLER_CONFIRM'));
        }
        return jsonOk(options, <dynamic>[]);
      });

      await tester.pumpWidget(GetMaterialApp(
        initialRoute: AppRoutes.home,
        getPages: AppPages.routes,
      ));
      Get.toNamed(AppRoutes.orderDetail, arguments: 101);
      await tester.pumpAndSettle();

      expect(find.byType(OrderDetailPage), findsOneWidget);
      // 核心断言：买家绝不能看到卖家的"确认接单"
      expect(find.widgetWithText(ElevatedButton, '确认接单'), findsNothing,
          reason: 'currentRole=SELLER 是列表页的标签页选择，不能用来判定订单身份');
      // 买家自身的操作仍然可用（说明底栏确实渲染了，只是没有卖家按钮）
      expect(find.widgetWithText(OutlinedButton, '取消订单'), findsOneWidget);
    });

    testWidgets('8. 买家评价方向仍然正确：买家评价卖家（即使列表页停在"我的卖出"）',
        (WidgetTester tester) async {
      tester.view.physicalSize = const Size(1080, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);

      final orderController = Get.put(OrderController());
      orderController.currentRole.value = 'SELLER';

      installMockApi((options) {
        if (options.path.contains('/reviews/order/101')) {
          return jsonOk(options, {
            'orderId': 101,
            'isBuyer': true,
            'canReview': true,
            'myReview': null,
            'peerReview': null,
          });
        }
        if (options.path.contains('/orders/101')) {
          return jsonOk(options, orderJson('COMPLETED'));
        }
        return jsonOk(options, <dynamic>[]);
      });

      await tester.pumpWidget(GetMaterialApp(
        initialRoute: AppRoutes.home,
        getPages: AppPages.routes,
      ));
      Get.toNamed(AppRoutes.orderDetail, arguments: 101);
      await tester.pumpAndSettle();

      expect(find.text('去评价'), findsWidgets);
      await tester.tap(find.text('去评价').first);
      await tester.pumpAndSettle();

      expect(find.text('评价本次交易'), findsOneWidget);
      // 买家视角的标签集合：'物美价廉' 属于买家评价卖家；'爽快买家' 属于卖家评价买家
      expect(find.text('物美价廉'), findsOneWidget,
          reason: '买家应使用"评价卖家"的标签集合');
      expect(find.text('爽快买家'), findsNothing,
          reason: '修复前 currentRole=SELLER 会让买家拿到"评价买家"的标签集合');
    });
  });
}
