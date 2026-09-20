import 'package:dio/dio.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:frontend/api/dio_client.dart';
import 'package:frontend/controllers/auth_controller.dart';
import 'package:frontend/controllers/order_controller.dart';
import 'package:frontend/controllers/review_controller.dart';
import 'package:frontend/models/order.dart';
import 'package:frontend/models/user_model.dart';
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
    await Get.putAsync(() => StorageService().init(), permanent: true);
    final auth = Get.put(AuthController(), permanent: true);
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
        creditScore: 135,
        tradeCount: 15,
        goodReviewCount: 14,
        badReviewCount: 1,
        completedCount: 15,
        cancelCount: 1,
        creditLevel: 'EXCELLENT',
      ),
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

  final sampleGoodsDetail = {
    'id': 501,
    'title': 'iPad Pro 11寸 256G',
    'price': 3999.00,
    'originalPrice': 5999.00,
    'description': '成色很好，电池健康95%',
    'images': <String>[],
    'categoryId': 1,
    'categoryName': '数码产品',
    'schoolId': 1,
    'schoolName': '清华大学',
    'location': '紫荆公寓1号楼',
    'sellerId': 2002,
    'sellerUsername': 'seller_test',
    'sellerNickname': '极客学长',
    'sellerAvatar': null,
    'sellerVerified': true,
    'sellerSchoolName': '清华大学',
    'sellerCreditScore': 150,
    'sellerTradeCount': 20,
    'sellerGoodReviewCount': 20,
    'conditionLevel': '95新',
    'status': 'ON_SALE',
    'viewCount': 128,
    'favoriteCount': 12,
    'isFavorite': false,
    'createdTime': '2026-09-10 10:00:00',
  };

  final sampleCompletedOrder = OrderVO(
    id: '901',
    orderNo: 'ORD2026091800901',
    goodsId: '501',
    goodsTitleSnapshot: 'iPad Pro 11寸 256G',
    goodsPriceSnapshot: 3999.00,
    buyerId: '1001',
    sellerId: '2002',
    buyer: OrderUserInfo(id: '1001', username: 'buyer_test', nickname: '买家测试'),
    seller: OrderUserInfo(id: '2002', username: 'seller_test', nickname: '极客学长'),
    orderStatus: OrderStatus.completed,
    statusDescription: '已完成',
    meetLocation: '紫荆公寓',
  );

  group('Stage 5-E: 商品评价展示与匿名脱敏测试', () {
    testWidgets('1. 商品详情页展示历史评价列表、星级、标签与匿名脱敏保护',
        (WidgetTester tester) async {
      installMockApi((options) {
        if (options.path.contains('/reviews/goods/501')) {
          return Response(
            requestOptions: options,
            statusCode: 200,
            data: {
              'code': 200,
              'message': 'success',
              'data': {
                'records': [
                  {
                    'id': 101,
                    'orderId': 901,
                    'goodsId': 501,
                    'reviewedUserId': 2002,
                    'reviewerNickname': '校友***',
                    'reviewerAvatar': null,
                    'score': 5,
                    'content': '平板成色跟描述完全一致，面交非常爽快！',
                    'tags': ['守时诚信', '成色极佳'],
                    'isAnonymous': true,
                    'createdTime': '2026-09-18 10:30',
                  },
                ],
                'total': 1,
                'current': 1,
                'size': 10,
                'pages': 1,
              },
            },
          );
        } else if (options.path.contains('/goods/501')) {
          return Response(
            requestOptions: options,
            statusCode: 200,
            data: {
              'code': 200,
              'message': 'success',
              'data': sampleGoodsDetail,
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
      Get.toNamed(AppRoutes.goodsDetail, arguments: 501);
      await tester.pumpAndSettle();

      expect(find.text('商品评价 (1)'), findsOneWidget);
      expect(find.text('校友***'), findsOneWidget);
      expect(find.text('匿名'), findsOneWidget);
      expect(find.text('平板成色跟描述完全一致，面交非常爽快！'), findsOneWidget);
      expect(find.text('成色极佳'), findsOneWidget);
    });

    testWidgets('2. 商品详情页暂无评价时展示友好空状态',
        (WidgetTester tester) async {
      installMockApi((options) {
        if (options.path.contains('/reviews/goods/501')) {
          return Response(
            requestOptions: options,
            statusCode: 200,
            data: {
              'code': 200,
              'message': 'success',
              'data': {
                'records': [],
                'total': 0,
                'current': 1,
                'size': 10,
                'pages': 0,
              },
            },
          );
        } else if (options.path.contains('/goods/501')) {
          return Response(
            requestOptions: options,
            statusCode: 200,
            data: {
              'code': 200,
              'message': 'success',
              'data': sampleGoodsDetail,
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
      Get.toNamed(AppRoutes.goodsDetail, arguments: 501);
      await tester.pumpAndSettle();

      expect(find.text('商品评价 (0)'), findsOneWidget);
      expect(find.text('该商品暂无评价，交易完成后可发表评价'), findsOneWidget);
    });
  });

  group('Stage 5-E: 信用中心与 Profile 页面渲染测试', () {
    test('3. UserCreditModel 等级推导与缺失容错', () {
      // 130 分以上为 EXCELLENT
      final c1 = UserCreditModel(creditScore: 140, tradeCount: 10, goodReviewCount: 10, badReviewCount: 0);
      expect(c1.computedCreditLevel, 'EXCELLENT');
      expect(c1.levelDescription, '信用极好');

      // 100~129 分为 GOOD
      final c2 = UserCreditModel(creditScore: 110, tradeCount: 5, goodReviewCount: 5, badReviewCount: 0);
      expect(c2.computedCreditLevel, 'GOOD');
      expect(c2.levelDescription, '信用良好');

      // 80~99 分为 FAIR
      final c3 = UserCreditModel(creditScore: 90, tradeCount: 2, goodReviewCount: 2, badReviewCount: 1);
      expect(c3.computedCreditLevel, 'FAIR');
      expect(c3.levelDescription, '信用中等');

      // 80 分以下为 POOR
      final c4 = UserCreditModel(creditScore: 65, tradeCount: 3, goodReviewCount: 1, badReviewCount: 2);
      expect(c4.computedCreditLevel, 'POOR');
      expect(c4.levelDescription, '信用较低');

      // 缺省 completedCount / cancelCount 容错
      final c5 = UserCreditModel.fromJson({'creditScore': 105, 'tradeCount': 8});
      expect(c5.completedCount, 8);
      expect(c5.cancelCount, 0);
      expect(c5.computedCreditLevel, 'GOOD');
    });

    testWidgets('4. ProfilePage 信用中心指标与收到的评价列表正常展示',
        (WidgetTester tester) async {
      installMockApi((options) {
        if (options.path.contains('/reviews/user/1001')) {
          return Response(
            requestOptions: options,
            statusCode: 200,
            data: {
              'code': 200,
              'message': 'success',
              'data': {
                'records': [
                  {
                    'id': 201,
                    'orderId': 901,
                    'goodsId': 501,
                    'reviewedUserId': 1001,
                    'reviewerNickname': '校友***',
                    'reviewerAvatar': null,
                    'score': 5,
                    'content': '买家同学守时礼貌，交易非常愉快！',
                    'tags': ['爽快买家', '守时面交'],
                    'isAnonymous': true,
                    'createdTime': '2026-09-18 11:00',
                  }
                ],
                'total': 1,
                'current': 1,
                'size': 10,
                'pages': 1,
              },
            },
          );
        }
        return Response(requestOptions: options, statusCode: 200, data: {'code': 200});
      });

      await tester.pumpWidget(
        GetMaterialApp(
          initialRoute: AppRoutes.home,
          getPages: AppPages.routes,
        ),
      );
      Get.toNamed(AppRoutes.profile);
      await tester.pumpAndSettle();

      // 验证信用中心卡片
      expect(find.text('校园信誉档案'), findsOneWidget);
      expect(find.text('135'), findsOneWidget);
      expect(find.text('信用分范围：0～200'), findsOneWidget);
      expect(find.text('信用极好 (EXCELLENT)'), findsOneWidget);
      expect(find.text('完成交易'), findsOneWidget);
      expect(find.text('15 笔'), findsOneWidget);
      expect(find.text('取消交易'), findsOneWidget);
      expect(find.text('1 笔'), findsOneWidget);
      expect(find.text('好评数'), findsOneWidget);
      expect(find.text('14'), findsOneWidget);

      // 验证收到的评价列表
      expect(find.text('收到的评价'), findsOneWidget);
      expect(find.text('买家同学守时礼貌，交易非常愉快！'), findsOneWidget);
      expect(find.text('爽快买家'), findsOneWidget);
    });
  });

  group('Stage 5-E: 全链路状态同步测试', () {
    test('5. 提交评价后跨控制器同步：订单详情刷新与信用数据更新', () async {
      int fetchOrderCalled = 0;
      int fetchProfileCalled = 0;

      installMockApi((options) {
        if (options.path.contains('/reviews') && options.method == 'POST') {
          return Response(
            requestOptions: options,
            statusCode: 200,
            data: {
              'code': 200,
              'message': '评价发表成功',
              'data': {
                'id': 888,
                'orderId': 901,
                'goodsId': 501,
                'reviewedUserId': 2002,
                'score': 5,
                'content': '非常满意',
                'isAnonymous': false,
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
              'data': {
                'orderId': 901,
                'isBuyer': true,
                'canReview': false, // 提交后变为不可评价
                'myReview': {
                  'id': 888,
                  'orderId': 901,
                  'goodsId': 501,
                  'reviewedUserId': 2002,
                  'score': 5,
                  'content': '非常满意',
                  'isAnonymous': false,
                },
                'peerReview': null,
              },
            },
          );
        } else if (options.path.contains('/orders/901')) {
          fetchOrderCalled++;
          return Response(
            requestOptions: options,
            statusCode: 200,
            data: {
              'code': 200,
              'message': 'success',
              'data': sampleCompletedOrder.toJson(),
            },
          );
        } else if (options.path.contains('/user/profile')) {
          fetchProfileCalled++;
          return Response(
            requestOptions: options,
            statusCode: 200,
            data: {
              'code': 200,
              'message': 'success',
              'data': {
                'id': 1001,
                'username': 'buyer_test',
                'nickname': '买家测试',
                'credit': {
                  'creditScore': 138, // 信用分 +3
                  'tradeCount': 16,
                  'completedCount': 16,
                  'cancelCount': 1,
                  'creditLevel': 'EXCELLENT',
                },
              },
            },
          );
        }
        return Response(requestOptions: options, statusCode: 200, data: {'code': 200});
      });

      Get.put(OrderController());
      final reviewCtrl = Get.put(ReviewController());

      // 执行提交评价
      reviewCtrl.setScore(5);
      reviewCtrl.setContent('非常满意');
      final res = await reviewCtrl.submitReview('901');

      expect(res.isSuccess, isTrue);
      // 验证跨控制器同步调用
      expect(fetchOrderCalled, greaterThanOrEqualTo(1));
      expect(fetchProfileCalled, greaterThanOrEqualTo(1));

      // 验证订单双向评价状态已同步为已评价且不可再评
      final updatedStatus = reviewCtrl.orderReviewStatusMap['901'];
      expect(updatedStatus?.canReview, isFalse);
      expect(updatedStatus?.currentUserReviewed, isTrue);
    });

    testWidgets('6. 返回订单详情页后不会重新出现“去评价”按钮',
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
                'canReview': false, // 已评价状态
                'myReview': {
                  'id': 888,
                  'orderId': 901,
                  'goodsId': 501,
                  'reviewedUserId': 2002,
                  'score': 5,
                  'content': '五星好评',
                  'isAnonymous': false,
                },
                'peerReview': null,
              },
            },
          );
        }
        return Response(requestOptions: options, statusCode: 200, data: {'code': 200});
      });

      await tester.pumpWidget(
        GetMaterialApp(
          initialRoute: AppRoutes.home,
          getPages: AppPages.routes,
        ),
      );
      Get.toNamed(AppRoutes.orderDetail, arguments: 901);
      await tester.pumpAndSettle();

      // 验证页面渲染“我的评价”，且绝对不包含“去评价”可操作按钮
      expect(find.text('我的评价'), findsOneWidget);
      expect(find.text('五星好评'), findsOneWidget);
      expect(find.text('去评价'), findsNothing);
    });
  });
}
