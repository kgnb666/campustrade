import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:frontend/api/dio_client.dart';
import 'package:frontend/api/order_api.dart';
import 'package:frontend/controllers/auth_controller.dart';
import 'package:frontend/models/status_enums.dart';
import 'package:frontend/models/user_model.dart';
import 'package:frontend/routes/app_pages.dart';
import 'package:frontend/routes/app_routes.dart';
import 'package:frontend/services/storage_service.dart';
import 'package:frontend/utils/api_error.dart';
import 'package:get/get.dart' hide Response;

/// 阶段 7-D 前端契约套件。
///
/// 覆盖三件事：
/// 1. 未知订单状态 → 展示服务端原文 + 只读（不出现任何可点击的订单操作）；
/// 2. 商品状态枚举 →「已售出」不再被显示成「已下架」；
/// 3. 业务错误以真实 HTTP 状态返回后，提示文案仍取服务端 message（不退化成一整段英文说明）。
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
          // 与真实 Dio 行为一致：非 2xx 一律以 DioException 抛出
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
            type: DioExceptionType.unknown,
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
      id: '1',
      username: 'stage7d_user',
      nickname: '七丁同学',
      role: 'USER',
      status: 'ACTIVE',
      verifyStatus: 'SUCCESS',
      schoolName: '清华大学',
      studentNumber: '2026001',
    );
  });

  tearDown(() {
    if (mockInterceptor != null) {
      DioClient().dio.interceptors.remove(mockInterceptor);
      mockInterceptor = null;
    }
    Get.reset();
  });

  Map<String, dynamic> orderJson({
    required Object id,
    required String orderStatus,
    String? statusDesc,
  }) =>
      {
        'id': id,
        'orderNo': 'ORD7D$id',
        'goodsId': 501,
        'goodsTitleSnapshot': '二手考研英语真题',
        'goodsPriceSnapshot': 26.50,
        'goodsImageSnapshot': null,
        'meetLocation': '学生活动中心南门',
        'buyerMessage': '周五中午面交',
        'sellerReply': null,
        'buyerId': 1,
        'sellerId': 2,
        'buyer': {'id': 1, 'username': 'buyer7d', 'nickname': '买家七丁'},
        'seller': {'id': 2, 'username': 'seller7d', 'nickname': '卖家七丁'},
        'orderStatus': orderStatus,
        'statusDesc': ?statusDesc,
        'createdTime': '2026-09-18 10:00:00',
      };

  // =========================================================================
  // 1. 未知订单状态：原文展示 + 只读
  // =========================================================================

  group('Stage 7-D: 未知订单状态的只读契约', () {
    testWidgets('1. 未知状态展示服务端原文，且不渲染任何订单操作按钮',
        (WidgetTester tester) async {
      tester.view.physicalSize = const Size(1080, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);

      installMockApi((options) {
        if (options.path.contains('/orders/9')) {
          return Response(
            requestOptions: options,
            statusCode: 200,
            data: {
              'code': 200,
              'message': 'success',
              'data': orderJson(
                id: 9,
                orderStatus: 'REFUNDING',
                statusDesc: '退款处理中',
              ),
            },
          );
        }
        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {'code': 200, 'message': 'success', 'data': []},
        );
      });

      await tester.pumpWidget(
        GetMaterialApp(
          initialRoute: AppRoutes.home,
          getPages: AppPages.routes,
        ),
      );
      Get.toNamed(AppRoutes.orderDetail, arguments: 9);
      await tester.pumpAndSettle();

      // 1) 原文展示：服务端描述出现在状态芯片上
      expect(find.text('退款处理中'), findsOneWidget);
      // 2) 不能把未知状态伪装成已知状态
      expect(find.text('待卖家确认'), findsNothing);
      expect(find.text('待面交'), findsNothing);
      // 3) 只读：没有任何订单操作入口（含卖家侧的"确认接单"）
      expect(find.text('确认接单'), findsNothing);
      expect(find.text('取消订单'), findsNothing);
      expect(find.text('完成交易'), findsNothing);
      expect(find.text('去评价'), findsNothing);
      // 4) 明确告知用户当前状态不受支持，而不是渲染一条错误的流转时间线
      expect(find.textContaining('退款处理中'), findsWidgets);
      expect(find.text('订单流转进度'), findsOneWidget);
    });

    testWidgets('2. 已知状态（待卖家确认）仍保留原有操作入口',
        (WidgetTester tester) async {
      tester.view.physicalSize = const Size(1080, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);

      installMockApi((options) {
        if (options.path.contains('/orders/8')) {
          return Response(
            requestOptions: options,
            statusCode: 200,
            data: {
              'code': 200,
              'message': 'success',
              'data': orderJson(
                id: 8,
                orderStatus: 'WAIT_SELLER_CONFIRM',
                statusDesc: '待卖家确认',
              ),
            },
          );
        }
        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {'code': 200, 'message': 'success', 'data': []},
        );
      });

      await tester.pumpWidget(
        GetMaterialApp(
          initialRoute: AppRoutes.home,
          getPages: AppPages.routes,
        ),
      );
      Get.toNamed(AppRoutes.orderDetail, arguments: 8);
      await tester.pumpAndSettle();

      // 已知状态不受影响：买家侧仍可取消订单（回归保护，防止"只读化"改过头）
      expect(find.text('取消订单'), findsWidgets);
      expect(find.text('待卖家确认'), findsWidgets);
    });
  });

  // =========================================================================
  // 2. 商品状态枚举：已售出 != 已下架
  // =========================================================================

  group('Stage 7-D: 商品状态枚举', () {
    test('3. GoodsStatus 与后端取值域一致，未知取值回退原文', () {
      expect(GoodsStatus.fromCode('ON_SALE'), equals(GoodsStatus.onSale));
      expect(GoodsStatus.fromCode(' off_shelf '), equals(GoodsStatus.offShelf));
      expect(GoodsStatus.fromCode('SOLD'), equals(GoodsStatus.sold));
      expect(GoodsStatus.fromCode('LOCKED'), equals(GoodsStatus.locked));
      expect(GoodsStatus.fromCode('DRAFT'), equals(GoodsStatus.draft));
      expect(GoodsStatus.fromCode('WHATEVER'), isNull);
      expect(GoodsStatus.fromCode(null), isNull);

      expect(GoodsStatus.labelOf('SOLD'), equals('已售出'));
      expect(GoodsStatus.labelOf('OFF_SHELF'), equals('已下架'));
      expect(GoodsStatus.labelOf('ON_SALE'), equals('在售中'));
      expect(GoodsStatus.labelOf('WHATEVER'), equals('WHATEVER'),
          reason: '未知状态必须展示服务端原文，而不是被当成某个已知状态');

      expect(GoodsStatus.sold.isUnavailable, isTrue);
      expect(GoodsStatus.offShelf.isUnavailable, isTrue);
      expect(GoodsStatus.locked.isUnavailable, isTrue);
      expect(GoodsStatus.onSale.isUnavailable, isFalse);
      expect(GoodsStatus.onSale.isBuyable, isTrue);
      expect(GoodsStatus.sold.isBuyable, isFalse);
      expect(GoodsStatus.sold.isEditableBySeller, isFalse);
      expect(GoodsStatus.offShelf.isEditableBySeller, isTrue);
    });

    test('4. VerifyStatus 收敛认证状态取值', () {
      expect(VerifyStatus.fromCode('SUCCESS'), equals(VerifyStatus.success));
      expect(VerifyStatus.fromCode('pending'), equals(VerifyStatus.pending));
      expect(VerifyStatus.fromCode('NONE'), equals(VerifyStatus.none));
      expect(VerifyStatus.fromCode(null), equals(VerifyStatus.none));
      expect(VerifyStatus.fromCode('WHATEVER'), equals(VerifyStatus.none));
      expect(VerifyStatus.success.isVerified, isTrue);
      expect(VerifyStatus.pending.isVerified, isFalse);
      expect(VerifyStatus.pending.label, equals('审核中'));
    });

    testWidgets('5. 收藏列表：已售出与已下架是两个不同的徽标',
        (WidgetTester tester) async {
      tester.view.physicalSize = const Size(1080, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);

      installMockApi((options) {
        if (options.path.contains('/favorite/list')) {
          return Response(
            requestOptions: options,
            statusCode: 200,
            data: {
              'code': 200,
              'message': 'success',
              'data': {
                'records': [
                  {
                    'id': 1,
                    'goodsId': 201,
                    'title': '还没卖掉的教材',
                    'price': 30.00,
                    'conditionLevel': '95新',
                    'status': 'ON_SALE',
                    'schoolName': '清华大学',
                    'createdTime': '2026-09-17 12:00:00',
                  },
                  {
                    'id': 2,
                    'goodsId': 202,
                    'title': '已经卖出去的耳机',
                    'price': 120.00,
                    'conditionLevel': '8成新',
                    'status': 'SOLD',
                    'schoolName': '北京大学',
                    'createdTime': '2026-09-16 10:00:00',
                  },
                  {
                    'id': 3,
                    'goodsId': 203,
                    'title': '被卖家下架的台灯',
                    'price': 45.00,
                    'conditionLevel': '9成新',
                    'status': 'OFF_SHELF',
                    'schoolName': '复旦大学',
                    'createdTime': '2026-09-15 09:00:00',
                  },
                ],
                'total': 3,
                'size': 10,
                'current': 1,
                'pages': 1,
              },
            },
          );
        }
        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {'code': 200, 'message': 'success', 'data': []},
        );
      });

      await tester.pumpWidget(
        GetMaterialApp(
          initialRoute: AppRoutes.favorite,
          getPages: AppPages.routes,
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('已经卖出去的耳机'), findsOneWidget);
      // 核心断言：已售出显示「已售出」，绝不能被混成「已下架」
      expect(find.text('已售出'), findsOneWidget);
      expect(find.text('已下架'), findsOneWidget);
      expect(find.text('在售中'), findsNothing);
    });

    testWidgets('6. 浏览足迹：已售出同样不被显示成已下架',
        (WidgetTester tester) async {
      tester.view.physicalSize = const Size(1080, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);

      installMockApi((options) {
        if (options.path.contains('/history/list')) {
          return Response(
            requestOptions: options,
            statusCode: 200,
            data: {
              'code': 200,
              'message': 'success',
              'data': {
                'records': [
                  {
                    'id': 1,
                    'goodsId': 301,
                    'title': '已经卖出去的考研政治',
                    'price': 18.00,
                    'conditionLevel': '全新',
                    'status': 'SOLD',
                    'browseTime': '2026-09-17 15:30:00',
                  },
                  {
                    'id': 2,
                    'goodsId': 302,
                    'title': '已被下架的英语真题',
                    'price': 22.00,
                    'conditionLevel': '95新',
                    'status': 'OFF_SHELF',
                    'browseTime': '2026-09-16 15:30:00',
                  },
                ],
                'total': 2,
                'size': 20,
                'current': 1,
                'pages': 1,
              },
            },
          );
        }
        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {'code': 200, 'message': 'success', 'data': []},
        );
      });

      await tester.pumpWidget(
        GetMaterialApp(
          initialRoute: AppRoutes.history,
          getPages: AppPages.routes,
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('已售出'), findsOneWidget);
      expect(find.text('已下架'), findsOneWidget);
    });
  });

  // =========================================================================
  // 3. 业务错误文案：4xx 走 DioException，但提示必须仍是服务端业务文案
  // =========================================================================

  group('Stage 7-D: 业务错误提示文案', () {
    late Dio dio;
    late OrderApi orderApi;

    setUp(() {
      dio = Dio(BaseOptions(baseUrl: 'http://127.0.0.1:8081/api'));
      orderApi = OrderApi(dio: dio);
    });

    test('7. 403 业务错误（订单非当事人）→ 取服务端 message，不丢文案', () async {
      dio.interceptors.add(InterceptorsWrapper(
        onRequest: (options, handler) => handler.reject(DioException(
          requestOptions: options,
          type: DioExceptionType.badResponse,
          response: Response(
            requestOptions: options,
            statusCode: 403,
            data: {
              'code': 403,
              'message': '无权查看该订单详情',
              'data': null,
              'timestamp': 1758100000000,
            },
          ),
        )),
      ));

      final response = await orderApi.getOrderDetail('9');
      expect(response.code, equals(403));
      expect(response.message, equals('无权查看该订单详情'));
      expect(response.data, isNull);
    });

    test('8. 404 / 409 / 422 业务错误同样保留服务端提示', () async {
      final cases = <int, String>{
        404: '订单不存在',
        409: '商品状态在本次变更上下架状态期间已被并发变更（当前状态: SOLD），请刷新后重试',
        422: '订单已完成超过7天，评价通道已关闭',
      };

      for (final entry in cases.entries) {
        final localDio = Dio(BaseOptions(baseUrl: 'http://127.0.0.1:8081/api'));
        localDio.interceptors.add(InterceptorsWrapper(
          onRequest: (options, handler) => handler.reject(DioException(
            requestOptions: options,
            type: DioExceptionType.badResponse,
            response: Response(
              requestOptions: options,
              statusCode: entry.key,
              data: {
                'code': entry.key,
                'message': entry.value,
                'data': null,
                'timestamp': 1758100000000,
              },
            ),
          )),
        ));

        final response = await OrderApi(dio: localDio).confirmOrder('9');
        expect(response.code, equals(entry.key));
        expect(response.message, equals(entry.value),
            reason: 'HTTP ${entry.key} 的业务错误必须原样保留服务端文案');
      }
    });

    test('9. describeApiError 优先服务端 message，其次才是网络兜底文案', () {
      final requestOptions = RequestOptions(path: '/orders/9');

      final business = DioException(
        requestOptions: requestOptions,
        type: DioExceptionType.badResponse,
        response: Response(
          requestOptions: requestOptions,
          statusCode: 403,
          data: {'code': 403, 'message': '只有卖家可以确认订单'},
        ),
      );
      expect(describeApiError(business), equals('只有卖家可以确认订单'));

      final noBody = DioException(
        requestOptions: requestOptions,
        type: DioExceptionType.badResponse,
        response: Response(requestOptions: requestOptions, statusCode: 500),
      );
      expect(describeApiError(noBody, fallback: '商品状态修改失败'),
          equals('商品状态修改失败（HTTP 500）'));

      final timeout = DioException(
        requestOptions: requestOptions,
        type: DioExceptionType.receiveTimeout,
      );
      expect(describeApiError(timeout), equals('网络请求超时，请检查网络后重试'));
      expect(
        describeApiError(timeout, timeoutMessage: 'AI 请求超时，请检查网络后重试'),
        equals('AI 请求超时，请检查网络后重试'),
      );

      // 服务层显式抛出的业务提示（非网络异常）仍可直接展示
      expect(describeApiError(Exception('商品处于交易中或已售出，禁止修改或删除')),
          equals('商品处于交易中或已售出，禁止修改或删除'));
    });
  });
}
