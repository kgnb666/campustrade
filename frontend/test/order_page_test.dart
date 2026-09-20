import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:frontend/api/dio_client.dart';
import 'package:frontend/controllers/auth_controller.dart';
import 'package:frontend/models/user_model.dart';
import 'package:frontend/pages/order/order_detail_page.dart';
import 'package:frontend/routes/app_pages.dart';
import 'package:frontend/routes/app_routes.dart';
import 'package:frontend/services/storage_service.dart';
import 'package:get/get.dart' hide Response;

void main() {
  Interceptor? mockInterceptor;

  final sampleWaitConfirmOrder = {
    'id': 101,
    'orderNo': 'ORD20260917000101',
    'goodsId': 801,
    'goodsTitleSnapshot': '考研英语词汇闪过 (正版无笔迹)',
    'goodsPriceSnapshot': 28.50,
    'goodsImageSnapshot': null,
    'meetLocation': '学生活动中心南门',
    'buyerMessage': '希望能在周五中午面交',
    'sellerReply': null,
    'buyerId': 1001,
    'sellerId': 1002,
    'buyer': {
      'id': 1001,
      'username': 'buyer_stu',
      'nickname': '买家张同学',
      'avatar': null,
    },
    'seller': {
      'id': 1002,
      'username': 'seller_stu',
      'nickname': '卖家李同学',
      'avatar': null,
    },
    'orderStatus': 'WAIT_SELLER_CONFIRM',
    'statusDesc': '待卖家确认',
    'createdTime': '2026-09-17 10:00:00',
  };

  final sampleWaitMeetOrder = {
    'id': 102,
    'orderNo': 'ORD20260917000102',
    'goodsId': 802,
    'goodsTitleSnapshot': '线性代数辅导讲义 (李永乐)',
    'goodsPriceSnapshot': 35.00,
    'goodsImageSnapshot': null,
    'meetLocation': '图书馆一层大厅',
    'buyerMessage': '请带上光盘',
    'sellerReply': '好的，光盘在封底',
    'buyerId': 1001,
    'sellerId': 1003,
    'buyer': {
      'id': 1001,
      'username': 'buyer_stu',
      'nickname': '买家张同学',
    },
    'seller': {
      'id': 1003,
      'username': 'seller_wang',
      'nickname': '卖家王同学',
    },
    'orderStatus': 'WAIT_MEET',
    'statusDesc': '待面交',
    'createdTime': '2026-09-17 11:00:00',
    'confirmedTime': '2026-09-17 11:15:00',
  };

  final sampleCompletedOrder = {
    'id': 103,
    'orderNo': 'ORD20260917000103',
    'goodsId': 803,
    'goodsTitleSnapshot': '罗技无线鼠标 M330',
    'goodsPriceSnapshot': 49.90,
    'goodsImageSnapshot': null,
    'meetLocation': '二食堂门口',
    'buyerMessage': '支持试用吗',
    'sellerReply': '可以当面通电测试',
    'buyerId': 1001,
    'sellerId': 1004,
    'buyer': {
      'id': 1001,
      'username': 'buyer_stu',
      'nickname': '买家张同学',
    },
    'seller': {
      'id': 1004,
      'username': 'seller_zhao',
      'nickname': '卖家赵同学',
    },
    'orderStatus': 'COMPLETED',
    'statusDesc': '已完成',
    'createdTime': '2026-09-17 09:00:00',
    'confirmedTime': '2026-09-17 09:10:00',
    'completedTime': '2026-09-17 12:30:00',
  };

  final sampleCancelledOrder = {
    'id': 104,
    'orderNo': 'ORD20260917000104',
    'goodsId': 804,
    'goodsTitleSnapshot': '大学物理实验报告册',
    'goodsPriceSnapshot': 15.00,
    'goodsImageSnapshot': null,
    'meetLocation': '实验楼B座',
    'buyerMessage': '急用',
    'sellerReply': null,
    'buyerId': 1001,
    'sellerId': 1005,
    'buyer': {
      'id': 1001,
      'username': 'buyer_stu',
      'nickname': '买家张同学',
    },
    'seller': {
      'id': 1005,
      'username': 'seller_qian',
      'nickname': '卖家钱同学',
    },
    'orderStatus': 'CANCELLED',
    'statusDesc': '已取消',
    'cancelReason': '临时有考试冲突无法按时面交',
    'createdTime': '2026-09-17 08:00:00',
    'cancelledTime': '2026-09-17 08:30:00',
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
      username: 'buyer_stu',
      nickname: '买家张同学',
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

  group('MyOrdersPage Widget Tests', () {
    testWidgets('1. 验证 MyOrdersPage 页面渲染、Tab 标签与 5 个状态筛选标签',
        (WidgetTester tester) async {
      installMockApi((options) {
        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {
            'code': 200,
            'message': 'success',
            'data': {
              'records': [sampleWaitConfirmOrder, sampleWaitMeetOrder],
              'total': 2,
              'current': 1,
              'size': 10,
              'pages': 1,
            },
          },
        );
      });

      await tester.pumpWidget(
        GetMaterialApp(
          initialRoute: AppRoutes.orderMy,
          getPages: AppPages.routes,
        ),
      );
      await tester.pumpAndSettle();

      // 验证页面主标题与两个 Tab
      expect(find.text('我的订单'), findsOneWidget);
      expect(find.text('我的购买'), findsOneWidget);
      expect(find.text('我的出售'), findsOneWidget);

      // 验证 5 个状态筛选标签
      expect(find.text('全部'), findsOneWidget);
      expect(find.text('待确认'), findsOneWidget);
      expect(find.text('待面交'), findsWidgets); // Filter chip & status badge
      expect(find.text('已完成'), findsOneWidget);
      expect(find.text('已取消'), findsOneWidget);

      // 验证商品卡片各要素
      expect(find.text('考研英语词汇闪过 (正版无笔迹)'), findsOneWidget);
      expect(find.text('28.50'), findsOneWidget);
      expect(find.text('待卖家确认'), findsWidgets);
      expect(find.text('ORD20260917000101'), findsOneWidget);
      expect(find.text('卖家: 卖家李同学'), findsOneWidget);
    });

    testWidgets('2. 验证 Tab 切换到“我的出售”与筛选条件变更',
        (WidgetTester tester) async {
      String? requestedRole;
      String? requestedStatus;

      installMockApi((options) {
        requestedRole = options.queryParameters['role']?.toString();
        requestedStatus = options.queryParameters['status']?.toString();
        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {
            'code': 200,
            'message': 'success',
            'data': {
              'records': <dynamic>[],
              'total': 0,
              'current': 1,
              'size': 10,
              'pages': 1,
            },
          },
        );
      });

      await tester.pumpWidget(
        GetMaterialApp(
          initialRoute: AppRoutes.orderMy,
          getPages: AppPages.routes,
        ),
      );
      await tester.pumpAndSettle();

      // 点击“我的出售”
      await tester.tap(find.text('我的出售'));
      await tester.pumpAndSettle();
      expect(requestedRole, equals('SELLER'));

      // 点击“待确认”筛选
      await tester.tap(find.widgetWithText(ChoiceChip, '待确认'));
      await tester.pumpAndSettle();
      expect(requestedStatus, equals('WAIT_SELLER_CONFIRM'));
    });

    testWidgets('3. 验证 MyOrdersPage 空状态展示与图标',
        (WidgetTester tester) async {
      installMockApi((options) {
        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {
            'code': 200,
            'message': 'success',
            'data': {
              'records': <dynamic>[],
              'total': 0,
              'current': 1,
              'size': 10,
              'pages': 1,
            },
          },
        );
      });

      await tester.pumpWidget(
        GetMaterialApp(
          initialRoute: AppRoutes.orderMy,
          getPages: AppPages.routes,
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('暂无相关订单'), findsOneWidget);
      expect(find.byIcon(Icons.receipt_long_outlined), findsWidgets);
    });

    testWidgets('4. 验证 MyOrdersPage 错误状态与点击重试',
        (WidgetTester tester) async {
      int requestCount = 0;
      installMockApi((options) {
        requestCount++;
        if (requestCount == 1) {
          throw DioException(
            requestOptions: options,
            response: Response(
              requestOptions: options,
              statusCode: 500,
              data: {'code': 500, 'message': '网络连接中断，请重试'},
            ),
          );
        }
        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {
            'code': 200,
            'message': 'success',
            'data': {
              'records': [sampleCompletedOrder],
              'total': 1,
              'current': 1,
              'size': 10,
              'pages': 1,
            },
          },
        );
      });

      await tester.pumpWidget(
        GetMaterialApp(
          initialRoute: AppRoutes.orderMy,
          getPages: AppPages.routes,
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('加载订单失败'), findsOneWidget);
      expect(find.text('网络连接中断，请重试'), findsOneWidget);
      expect(find.widgetWithText(ElevatedButton, '点击重试'), findsOneWidget);

      // 点击重试
      await tester.tap(find.widgetWithText(ElevatedButton, '点击重试'));
      await tester.pumpAndSettle();

      expect(find.text('罗技无线鼠标 M330'), findsOneWidget);
      expect(find.text('49.90'), findsOneWidget);
    });

    testWidgets('5. 验证点击订单卡片能够导航跳转到 OrderDetailPage',
        (WidgetTester tester) async {
      installMockApi((options) {
        if (options.path.contains('/orders/my')) {
          return Response(
            requestOptions: options,
            statusCode: 200,
            data: {
              'code': 200,
              'message': 'success',
              'data': {
                'records': [sampleWaitMeetOrder],
                'total': 1,
                'current': 1,
                'size': 10,
                'pages': 1,
              },
            },
          );
        } else if (options.path.contains('/orders/102')) {
          return Response(
            requestOptions: options,
            statusCode: 200,
            data: {
              'code': 200,
              'message': 'success',
              'data': sampleWaitMeetOrder,
            },
          );
        }
        return Response(requestOptions: options, statusCode: 200, data: {});
      });

      await tester.pumpWidget(
        GetMaterialApp(
          initialRoute: AppRoutes.orderMy,
          getPages: AppPages.routes,
        ),
      );
      await tester.pumpAndSettle();

      // 点击订单卡片
      await tester.tap(find.text('线性代数辅导讲义 (李永乐)'));
      await tester.pumpAndSettle();

      // 验证成功进入 OrderDetailPage
      expect(find.byType(OrderDetailPage), findsOneWidget);
      expect(find.text('订单流转进度'), findsOneWidget);
      expect(find.text('商品交易快照 (防篡改)'), findsOneWidget);
    });
  });

  group('OrderDetailPage Widget Tests', () {
    testWidgets('6. 验证正常订单详情渲染：状态时间线、快照、买卖双方、面交地点及留言',
        (WidgetTester tester) async {
      installMockApi((options) {
        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {
            'code': 200,
            'message': 'success',
            'data': sampleWaitMeetOrder,
          },
        );
      });

      await tester.pumpWidget(
        GetMaterialApp(
          initialRoute: AppRoutes.home,
          getPages: AppPages.routes,
        ),
      );
      Get.toNamed(AppRoutes.orderDetail, arguments: 102);
      await tester.pumpAndSettle();

      // 验证时间线节点
      expect(find.text('订单流转进度'), findsOneWidget);
      expect(find.text('待卖家确认'), findsWidgets);
      expect(find.text('待面交'), findsWidgets);
      expect(find.text('已完成'), findsWidgets);

      // 验证商品交易快照
      expect(find.text('商品交易快照 (防篡改)'), findsOneWidget);
      expect(find.text('线性代数辅导讲义 (李永乐)'), findsOneWidget);
      expect(find.text('35.00'), findsOneWidget);

      // 验证面交与留言
      expect(find.text('约定地点'), findsOneWidget);
      expect(find.text('图书馆一层大厅'), findsOneWidget);
      expect(find.text('买家留言'), findsOneWidget);
      expect(find.text('请带上光盘'), findsOneWidget);
      expect(find.text('卖家回复'), findsOneWidget);
      expect(find.text('好的，光盘在封底'), findsOneWidget);

      // 验证当事人信息卡片
      expect(find.text('交易当事人信息'), findsOneWidget);
      expect(find.text('买家张同学'), findsOneWidget);
      expect(find.text('卖家王同学'), findsOneWidget);

      // 验证业务订单号
      expect(find.text('ORD20260917000102'), findsOneWidget);
    });

    testWidgets('7. 验证已取消订单 (CANCELLED) 展示取消原因与取消时间',
        (WidgetTester tester) async {
      installMockApi((options) {
        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {
            'code': 200,
            'message': 'success',
            'data': sampleCancelledOrder,
          },
        );
      });

      await tester.pumpWidget(
        GetMaterialApp(
          initialRoute: AppRoutes.home,
          getPages: AppPages.routes,
        ),
      );
      Get.toNamed(AppRoutes.orderDetail, arguments: 104);
      await tester.pumpAndSettle();

      expect(find.text('订单已终止流转'), findsOneWidget);
      expect(find.text('取消原因: 临时有考试冲突无法按时面交'), findsOneWidget);
      expect(find.text('取消时间: 2026-09-17 08:30:00'), findsOneWidget);
      expect(find.text('大学物理实验报告册'), findsOneWidget);
    });

    testWidgets('8. 严格禁止原则验证：确保订单页面绝对不包含支付、聊天、评价等无关按钮',
        (WidgetTester tester) async {
      installMockApi((options) {
        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {
            'code': 200,
            'message': 'success',
            'data': sampleWaitConfirmOrder,
          },
        );
      });

      await tester.pumpWidget(
        GetMaterialApp(
          initialRoute: AppRoutes.home,
          getPages: AppPages.routes,
        ),
      );
      Get.toNamed(AppRoutes.orderDetail, arguments: 101);
      await tester.pumpAndSettle();

      // 严格校验禁止包含的业务操作按钮
      expect(find.widgetWithText(ElevatedButton, '去支付'), findsNothing);
      expect(find.widgetWithText(ElevatedButton, '立即支付'), findsNothing);
      expect(find.widgetWithText(ElevatedButton, '联系卖家'), findsNothing);
      expect(find.widgetWithText(ElevatedButton, '发消息'), findsNothing);
      expect(find.widgetWithText(ElevatedButton, '去评价'), findsNothing);
      expect(find.widgetWithText(ElevatedButton, '发表评价'), findsNothing);
      expect(find.byIcon(Icons.payment), findsNothing);
      expect(find.byIcon(Icons.rate_review), findsNothing);
    });
  });
}
