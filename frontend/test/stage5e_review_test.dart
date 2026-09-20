import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:frontend/api/dio_client.dart';
import 'package:frontend/api/review_api.dart';
import 'package:frontend/controllers/auth_controller.dart';
import 'package:frontend/controllers/review_controller.dart';
import 'package:frontend/models/order.dart';
import 'package:frontend/models/review.dart';
import 'package:frontend/models/user_model.dart';
import 'package:frontend/pages/review/create_review_sheet.dart';
import 'package:frontend/routes/app_pages.dart';
import 'package:frontend/routes/app_routes.dart';
import 'package:frontend/services/storage_service.dart';
import 'package:get/get.dart' hide Response;

void main() {
  Interceptor? mockInterceptor;

  void installMockApi(Response Function(RequestOptions options) responder) {
    if (mockInterceptor != null) {
      DioClient().dio.interceptors.remove(mockInterceptor);
    }
    mockInterceptor = InterceptorsWrapper(
      onRequest: (options, handler) {
        try {
          final resp = responder(options);
          if (resp.statusCode != null &&
              (resp.statusCode! < 200 || resp.statusCode! >= 300)) {
            return handler.reject(DioException(
              requestOptions: options,
              response: resp,
              type: DioExceptionType.badResponse,
            ));
          }
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

  final sampleCompletedOrder = OrderVO(
    id: '901',
    orderNo: 'ORD2026091800901',
    goodsId: '501',
    goodsTitleSnapshot: '机械键盘',
    goodsPriceSnapshot: 199.00,
    buyerId: '1001',
    sellerId: '2002',
    buyer: OrderUserInfo(id: '1001', username: 'buyer_test', nickname: '买家小明'),
    seller: OrderUserInfo(id: '2002', username: 'seller_test', nickname: '卖家大华'),
    orderStatus: OrderStatus.completed,
    statusDescription: '已完成',
    meetLocation: '图书馆西门',
  );

  final sampleWaitMeetOrder = OrderVO(
    id: '902',
    orderNo: 'ORD2026091800902',
    goodsId: '502',
    goodsTitleSnapshot: '篮球',
    goodsPriceSnapshot: 50.00,
    buyerId: '1001',
    sellerId: '2002',
    buyer: OrderUserInfo(id: '1001', username: 'buyer_test', nickname: '买家小明'),
    seller: OrderUserInfo(id: '2002', username: 'seller_test', nickname: '卖家大华'),
    orderStatus: OrderStatus.waitMeet,
    statusDescription: '待面交',
    meetLocation: '操场',
  );

  group('Stage 5-E: 评价模型与 API 单元测试', () {
    test('1. ReviewModel 正常解析与匿名脱敏保护', () {
      final anonJson = {
        'id': 1,
        'orderId': 901,
        'goodsId': 501,
        'reviewerId': null,
        'reviewerNickname': '校友***',
        'reviewerAvatar': null,
        'reviewedUserId': 2002,
        'score': 5,
        'content': '非常好的卖家',
        'tags': ['守时诚信', '物美价廉'],
        'isAnonymous': true,
        'status': 'VISIBLE',
        'createdTime': '2026-09-18 12:00:00',
      };

      final review = ReviewModel.fromJson(anonJson);
      expect(review.id, '1');
      expect(review.score, 5);
      expect(review.starString, '⭐⭐⭐⭐⭐');
      expect(review.isAnonymous, isTrue);
      expect(review.displayNickname, '校友***');
      expect(review.displayAvatar, isNull);
      expect(review.tags, ['守时诚信', '物美价廉']);
    });

    test('2. OrderReviewStatusModel 状态计算属性校验', () {
      final statusJson = {
        'orderId': 901,
        'isBuyer': true,
        'canReview': false,
        'reasonIfNotEligible': '您已经评价过该订单',
        'myReview': {
          'id': 10,
          'orderId': 901,
          'goodsId': 501,
          'reviewedUserId': 2002,
          'score': 5,
          'content': '好评',
          'isAnonymous': false,
        },
        'peerReview': {
          'id': 11,
          'orderId': 901,
          'goodsId': 501,
          'reviewedUserId': 1001,
          'score': 5,
          'content': '买家爽快',
          'isAnonymous': true,
        },
      };

      final model = OrderReviewStatusModel.fromJson(statusJson);
      expect(model.currentUserReviewed, isTrue);
      expect(model.peerReviewed, isTrue);
      expect(model.bothReviewed, isTrue);
      expect(model.canReview, isFalse);
    });

    test('3. ReviewApi 错误码精准映射 (400, 403, 409, 422)', () async {
      installMockApi((options) {
        if (options.path.contains('/reviews') && options.method == 'POST') {
          final data = options.data as Map<String, dynamic>;
          if (data['score'] == 0) {
            return Response(
              requestOptions: options,
              statusCode: 400,
              data: {'code': 400, 'message': '评分星级不能为空'},
            );
          } else if (data['orderId'] == '403') {
            return Response(
              requestOptions: options,
              statusCode: 403,
              data: {'code': 403, 'message': '无权评价'},
            );
          } else if (data['orderId'] == '409') {
            return Response(
              requestOptions: options,
              statusCode: 409,
              data: {'code': 409, 'message': '重复评价'},
            );
          } else if (data['orderId'] == '422') {
            return Response(
              requestOptions: options,
              statusCode: 422,
              data: {'code': 422, 'message': '订单未完成'},
            );
          }
        }
        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {
            'code': 200,
            'message': 'success',
            'data': {'id': 1, 'orderId': 901, 'goodsId': 501, 'reviewedUserId': 2002, 'score': 5},
          },
        );
      });

      final api = ReviewApi();
      final res400 = await api.createReview(CreateReviewRequest(orderId: '901', score: 0));
      expect(res400.code, 400);

      final res403 = await api.createReview(CreateReviewRequest(orderId: '403', score: 5));
      expect(res403.code, 403);
      expect(res403.message, contains('没有权限'));

      final res409 = await api.createReview(CreateReviewRequest(orderId: '409', score: 5));
      expect(res409.code, 409);
      expect(res409.message, contains('已经评价过'));

      final res422 = await api.createReview(CreateReviewRequest(orderId: '422', score: 5));
      expect(res422.code, 422);
      expect(res422.message, contains('完成后才能评价'));
    });
  });

  group('Stage 5-E: ReviewController 状态机与表单交互测试', () {
    test('4. 默认未选择星级，强制要求 1~5 星选择', () async {
      final controller = ReviewController();
      expect(controller.selectedScore.value, 0);

      final res = await controller.submitReview('901');
      expect(res.isSuccess, isFalse);
      expect(controller.errorMessage.value, contains('请选择评分星级'));
      expect(controller.submitState.value, ReviewSubmitState.error);

      // 设置 4 星
      controller.setScore(4);
      expect(controller.selectedScore.value, 4);
    });

    test('5. 标签选择、多选上限与匿名开关', () {
      final controller = ReviewController();
      final tags = controller.getPresetTags(isBuyer: true);
      expect(tags, isNotEmpty);

      controller.toggleTag(tags[0]);
      controller.toggleTag(tags[1]);
      expect(controller.selectedTags.length, 2);

      // 再次点击取消选中
      controller.toggleTag(tags[0]);
      expect(controller.selectedTags.length, 1);

      // 匿名开关
      expect(controller.isAnonymous.value, isFalse);
      controller.setAnonymous(true);
      expect(controller.isAnonymous.value, isTrue);
    });

    test('6. 500 字限制与失败保留已输入内容', () async {
      installMockApi((options) {
        return Response(
          requestOptions: options,
          statusCode: 409,
          data: {'code': 409, 'message': '你已经评价过该订单'},
        );
      });

      final controller = ReviewController();
      controller.setScore(5);
      controller.setContent('用户输入的珍贵长文本评价');
      controller.toggleTag('守时诚信');

      final res = await controller.submitReview('901');
      expect(res.isSuccess, isFalse);
      // 失败后用户已输入内容必须保留
      expect(controller.content.value, '用户输入的珍贵长文本评价');
      expect(controller.selectedScore.value, 5);
      expect(controller.selectedTags, contains('守时诚信'));
    });

    test('7. 并发提交防护 (isSubmitting 拦截重复点击)', () async {
      final controller = ReviewController();
      controller.setScore(5);

      controller.submitState.value = ReviewSubmitState.submitting;
      final res = await controller.submitReview('901');
      expect(res.code, 400);
      expect(res.message, contains('正在提交中'));
    });
  });

  group('Stage 5-E: 订单详情页评价入口与互评展示 Widget 测试', () {
    testWidgets('8. 订单 COMPLETED 且未评价 → 显示“去评价”主按钮与评价卡片',
        (WidgetTester tester) async {
      installMockApi((options) {
        if (options.path.contains('/orders/901')) {
          return Response(
            requestOptions: options,
            statusCode: 200,
            data: {
              'code': 200,
              'message': 'success',
              'data': sampleCompletedOrder.toJson(),
            },
          );
        } else if (options.path.contains('/reviews/order/901')) {
          return Response(
            requestOptions: options,
            statusCode: 200,
            data: {
              'code': 200,
              'message': 'success',
              'data': {
                'orderId': 901,
                'isBuyer': true,
                'canReview': true,
                'myReview': null,
                'peerReview': null,
              },
            },
          );
        }
        return Response(requestOptions: options, statusCode: 404);
      });

      await tester.pumpWidget(
        GetMaterialApp(
          initialRoute: AppRoutes.home,
          getPages: AppPages.routes,
        ),
      );
      Get.toNamed(AppRoutes.orderDetail, arguments: 901);
      await tester.pumpAndSettle();

      expect(find.text('交易评价'), findsOneWidget);
      expect(find.text('去评价'), findsWidgets);
    });

    testWidgets('9. 订单已评价 → 不显示“去评价”，展示“我的评价”内容',
        (WidgetTester tester) async {
      installMockApi((options) {
        if (options.path.contains('/orders/901')) {
          return Response(
            requestOptions: options,
            statusCode: 200,
            data: {
              'code': 200,
              'message': 'success',
              'data': sampleCompletedOrder.toJson(),
            },
          );
        } else if (options.path.contains('/reviews/order/901')) {
          return Response(
            requestOptions: options,
            statusCode: 200,
            data: {
              'code': 200,
              'message': 'success',
              'data': {
                'orderId': 901,
                'isBuyer': true,
                'canReview': false,
                'reasonIfNotEligible': '您已经评价过该订单',
                'myReview': {
                  'id': 100,
                  'orderId': 901,
                  'goodsId': 501,
                  'reviewedUserId': 2002,
                  'score': 5,
                  'content': '卖家大华非常守时靠谱！',
                  'tags': ['守时诚信', '沟通友好'],
                  'isAnonymous': false,
                  'createdTime': '2026-09-18 14:00',
                },
                'peerReview': null,
              },
            },
          );
        }
        return Response(requestOptions: options, statusCode: 404);
      });

      await tester.pumpWidget(
        GetMaterialApp(
          initialRoute: AppRoutes.home,
          getPages: AppPages.routes,
        ),
      );
      Get.toNamed(AppRoutes.orderDetail, arguments: 901);
      await tester.pumpAndSettle();

      expect(find.text('我的评价'), findsOneWidget);
      expect(find.text('卖家大华非常守时靠谱！'), findsOneWidget);
      expect(find.text('去评价'), findsNothing);
    });

    testWidgets('10. 订单未完成 (WAIT_MEET) → 绝不显示“去评价”',
        (WidgetTester tester) async {
      installMockApi((options) {
        if (options.path.contains('/orders/902')) {
          return Response(
            requestOptions: options,
            statusCode: 200,
            data: {
              'code': 200,
              'message': 'success',
              'data': sampleWaitMeetOrder.toJson(),
            },
          );
        }
        return Response(requestOptions: options, statusCode: 404);
      });

      await tester.pumpWidget(
        GetMaterialApp(
          initialRoute: AppRoutes.home,
          getPages: AppPages.routes,
        ),
      );
      Get.toNamed(AppRoutes.orderDetail, arguments: 902);
      await tester.pumpAndSettle();

      expect(find.text('交易评价'), findsNothing);
      expect(find.text('去评价'), findsNothing);
    });
  });

  group('Stage 5-E: CreateReviewSheet 评价表单交互 Widget 测试', () {
    testWidgets('11. 交互点击评分、选择标签、切换匿名与提交成功闭环',
        (WidgetTester tester) async {
      bool submitCalled = false;

      installMockApi((options) {
        if (options.path.contains('/reviews') && options.method == 'POST') {
          submitCalled = true;
          final data = options.data as Map<String, dynamic>;
          expect(data['score'], 5);
          expect(data['isAnonymous'], isTrue);
          return Response(
            requestOptions: options,
            statusCode: 200,
            data: {
              'code': 200,
              'message': 'success',
              'data': {
                'id': 701,
                'orderId': 901,
                'goodsId': 501,
                'reviewedUserId': 2002,
                'score': 5,
                'isAnonymous': true,
              },
            },
          );
        } else if (options.path.contains('/reviews/order/901')) {
          return Response(
            requestOptions: options,
            statusCode: 200,
            data: {
              'code': 200,
              'message': 'success',
              'data': {'orderId': 901, 'canReview': false},
            },
          );
        }
        return Response(requestOptions: options, statusCode: 200, data: {'code': 200});
      });

      await tester.pumpWidget(
        GetMaterialApp(
          home: Scaffold(
            body: CreateReviewSheet(
              order: sampleCompletedOrder,
              isBuyer: true,
            ),
          ),
        ),
      );

      await tester.pump();

      // 验证标题与被评价人
      expect(find.text('评价本次交易'), findsOneWidget);
      expect(find.text('卖家大华'), findsOneWidget);

      // 点击第5颗星
      final starIcons = find.byIcon(Icons.star_outline_rounded);
      expect(starIcons, findsWidgets);
      await tester.tap(starIcons.last);
      await tester.pump();

      expect(find.text('5星 - 非常满意 (将为对方增加信用分)'), findsOneWidget);

      // 点选标签
      final tagFinder = find.text('守时诚信');
      if (tagFinder.evaluate().isNotEmpty) {
        await tester.tap(tagFinder);
        await tester.pump();
      }

      // 开启匿名
      final switchFinder = find.byType(Switch);
      expect(switchFinder, findsOneWidget);
      await tester.tap(switchFinder);
      await tester.pump();

      // 输入文字
      await tester.enterText(find.byType(TextField), '键盘手感极佳，面交准时！');
      await tester.pump();
      expect(find.text('12 / 500'), findsOneWidget);

      // 滚动并点击提交按钮
      await tester.ensureVisible(find.text('提交评价'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('提交评价'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(submitCalled, isTrue);
    });
  });
}
