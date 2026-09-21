import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:frontend/api/order_api.dart';
import 'package:frontend/controllers/order_controller.dart';
import 'package:frontend/models/order.dart';
import 'package:get/get.dart' hide Response;

void main() {
  late Dio dio;
  late OrderApi orderApi;
  late OrderController orderController;
  Interceptor? mockInterceptor;

  void installMock(Response Function(RequestOptions options) responder) {
    if (mockInterceptor != null) {
      dio.interceptors.remove(mockInterceptor);
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
    dio.interceptors.insert(0, mockInterceptor!);
  }

  setUp(() {
    dio = Dio(BaseOptions(baseUrl: 'http://127.0.0.1:8081/api'));
    orderApi = OrderApi(dio: dio);
    orderController = OrderController(orderApi: orderApi);
  });

  tearDown(() {
    if (mockInterceptor != null) {
      dio.interceptors.remove(mockInterceptor);
      mockInterceptor = null;
    }
  });

  final sampleOrderMap = {
    'id': 100,
    'orderNo': 'ORD20260917001',
    'goodsId': 50,
    'goodsTitleSnapshot': '考研英语词汇闪过',
    'goodsPriceSnapshot': 18.0,
    'goodsImageSnapshot': 'https://example.com/book.jpg',
    'meetLocation': '二食堂门口',
    'buyerMessage': '请带上配套光盘',
    'buyerId': 1,
    'sellerId': 2,
    'buyer': {
      'id': 1,
      'username': 'buyer_user',
      'nickname': '买家小明',
    },
    'seller': {
      'id': 2,
      'username': 'seller_user',
      'nickname': '卖家小红',
    },
    'orderStatus': 'WAIT_SELLER_CONFIRM',
    'statusDesc': '待卖家确认',
    'createdTime': '2026-09-17 14:00:00',
  };

  group('OrderApi Endpoint Tests', () {
    test('createOrder (POST /orders) sends correct parameters and parses result',
        () async {
      installMock((options) {
        expect(options.path, equals('/orders'));
        expect(options.method, equals('POST'));
        final data = options.data as Map<String, dynamic>;
        expect(data['goodsId'], equals('50'));
        expect(data['meetLocation'], equals('二食堂门口'));
        expect(data['buyerMessage'], equals('请带上配套光盘'));

        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {
            'code': 200,
            'message': '订单创建成功',
            'data': sampleOrderMap,
          },
        );
      });

      final response = await orderApi.createOrder(
        goodsId: '50',
        meetLocation: '二食堂门口',
        buyerMessage: '请带上配套光盘',
      );

      expect(response.isSuccess, isTrue);
      expect(response.code, equals(200));
      expect(response.data, isNotNull);
      expect(response.data!.orderNo, equals('ORD20260917001'));
      expect(response.data!.orderStatus, equals(OrderStatus.waitSellerConfirm));
    });

    test('getMyOrders (GET /orders/my) fetches page with records', () async {
      installMock((options) {
        expect(options.path, equals('/orders/my'));
        expect(options.method, equals('GET'));
        expect(options.queryParameters['role'], equals('BUYER'));
        expect(options.queryParameters['page'], equals(1));
        expect(options.queryParameters['size'], equals(10));

        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {
            'code': 200,
            'message': '获取订单列表成功',
            'data': {
              'records': [sampleOrderMap],
              'total': 1,
              'current': 1,
              'size': 10,
              'pages': 1,
            },
          },
        );
      });

      final response = await orderApi.getMyOrders(role: 'BUYER', page: 1, size: 10);

      expect(response.isSuccess, isTrue);
      expect(response.data, isNotNull);
      expect(response.data!.total, equals(1));
      expect(response.data!.records.length, equals(1));
      expect(response.data!.records.first.id, equals('100'));
    });

    test('getOrderDetail (GET /orders/{id}) returns specific order', () async {
      installMock((options) {
        expect(options.path, equals('/orders/100'));
        expect(options.method, equals('GET'));

        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {
            'code': 200,
            'message': '获取订单详情成功',
            'data': sampleOrderMap,
          },
        );
      });

      final response = await orderApi.getOrderDetail('100');

      expect(response.isSuccess, isTrue);
      expect(response.data?.id, equals('100'));
      expect(response.data?.goodsTitleSnapshot, equals('考研英语词汇闪过'));
    });

    test('confirmOrder (PUT /orders/{id}/confirm) updates to WAIT_MEET', () async {
      final confirmedMap = Map<String, dynamic>.from(sampleOrderMap);
      confirmedMap['orderStatus'] = 'WAIT_MEET';
      confirmedMap['statusDesc'] = '待面交';
      confirmedMap['confirmedTime'] = '2026-09-17 14:10:00';

      installMock((options) {
        expect(options.path, equals('/orders/100/confirm'));
        expect(options.method, equals('PUT'));

        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {
            'code': 200,
            'message': '卖家确认接单成功',
            'data': confirmedMap,
          },
        );
      });

      final response = await orderApi.confirmOrder('100');

      expect(response.isSuccess, isTrue);
      expect(response.data?.orderStatus, equals(OrderStatus.waitMeet));
      expect(response.data?.statusDescription, equals('待面交'));
      expect(response.data?.confirmedTime, equals('2026-09-17 14:10:00'));
    });

    test('cancelOrder (PUT /orders/{id}/cancel) transmits cancelReason and updates to CANCELLED',
        () async {
      final cancelledMap = Map<String, dynamic>.from(sampleOrderMap);
      cancelledMap['orderStatus'] = 'CANCELLED';
      cancelledMap['statusDesc'] = '已取消';
      cancelledMap['cancelReason'] = '面交时间冲突无法约定';
      cancelledMap['cancelledTime'] = '2026-09-17 14:20:00';

      installMock((options) {
        expect(options.path, equals('/orders/100/cancel'));
        expect(options.method, equals('PUT'));
        final data = options.data as Map<String, dynamic>;
        expect(data['cancelReason'], equals('面交时间冲突无法约定'));

        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {
            'code': 200,
            'message': '订单取消成功',
            'data': cancelledMap,
          },
        );
      });

      final response = await orderApi.cancelOrder(
        id: '100',
        cancelReason: '面交时间冲突无法约定',
      );

      expect(response.isSuccess, isTrue);
      expect(response.data?.orderStatus, equals(OrderStatus.cancelled));
      expect(response.data?.cancelReason, equals('面交时间冲突无法约定'));
    });

    test('completeOrder (PUT /orders/{id}/complete) updates to COMPLETED', () async {
      final completedMap = Map<String, dynamic>.from(sampleOrderMap);
      completedMap['orderStatus'] = 'COMPLETED';
      completedMap['statusDesc'] = '已完成';
      completedMap['completedTime'] = '2026-09-17 15:00:00';

      installMock((options) {
        expect(options.path, equals('/orders/100/complete'));
        expect(options.method, equals('PUT'));

        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {
            'code': 200,
            'message': '交易完成',
            'data': completedMap,
          },
        );
      });

      final response = await orderApi.completeOrder('100');

      expect(response.isSuccess, isTrue);
      expect(response.data?.orderStatus, equals(OrderStatus.completed));
      expect(response.data?.statusDescription, equals('已完成'));
    });

    test('OrderApi handles 400 Bad Request error gracefully', () async {
      installMock((options) {
        throw DioException(
          requestOptions: options,
          response: Response(
            requestOptions: options,
            statusCode: 400,
            data: {
              'code': 400,
              'message': '商品已被锁定或已下架',
            },
          ),
        );
      });

      final response = await orderApi.createOrder(goodsId: '999');

      expect(response.isSuccess, isFalse);
      expect(response.code, equals(400));
      expect(response.message, equals('商品已被锁定或已下架'));
      expect(response.data, isNull);
    });

    test('OrderApi handles 401 and 403 authorization failures gracefully',
        () async {
      installMock((options) {
        throw DioException(
          requestOptions: options,
          response: Response(
            requestOptions: options,
            statusCode: 403,
            data: {
              'code': 403,
              'message': '非订单当事人无权访问',
            },
          ),
        );
      });

      final response = await orderApi.getOrderDetail('888');

      expect(response.isSuccess, isFalse);
      expect(response.code, equals(403));
      expect(response.message, equals('非订单当事人无权访问'));
    });
  });

  group('OrderController State Management Tests', () {
    test('initial state has clean default values', () {
      expect(orderController.orders, isEmpty);
      expect(orderController.currentOrder.value, isNull);
      expect(orderController.loading.value, isFalse);
      expect(orderController.errorMessage.value, isEmpty);
      expect(orderController.hasError, isFalse);
      expect(orderController.currentRole.value, equals('BUYER'));
    });

    test('fetchMyOrders updates orders list and resets error', () async {
      installMock((options) {
        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {
            'code': 200,
            'message': 'success',
            'data': {
              'records': [sampleOrderMap],
              'total': 1,
              'current': 1,
              'size': 10,
              'pages': 1,
            },
          },
        );
      });

      await orderController.fetchMyOrders();

      expect(orderController.loading.value, isFalse);
      expect(orderController.orders.length, equals(1));
      expect(orderController.orders.first.orderNo, equals('ORD20260917001'));
      expect(orderController.hasMore.value, isFalse);
      expect(orderController.errorMessage.value, isEmpty);
    });

    test('fetchMyOrders handles failure and populates errorMessage', () async {
      installMock((options) {
        throw DioException(
          requestOptions: options,
          response: Response(
            requestOptions: options,
            statusCode: 500,
            data: {
              'code': 500,
              'message': '数据库连接超时',
            },
          ),
        );
      });

      await orderController.fetchMyOrders();

      expect(orderController.loading.value, isFalse);
      expect(orderController.orders, isEmpty);
      expect(orderController.hasError, isTrue);
      expect(orderController.errorMessage.value, equals('数据库连接超时'));
    });

    test('fetchOrderDetail updates currentOrder and syncs with orders list',
        () async {
      // 预置列表项
      orderController.orders.assignAll([OrderVO.fromJson(sampleOrderMap)]);

      final updatedDetail = Map<String, dynamic>.from(sampleOrderMap);
      updatedDetail['sellerReply'] = '已备好，随时可取';

      installMock((options) {
        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {
            'code': 200,
            'message': 'success',
            'data': updatedDetail,
          },
        );
      });

      final result = await orderController.fetchOrderDetail('100');

      expect(result, isNotNull);
      expect(orderController.currentOrder.value?.sellerReply,
          equals('已备好，随时可取'));
      expect(orderController.orders.first.sellerReply, equals('已备好，随时可取'));
    });

    test('createOrder inserts new order into list and sets currentOrder',
        () async {
      installMock((options) {
        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {
            'code': 200,
            'message': '创建成功',
            'data': sampleOrderMap,
          },
        );
      });

      final newOrder = await orderController.createOrder(
        goodsId: '50',
        meetLocation: '二食堂门口',
      );

      expect(newOrder, isNotNull);
      expect(orderController.currentOrder.value?.id, equals('100'));
      expect(orderController.orders.length, equals(1));
      expect(orderController.orders.first.id, equals('100'));
    });

    test('confirmOrder updates currentOrder and in-list item status to WAIT_MEET',
        () async {
      orderController.orders.assignAll([OrderVO.fromJson(sampleOrderMap)]);
      orderController.currentOrder.value = OrderVO.fromJson(sampleOrderMap);

      final confirmed = Map<String, dynamic>.from(sampleOrderMap);
      confirmed['orderStatus'] = 'WAIT_MEET';
      confirmed['statusDesc'] = '待面交';

      installMock((options) {
        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {
            'code': 200,
            'message': '卖家接单成功',
            'data': confirmed,
          },
        );
      });

      final success = await orderController.confirmOrder('100');

      expect(success, isTrue);
      expect(orderController.currentOrder.value?.orderStatus,
          equals(OrderStatus.waitMeet));
      expect(orderController.orders.first.orderStatus,
          equals(OrderStatus.waitMeet));
    });

    test('cancelOrder updates currentOrder and in-list item status to CANCELLED',
        () async {
      orderController.orders.assignAll([OrderVO.fromJson(sampleOrderMap)]);
      orderController.currentOrder.value = OrderVO.fromJson(sampleOrderMap);

      final cancelled = Map<String, dynamic>.from(sampleOrderMap);
      cancelled['orderStatus'] = 'CANCELLED';
      cancelled['statusDesc'] = '已取消';
      cancelled['cancelReason'] = '买家改变主意';

      installMock((options) {
        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {
            'code': 200,
            'message': '取消成功',
            'data': cancelled,
          },
        );
      });

      final success = await orderController.cancelOrder('100', '买家改变主意');

      expect(success, isTrue);
      expect(orderController.currentOrder.value?.orderStatus,
          equals(OrderStatus.cancelled));
      expect(orderController.currentOrder.value?.cancelReason,
          equals('买家改变主意'));
      expect(orderController.orders.first.orderStatus,
          equals(OrderStatus.cancelled));
    });

    test('completeOrder updates currentOrder and in-list item status to COMPLETED',
        () async {
      orderController.orders.assignAll([OrderVO.fromJson(sampleOrderMap)]);
      orderController.currentOrder.value = OrderVO.fromJson(sampleOrderMap);

      final completed = Map<String, dynamic>.from(sampleOrderMap);
      completed['orderStatus'] = 'COMPLETED';
      completed['statusDesc'] = '已完成';

      installMock((options) {
        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {
            'code': 200,
            'message': '交易完成',
            'data': completed,
          },
        );
      });

      final success = await orderController.completeOrder('100');

      expect(success, isTrue);
      expect(orderController.currentOrder.value?.orderStatus,
          equals(OrderStatus.completed));
      expect(orderController.orders.first.orderStatus,
          equals(OrderStatus.completed));
    });

    test('role switching and status filtering trigger list reload', () async {
      String? lastRoleRequested;
      String? lastStatusRequested;

      installMock((options) {
        lastRoleRequested = options.queryParameters['role']?.toString();
        lastStatusRequested = options.queryParameters['status']?.toString();
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

      await orderController.switchRole('SELLER');
      expect(orderController.currentRole.value, equals('SELLER'));
      expect(lastRoleRequested, equals('SELLER'));

      await orderController.filterByStatus(OrderStatus.waitMeet);
      expect(orderController.currentStatusFilter.value,
          equals(OrderStatus.waitMeet));
      expect(lastStatusRequested, equals('WAIT_MEET'));
    });
  });
}
