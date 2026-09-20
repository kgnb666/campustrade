import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:frontend/api/dio_client.dart';
import 'package:frontend/controllers/auth_controller.dart';
import 'package:frontend/controllers/favorite_controller.dart';
import 'package:frontend/controllers/goods_controller.dart';
import 'package:frontend/controllers/history_controller.dart';
import 'package:frontend/controllers/order_controller.dart';
import 'package:frontend/models/user_model.dart';
import 'package:frontend/routes/app_pages.dart';
import 'package:frontend/routes/app_routes.dart';
import 'package:frontend/services/storage_service.dart';
import 'package:get/get.dart' hide Response;

/// Stage 8-A：控制器作用域（页面级实例）与请求取消（CancelToken）
///
/// 覆盖两个真实缺陷：
/// 1. 页面用 `Get.put(OrderController())` 注册控制器时，同类型 key 会"取回旧实例"
///    而不是替换，于是"我的订单 / 订单详情 / 商品详情下单"共用同一个实例——
///    商品详情下单失败写入的 errorMessage 会跟着实例回到"我的订单"，
///    让本该是空态的列表显示成"创建订单失败"。
/// 2. 全前端此前零 CancelToken：快速切分类 / 连续刷新时会同时发出多个真实请求，
///    只做到"丢弃迟到响应"，旧请求仍会跑完整个往返。
void main() {
  Interceptor? mockInterceptor;

  void installMockApi(
    FutureOr<Response> Function(RequestOptions options) responder,
  ) {
    if (mockInterceptor != null) {
      DioClient().dio.interceptors.remove(mockInterceptor);
    }
    mockInterceptor = InterceptorsWrapper(
      onRequest: (options, handler) async {
        try {
          final resp = await responder(options);
          return handler.resolve(resp);
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

  Response pageOf(
    RequestOptions options,
    List<Map<String, dynamic>> records, {
    int pages = 1,
  }) =>
      jsonOk(options, {
        'records': records,
        'total': records.length,
        'size': 10,
        'current': 1,
        'pages': pages,
      });

  DioException serverError(RequestOptions options, int status, String message) =>
      DioException(
        requestOptions: options,
        type: DioExceptionType.badResponse,
        response: Response(
          requestOptions: options,
          statusCode: status,
          data: {
            'code': status,
            'message': message,
            'data': null,
            'timestamp': 1758100000000,
          },
        ),
      );

  final Map<String, dynamic> goodsItem = {
    'id': 501,
    'sellerId': 2002,
    'schoolId': 1,
    'schoolName': '清华大学',
    'categoryId': 1,
    'categoryName': '教材书籍',
    'title': '校园二手电单车',
    'price': 699.00,
    'conditionLevel': '9成新',
    'status': 'ON_SALE',
  };

  final Map<String, dynamic> goodsDetail = {
    ...goodsItem,
    'description': '九成新，可小刀',
    'location': '二食堂门口',
    'viewCount': 10,
    'images': <String>[],
    'tags': <String>[],
    'sellerUsername': 'seller_bike',
    'sellerNickname': '学长',
    'sellerVerified': true,
    'sellerCreditScore': 100,
  };

  final Map<String, dynamic> orderJson = {
    'id': 901,
    'orderNo': 'ORD2026091700901',
    'goodsId': 501,
    'goodsTitleSnapshot': '校园二手电单车',
    'goodsPriceSnapshot': 699.00,
    'meetLocation': '二食堂门口',
    'buyerId': 1001,
    'sellerId': 2002,
    'buyer': {'id': 1001, 'username': 'buyer_test', 'nickname': '买家'},
    'seller': {'id': 2002, 'username': 'seller_bike', 'nickname': '卖家'},
    'orderStatus': 'WAIT_SELLER_CONFIRM',
    'statusDesc': '待卖家确认',
    'createdTime': '2026-09-17 15:00:00',
  };

  /// 推进若干微任务轮次：Dio 的请求拦截器是排到微任务队列里执行的，
  /// 调用控制器方法后必须让出事件循环，才能观察到"请求真的发出去了"。
  Future<void> microtasks([int rounds = 5]) async {
    for (int i = 0; i < rounds; i++) {
      await Future<void>.delayed(Duration.zero);
    }
  }

  void enlargeWindow(WidgetTester tester) {
    tester.view.physicalSize = const Size(1080, 2400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
  }

  setUp(() async {
    Get.reset();
    FlutterSecureStorage.setMockInitialValues({});
    await Get.putAsync(() => StorageService().init());
    final auth = Get.put(AuthController());
    auth.isLoggedIn.value = true;
    auth.currentUser.value = UserProfileModel(
      id: '1001',
      username: 'buyer_test',
      nickname: '买家测试',
      role: 'USER',
      status: 'ACTIVE',
      verifyStatus: 'SUCCESS',
      schoolName: '清华大学',
    );
  });

  tearDown(() {
    if (mockInterceptor != null) {
      DioClient().dio.interceptors.remove(mockInterceptor);
      mockInterceptor = null;
    }
    Get.reset();
  });

  // ==========================================================================
  // 一、页面级控制器作用域
  // ==========================================================================

  group('Stage 8-A1: 页面级控制器各自持有实例、离开页面即释放', () {
    testWidgets('1. "我的订单"与"订单详情"不是同一个 OrderController 实例',
        (WidgetTester tester) async {
      enlargeWindow(tester);

      installMockApi((options) {
        if (options.path.contains('/orders/my')) return pageOf(options, []);
        if (options.path.contains('/orders/901')) return jsonOk(options, orderJson);
        if (options.path.contains('/reviews/order/901')) {
          return jsonOk(options, {
            'orderId': 901,
            'isBuyer': true,
            'canReview': false,
            'myReview': null,
            'peerReview': null,
          });
        }
        return jsonOk(options, null);
      });

      await tester.pumpWidget(GetMaterialApp(
        initialRoute: AppRoutes.orderMy,
        getPages: AppPages.routes,
      ));
      await tester.pumpAndSettle();

      expect(find.text('暂无相关订单'), findsOneWidget);
      // 列表页注册的是自己的 tag 实例，而不是全局默认实例
      expect(
        Get.isRegistered<OrderController>(tag: OrderController.tagMyOrders),
        isTrue,
        reason: '我的订单页必须拿到属于自己的控制器实例',
      );
      expect(Get.isRegistered<OrderController>(), isFalse,
          reason: '列表页不得顺带占用订单详情的默认实例');
      final OrderController listCtrl =
          Get.find<OrderController>(tag: OrderController.tagMyOrders);

      // 进入订单详情：另一个实例
      Get.toNamed(AppRoutes.orderDetail, arguments: 901);
      await tester.pumpAndSettle();

      expect(Get.isRegistered<OrderController>(), isTrue);
      final OrderController detailCtrl = Get.find<OrderController>();
      expect(identical(listCtrl, detailCtrl), isFalse,
          reason: '两个页面必须各自持有实例（这正是跨页错误态串页的根因）');
      expect(listCtrl.currentOrder.value, isNull,
          reason: '详情页写入的 currentOrder 不得出现在列表页实例上');
      expect(detailCtrl.currentOrder.value, isNotNull);

      // 离开页面：实例随路由一起释放
      Get.offAllNamed(AppRoutes.home);
      await tester.pumpAndSettle();
      expect(
        Get.isRegistered<OrderController>(tag: OrderController.tagMyOrders),
        isFalse,
        reason: '离开"我的订单"即释放其控制器实例',
      );
      expect(Get.isRegistered<OrderController>(), isFalse,
          reason: '离开"订单详情"即释放其控制器实例');
    });

    testWidgets('2. 商品详情下单失败后进入"我的订单"：显示空态，不显示别处的错误态',
        (WidgetTester tester) async {
      enlargeWindow(tester);

      installMockApi((options) {
        if (options.path.contains('/goods/501')) return jsonOk(options, goodsDetail);
        if (options.path.contains('/orders/my')) return pageOf(options, []);
        if (options.method == 'POST' && options.path == '/orders') {
          // 下单失败（后端以真实 HTTP 状态返回业务错误）
          throw serverError(options, 409, '商品已被他人下单锁定，请刷新后重试');
        }
        return jsonOk(options, null);
      });

      await tester.pumpWidget(GetMaterialApp(
        initialRoute: AppRoutes.home,
        getPages: AppPages.routes,
      ));
      await tester.pumpAndSettle();

      // 买家进入商品详情 -> 立即下单 -> 确认下单（失败）
      Get.toNamed(AppRoutes.goodsDetail, arguments: 501);
      await tester.pumpAndSettle();
      expect(find.text('立即下单'), findsOneWidget);

      await tester.tap(find.text('立即下单'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('确认下单'));
      await tester.pumpAndSettle();

      final OrderController createCtrl =
          Get.find<OrderController>(tag: OrderController.tagCreate);
      expect(createCtrl.errorMessage.value, contains('已被他人下单锁定'),
          reason: '下单失败必须落在"商品详情下单"这个专属实例上');

      // 关掉抽屉，再进"我的订单"
      Get.back();
      await tester.pumpAndSettle();
      Get.toNamed(AppRoutes.orderMy);
      await tester.pumpAndSettle();

      final OrderController listCtrl =
          Get.find<OrderController>(tag: OrderController.tagMyOrders);
      expect(identical(listCtrl, createCtrl), isFalse,
          reason: '"我的订单"与商品详情下单必须是两个实例');
      expect(listCtrl.errorMessage.value, isEmpty,
          reason: '别处下单失败不得写入"我的订单"的错误态');

      // 复用旧实现（共用同一实例）时，这里会显示成"创建订单失败"错误态
      expect(find.text('暂无相关订单'), findsOneWidget,
          reason: '列表页必须是空态');
      expect(find.text('加载订单失败'), findsNothing);
      expect(find.textContaining('已被他人下单锁定'), findsNothing,
          reason: '商品详情的下单错误不得串到"我的订单"页面');
    });

    testWidgets('3. 集市页与"我的发布"不是同一个 GoodsController 实例',
        (WidgetTester tester) async {
      enlargeWindow(tester);

      installMockApi((options) {
        if (options.path.endsWith('/goods/search/hot') ||
            options.path.endsWith('/goods/search/history')) {
          return jsonOk(options, <dynamic>[]);
        }
        if (options.path.contains('/category/list')) return jsonOk(options, <dynamic>[]);
        if (options.path.contains('/goods/my')) return jsonOk(options, <dynamic>[]);
        if (options.path.contains('/goods/list')) return pageOf(options, [goodsItem]);
        return jsonOk(options, null);
      });

      await tester.pumpWidget(GetMaterialApp(
        initialRoute: AppRoutes.goodsList,
        getPages: AppPages.routes,
      ));
      await tester.pumpAndSettle();

      final GoodsController market = Get.find<GoodsController>();
      // 模拟"别处"（集市页）写入的错误态
      market.errorMessage.value = '商品列表加载失败';

      Get.toNamed(AppRoutes.goodsMy);
      await tester.pumpAndSettle();

      expect(
        Get.isRegistered<GoodsController>(tag: GoodsController.tagMyGoods),
        isTrue,
        reason: '"我的发布"必须拿到属于自己的控制器实例',
      );
      final GoodsController mine =
          Get.find<GoodsController>(tag: GoodsController.tagMyGoods);
      expect(identical(market, mine), isFalse);
      expect(mine.errorMessage.value, isEmpty);
      expect(mine.myGoodsErrorMessage.value, isEmpty);
      expect(find.text('您尚未发布过任何闲置商品'), findsOneWidget,
          reason: '"我的发布"不应继承集市页的错误态');
    });

    testWidgets('4. 先进入"我的发布"：只请求自己的商品，不预热集市页数据',
        (WidgetTester tester) async {
      enlargeWindow(tester);

      int marketplaceListCalls = 0;
      int categoryCalls = 0;
      int myGoodsCalls = 0;

      installMockApi((options) {
        if (options.path.endsWith('/goods/search/hot') ||
            options.path.endsWith('/goods/search/history')) {
          return jsonOk(options, <dynamic>[]);
        }
        if (options.path.contains('/category/list')) {
          categoryCalls++;
          return jsonOk(options, <dynamic>[]);
        }
        if (options.path.contains('/goods/my')) {
          myGoodsCalls++;
          return jsonOk(options, [goodsItem]);
        }
        if (options.path.contains('/goods/list')) {
          marketplaceListCalls++;
          return pageOf(options, []);
        }
        return jsonOk(options, null);
      });

      await tester.pumpWidget(GetMaterialApp(
        initialRoute: AppRoutes.goodsMy,
        getPages: AppPages.routes,
      ));
      await tester.pumpAndSettle();

      expect(myGoodsCalls, greaterThanOrEqualTo(1));
      expect(marketplaceListCalls, 0,
          reason: '进入"我的发布"不得触发集市页商品列表请求');
      expect(categoryCalls, 0, reason: '进入"我的发布"不得预热分类数据');
      expect(find.text('校园二手电单车'), findsOneWidget);
      expect(find.text('编辑'), findsOneWidget);
      expect(find.text('下架'), findsOneWidget);
    });
  });

  // ==========================================================================
  // 二、CancelToken：旧请求被真正取消
  // ==========================================================================

  group('Stage 8-A2: 列表请求的 CancelToken', () {
    test('5. GoodsController：刷新会取消上一个在途请求，迟到响应不再回写', () async {
      final tokens = <CancelToken?>[];
      final completer = <Completer<Response>>[];

      installMockApi((options) {
        if (options.path.contains('/goods/list')) {
          tokens.add(options.cancelToken);
          final c = Completer<Response>();
          completer.add(c);
          return c.future;
        }
        return jsonOk(options, <dynamic>[]);
      });

      final controller = GoodsController(autoLoadMarketplace: false);

      final first = controller.loadGoods(refresh: true);
      await microtasks();
      expect(tokens.length, 1);
      final CancelToken firstToken = tokens[0]!;
      expect(firstToken.isCancelled, isFalse);

      // 用户快速再次刷新（例如连续切换分类）
      final second = controller.loadGoods(refresh: true);
      expect(firstToken.isCancelled, isTrue,
          reason: '新请求发起时必须 cancel 掉上一个仍在途的请求（而不是让它白跑）');
      await microtasks();
      expect(tokens.length, 2);
      expect(tokens[1]!.isCancelled, isFalse);

      // 旧请求迟到返回：整份丢弃，绝不回写
      completer[0].complete(pageOf(
        RequestOptions(path: '/goods/list'),
        [
          {...goodsItem, 'id': 1, 'title': '旧请求的商品'},
        ],
      ));
      await first;
      expect(controller.goodsList, isEmpty,
          reason: '被取消/被取代的旧响应不得写入列表');

      // 新请求正常返回
      completer[1].complete(pageOf(
        RequestOptions(path: '/goods/list'),
        [
          {...goodsItem, 'id': 2, 'title': '新请求的商品'},
        ],
      ));
      await second;
      expect(controller.goodsList.length, 1);
      expect(controller.goodsList.first.title, '新请求的商品');
    });

    test('6. GoodsController：onClose 取消在途请求并丢弃迟到响应', () async {
      final tokens = <CancelToken?>[];
      final completer = <Completer<Response>>[];

      installMockApi((options) {
        if (options.path.contains('/goods/list')) {
          tokens.add(options.cancelToken);
          final c = Completer<Response>();
          completer.add(c);
          return c.future;
        }
        return jsonOk(options, <dynamic>[]);
      });

      final controller = GoodsController(autoLoadMarketplace: false);
      final pending = controller.loadGoods(refresh: true);
      await microtasks();
      final CancelToken token = tokens.single!;
      expect(token.isCancelled, isFalse);

      // 用户离开页面 -> 控制器关闭
      controller.onClose();
      expect(token.isCancelled, isTrue,
          reason: '控制器关闭必须取消在途请求，不再等它跑完');

      completer.single.complete(pageOf(
        RequestOptions(path: '/goods/list'),
        [goodsItem],
      ));
      await pending;
      expect(controller.goodsList, isEmpty);
      expect(controller.errorMessage.value, isEmpty,
          reason: '控制器已关闭，迟到响应不得再写入任何状态');
    });

    test('7. FavoriteController / HistoryController：onClose 同样取消在途请求', () async {
      final favoriteTokens = <CancelToken?>[];
      final historyTokens = <CancelToken?>[];

      installMockApi((options) {
        if (options.path.contains('/favorite/list')) {
          favoriteTokens.add(options.cancelToken);
          return Completer<Response>().future;
        }
        if (options.path.contains('/history/list')) {
          historyTokens.add(options.cancelToken);
          return Completer<Response>().future;
        }
        return jsonOk(options, <dynamic>[]);
      });

      final favorite = FavoriteController();
      unawaited(favorite.loadFavorites(refresh: true));
      await microtasks();
      final CancelToken favoriteToken = favoriteTokens.single!;
      expect(favoriteToken.isCancelled, isFalse);
      favorite.onClose();
      expect(favoriteToken.isCancelled, isTrue,
          reason: '收藏页离开时必须取消在途的列表请求');

      final history = HistoryController();
      unawaited(history.loadHistory(refresh: true));
      await microtasks();
      final CancelToken historyToken = historyTokens.single!;
      expect(historyToken.isCancelled, isFalse);
      history.onClose();
      expect(historyToken.isCancelled, isTrue,
          reason: '足迹页离开时必须取消在途的列表请求');
    });

    test('8. OrderController：切标签/刷新会取消上一个在途的列表请求', () async {
      final tokens = <CancelToken?>[];
      final completer = <Completer<Response>>[];

      installMockApi((options) {
        if (options.path.contains('/orders/my')) {
          tokens.add(options.cancelToken);
          final c = Completer<Response>();
          completer.add(c);
          return c.future;
        }
        return jsonOk(options, <dynamic>[]);
      });

      final controller = OrderController();
      final first = controller.fetchMyOrders(refresh: true);
      await microtasks();
      final CancelToken firstToken = tokens.single!;
      expect(firstToken.isCancelled, isFalse);

      // 切换到"我的卖出"
      final switching = controller.switchRole('SELLER');
      expect(firstToken.isCancelled, isTrue,
          reason: '切换视角后旧的列表请求必须被取消');
      await microtasks();
      expect(tokens.length, 2);

      completer[0].complete(pageOf(RequestOptions(path: '/orders/my'), []));
      await first;
      expect(controller.orders, isEmpty);

      completer[1].complete(pageOf(RequestOptions(path: '/orders/my'), [orderJson]));
      await switching;
      expect(controller.orders.length, 1);
      expect(controller.currentRole.value, 'SELLER');
    });
  });
}
