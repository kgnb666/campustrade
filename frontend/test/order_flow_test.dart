import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:frontend/api/dio_client.dart';
import 'package:frontend/controllers/auth_controller.dart';
import 'package:frontend/models/user_model.dart';
import 'package:frontend/pages/goods/goods_detail_page.dart';
import 'package:frontend/pages/order/order_detail_page.dart';
import 'package:frontend/routes/app_pages.dart';
import 'package:frontend/routes/app_routes.dart';
import 'package:frontend/services/storage_service.dart';
import 'package:get/get.dart' hide Response;

void main() {
  Interceptor? mockInterceptor;

  final sampleGoodsDetail = {
    'id': 501,
    'sellerId': 2002,
    'schoolId': 1,
    'schoolName': '清华大学',
    'categoryId': 1,
    'categoryName': '教材书籍',
    'title': '考研数学复习全书 (2026版)',
    'description': '九成新，附带核心公式手册',
    'price': 38.00,
    'originalPrice': 68.00,
    'conditionLevel': '9成新',
    'status': 'ON_SALE',
    'location': '二食堂门口',
    'viewCount': 100,
    'images': <String>[],
    'tags': <String>['考研', '数学'],
    'sellerUsername': 'seller_math',
    'sellerNickname': '数学学长',
    'sellerAvatar': null,
    'sellerVerified': true,
    'sellerSchoolName': '清华大学',
    'sellerCreditScore': 120,
    'sellerTradeCount': 15,
    'sellerGoodReviewCount': 15,
  };

  final sampleCreatedOrder = {
    'id': 901,
    'orderNo': 'ORD2026091700901',
    'goodsId': 501,
    'goodsTitleSnapshot': '考研数学复习全书 (2026版)',
    'goodsPriceSnapshot': 38.00,
    'goodsImageSnapshot': null,
    'meetLocation': '二食堂门口',
    'buyerMessage': '周五下午4点可否交接？',
    'buyerId': 1001,
    'sellerId': 2002,
    'buyer': {
      'id': 1001,
      'username': 'buyer_test',
      'nickname': '买家测试',
    },
    'seller': {
      'id': 2002,
      'username': 'seller_math',
      'nickname': '数学学长',
    },
    'orderStatus': 'WAIT_SELLER_CONFIRM',
    'statusDesc': '待卖家确认',
    'createdTime': '2026-09-17 15:00:00',
  };

  void installMockApi(Response Function(RequestOptions options) responder) {
    if (mockInterceptor != null) {
      DioClient().dio.interceptors.remove(mockInterceptor);
    }
    mockInterceptor = InterceptorsWrapper(
      onRequest: (options, handler) {
        try {
          final resp = responder(options);
          return handler.resolve(resp);
        } on DioException catch (dioErr) {
          return handler.reject(dioErr);
        } catch (e) {
          return handler.reject(DioException(
            requestOptions: options,
            error: e,
          ));
        }
      },
    );
    DioClient().dio.interceptors.insert(0, mockInterceptor!);
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

  group('Stage 4-D-3 Order Flow Tests', () {
    testWidgets('1. 买家在商品详情页立即下单 -> 弹窗确认 -> 创建成功并跳转订单详情',
        (WidgetTester tester) async {
      int createOrderApiCalled = 0;

      installMockApi((options) {
        if (options.path.contains('/goods/501')) {
          return Response(
            requestOptions: options,
            statusCode: 200,
            data: {
              'code': 200,
              'message': 'success',
              'data': sampleGoodsDetail,
            },
          );
        } else if (options.path.contains('/orders') &&
            options.method == 'POST') {
          createOrderApiCalled++;
          final data = options.data as Map<String, dynamic>;
          expect(data['goodsId'], equals('501'));
          expect(data['meetLocation'], equals('二食堂门口'));
          expect(data['buyerMessage'], equals('周五下午4点可否交接？'));

          return Response(
            requestOptions: options,
            statusCode: 200,
            data: {
              'code': 200,
              'message': '下单成功',
              'data': sampleCreatedOrder,
            },
          );
        } else if (options.path.contains('/orders/901')) {
          return Response(
            requestOptions: options,
            statusCode: 200,
            data: {
              'code': 200,
              'message': 'success',
              'data': sampleCreatedOrder,
            },
          );
        }
        return Response(requestOptions: options, statusCode: 200, data: {});
      });

      await tester.pumpWidget(
        GetMaterialApp(
          initialRoute: AppRoutes.home,
          getPages: AppPages.routes,
        ),
      );
      Get.toNamed(AppRoutes.goodsDetail, arguments: 501);
      await tester.pumpAndSettle();

      // 验证商品详情页展示“立即下单”按钮
      expect(find.byType(GoodsDetailPage), findsOneWidget);
      expect(find.widgetWithText(ElevatedButton, '立即下单'), findsOneWidget);

      // 点击“立即下单”
      await tester.tap(find.widgetWithText(ElevatedButton, '立即下单'));
      await tester.pumpAndSettle();

      // 验证弹出下单确认抽屉
      expect(find.text('确认购买意向与下单'), findsOneWidget);
      expect(find.text('约定面交地点'), findsOneWidget);
      expect(find.text('买家留言 (选填)'), findsOneWidget);

      // 输入买家留言
      await tester.enterText(
        find.widgetWithText(TextField, '买家留言 (选填)'),
        '周五下午4点可否交接？',
      );
      await tester.pumpAndSettle();

      // 点击“确认下单”
      await tester.tap(find.widgetWithText(ElevatedButton, '确认下单'));
      await tester.pumpAndSettle();

      // 验证 API 成功触发调用
      expect(createOrderApiCalled, equals(1));

      // 验证自动跳转至 OrderDetailPage 并渲染订单流转信息
      expect(find.byType(OrderDetailPage), findsOneWidget);
      expect(find.text('ORD2026091700901'), findsOneWidget);
      expect(find.text('待卖家确认'), findsWidgets);

      // 等待 snackbar 定时器与退场动画完全结束
      await tester.pump(const Duration(seconds: 5));
      await tester.pumpAndSettle();
    });

    testWidgets('2. 卖家确认接单：WAIT_SELLER_CONFIRM 状态下点击“确认接单” -> PUT confirm -> 刷新为 WAIT_MEET',
        (WidgetTester tester) async {
      // 切换当前用户为卖家 2002
      final auth = Get.find<AuthController>();
      auth.currentUser.value = UserProfileModel(
        id: '2002',
        username: 'seller_math',
        nickname: '数学学长',
        role: 'USER',
        status: 'ACTIVE',
        verifyStatus: 'SUCCESS',
      );

      final confirmedOrderMap = Map<String, dynamic>.from(sampleCreatedOrder);
      confirmedOrderMap['orderStatus'] = 'WAIT_MEET';
      confirmedOrderMap['statusDesc'] = '待面交';
      confirmedOrderMap['confirmedTime'] = '2026-09-17 15:30:00';

      int confirmCalled = 0;

      installMockApi((options) {
        if (options.path.contains('/orders/901/confirm') &&
            options.method == 'PUT') {
          confirmCalled++;
          return Response(
            requestOptions: options,
            statusCode: 200,
            data: {
              'code': 200,
              'message': '接单成功',
              'data': confirmedOrderMap,
            },
          );
        } else if (options.path.contains('/orders/901')) {
          return Response(
            requestOptions: options,
            statusCode: 200,
            data: {
              'code': 200,
              'message': 'success',
              'data': sampleCreatedOrder,
            },
          );
        }
        return Response(requestOptions: options, statusCode: 200, data: {});
      });

      await tester.pumpWidget(
        GetMaterialApp(
          initialRoute: AppRoutes.home,
          getPages: AppPages.routes,
        ),
      );
      Get.toNamed(AppRoutes.orderDetail, arguments: 901);
      await tester.pumpAndSettle();

      // 验证卖家视角展示“确认接单”与“取消订单”按钮
      expect(find.widgetWithText(ElevatedButton, '确认接单'), findsOneWidget);
      expect(find.widgetWithText(OutlinedButton, '取消订单'), findsOneWidget);

      // 卖家点击“确认接单”
      await tester.tap(find.widgetWithText(ElevatedButton, '确认接单'));
      await tester.pumpAndSettle();

      expect(confirmCalled, equals(1));

      // 状态已流转为 WAIT_MEET
      expect(find.text('待面交'), findsWidgets);
      // 接单按钮消失，出现“完成交易”
      expect(find.widgetWithText(ElevatedButton, '确认接单'), findsNothing);
      expect(find.widgetWithText(ElevatedButton, '完成交易'), findsOneWidget);

      await tester.pump(const Duration(seconds: 5));
      await tester.pumpAndSettle();
    });

    testWidgets('3. 完成交易：WAIT_MEET 状态下点击“完成交易” -> 二次确认弹窗 -> 变迁为 COMPLETED',
        (WidgetTester tester) async {
      final waitMeetOrderMap = Map<String, dynamic>.from(sampleCreatedOrder);
      waitMeetOrderMap['orderStatus'] = 'WAIT_MEET';
      waitMeetOrderMap['statusDesc'] = '待面交';
      waitMeetOrderMap['confirmedTime'] = '2026-09-17 15:30:00';

      final completedOrderMap = Map<String, dynamic>.from(waitMeetOrderMap);
      completedOrderMap['orderStatus'] = 'COMPLETED';
      completedOrderMap['statusDesc'] = '已完成';
      completedOrderMap['completedTime'] = '2026-09-17 16:00:00';

      int completeCalled = 0;

      installMockApi((options) {
        if (options.path.contains('/orders/901/complete') &&
            options.method == 'PUT') {
          completeCalled++;
          return Response(
            requestOptions: options,
            statusCode: 200,
            data: {
              'code': 200,
              'message': '交易完成',
              'data': completedOrderMap,
            },
          );
        } else if (options.path.contains('/orders/901')) {
          return Response(
            requestOptions: options,
            statusCode: 200,
            data: {
              'code': 200,
              'message': 'success',
              'data': waitMeetOrderMap,
            },
          );
        }
        return Response(requestOptions: options, statusCode: 200, data: {});
      });

      await tester.pumpWidget(
        GetMaterialApp(
          initialRoute: AppRoutes.home,
          getPages: AppPages.routes,
        ),
      );
      Get.toNamed(AppRoutes.orderDetail, arguments: 901);
      await tester.pumpAndSettle();

      // 验证买卖双方可见“完成交易”按钮
      expect(find.widgetWithText(ElevatedButton, '完成交易'), findsOneWidget);

      // 点击“完成交易”
      await tester.tap(find.widgetWithText(ElevatedButton, '完成交易'));
      await tester.pumpAndSettle();

      // 严格验证二次确认弹窗与提示文案
      expect(find.text('完成交易确认'), findsOneWidget);
      expect(find.text('确认双方已经完成线下面交？'), findsOneWidget);

      // 点击弹窗中的“确认完成”
      await tester.tap(find.widgetWithText(ElevatedButton, '确认完成'));
      await tester.pumpAndSettle();

      expect(completeCalled, equals(1));

      // 验证状态变迁为 COMPLETED
      expect(find.text('已完成'), findsWidgets);
      // 终态保护：完成交易与取消订单按钮均消失
      expect(find.widgetWithText(ElevatedButton, '完成交易'), findsNothing);
      expect(find.widgetWithText(OutlinedButton, '取消订单'), findsNothing);

      await tester.pump(const Duration(seconds: 5));
      await tester.pumpAndSettle();
    });

    testWidgets('4. 取消订单：填写 cancelReason -> 校验非空 -> 变迁为 CANCELLED 并展示取消信息',
        (WidgetTester tester) async {
      final cancelledOrderMap = Map<String, dynamic>.from(sampleCreatedOrder);
      cancelledOrderMap['orderStatus'] = 'CANCELLED';
      cancelledOrderMap['statusDesc'] = '已取消';
      cancelledOrderMap['cancelReason'] = '买家临时变动取消';
      cancelledOrderMap['cancelledTime'] = '2026-09-17 15:40:00';

      int cancelCalled = 0;

      installMockApi((options) {
        if (options.path.contains('/orders/901/cancel') &&
            options.method == 'PUT') {
          cancelCalled++;
          final body = options.data as Map<String, dynamic>;
          expect(body['cancelReason'], equals('买家临时变动取消'));
          return Response(
            requestOptions: options,
            statusCode: 200,
            data: {
              'code': 200,
              'message': '订单已取消',
              'data': cancelledOrderMap,
            },
          );
        } else if (options.path.contains('/orders/901')) {
          return Response(
            requestOptions: options,
            statusCode: 200,
            data: {
              'code': 200,
              'message': 'success',
              'data': sampleCreatedOrder,
            },
          );
        }
        return Response(requestOptions: options, statusCode: 200, data: {});
      });

      await tester.pumpWidget(
        GetMaterialApp(
          initialRoute: AppRoutes.home,
          getPages: AppPages.routes,
        ),
      );
      Get.toNamed(AppRoutes.orderDetail, arguments: 901);
      await tester.pumpAndSettle();

      // 点击“取消订单”
      expect(find.widgetWithText(OutlinedButton, '取消订单'), findsOneWidget);
      await tester.tap(find.widgetWithText(OutlinedButton, '取消订单'));
      await tester.pumpAndSettle();

      // 验证取消弹窗
      expect(find.text('取消订单'), findsWidgets);
      expect(find.text('取消原因 (必填)'), findsOneWidget);

      // 先测试空内容直接提交，应被拦截
      await tester.tap(find.widgetWithText(ElevatedButton, '确认取消'));
      await tester.pumpAndSettle();
      expect(cancelCalled, equals(0)); // 尚未调用
      await tester.pump(const Duration(seconds: 5));
      await tester.pumpAndSettle();

      // 输入有效原因
      await tester.enterText(
        find.widgetWithText(TextField, '取消原因 (必填)'),
        '买家临时变动取消',
      );
      await tester.pumpAndSettle();

      // 点击确认取消
      await tester.tap(find.widgetWithText(ElevatedButton, '确认取消'));
      await tester.pumpAndSettle();

      expect(cancelCalled, equals(1));

      // 验证展示取消时间线卡片与终止原因
      expect(find.text('订单已终止流转'), findsOneWidget);
      expect(find.text('取消原因: 买家临时变动取消'), findsOneWidget);
      // 终态保护：无任何操作按钮
      expect(find.widgetWithText(OutlinedButton, '取消订单'), findsNothing);
      expect(find.widgetWithText(ElevatedButton, '确认接单'), findsNothing);

      await tester.pump(const Duration(seconds: 5));
      await tester.pumpAndSettle();
    });

    testWidgets('5. 终态保护与安全原则：已完成与已取消订单彻底禁止继续流转操作',
        (WidgetTester tester) async {
      final completedOrderMap = Map<String, dynamic>.from(sampleCreatedOrder);
      completedOrderMap['orderStatus'] = 'COMPLETED';
      completedOrderMap['statusDesc'] = '已完成';
      completedOrderMap['completedTime'] = '2026-09-17 16:00:00';

      installMockApi((options) {
        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {
            'code': 200,
            'message': 'success',
            'data': completedOrderMap,
          },
        );
      });

      await tester.pumpWidget(
        GetMaterialApp(
          initialRoute: AppRoutes.home,
          getPages: AppPages.routes,
        ),
      );
      Get.toNamed(AppRoutes.orderDetail, arguments: 901);
      await tester.pumpAndSettle();

      // 终态校验：底栏无任何操作按钮
      expect(find.widgetWithText(ElevatedButton, '确认接单'), findsNothing);
      expect(find.widgetWithText(ElevatedButton, '完成交易'), findsNothing);
      expect(find.widgetWithText(OutlinedButton, '取消订单'), findsNothing);

      // 业务禁止项校验：绝对无支付、聊天、评价按钮
      expect(find.widgetWithText(ElevatedButton, '去支付'), findsNothing);
      expect(find.widgetWithText(ElevatedButton, '立即支付'), findsNothing);
      expect(find.widgetWithText(ElevatedButton, '联系卖家'), findsNothing);
      expect(find.widgetWithText(ElevatedButton, '去评价'), findsNothing);
    });
  });
}
