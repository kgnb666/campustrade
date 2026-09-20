import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import '../models/api_response.dart';
import '../models/order.dart';
import 'dio_client.dart';

/// 交易订单核心网络 API 接口服务
/// 封装与后端 /api/orders 相关的所有 REST 交互
class OrderApi {
  final Dio _dio;

  OrderApi({Dio? dio}) : _dio = dio ?? DioClient().dio;

  /// 接口1: 创建订单
  /// POST /api/orders
  Future<ApiResponse<OrderVO>> createOrder({
    required String goodsId,
    String? meetLocation,
    String? buyerMessage,
  }) async {
    try {
      final request = CreateOrderRequest(
        goodsId: goodsId,
        meetLocation: meetLocation,
        buyerMessage: buyerMessage,
      );
      final response = await _dio.post('/orders', data: request.toJson());
      return _parseApiResponse<OrderVO>(
        response,
        (data) => OrderVO.fromJson(data as Map<String, dynamic>),
      );
    } on DioException catch (e) {
      return _handleDioException<OrderVO>(e);
    } catch (e) {
      debugPrint('[OrderApi] createOrder unexpected error: $e');
      return ApiResponse<OrderVO>(
        code: 500,
        message: '创建订单异常: $e',
        data: null,
      );
    }
  }

  /// 接口2: 分页查询当前用户的订单 (我的订单)
  /// GET /api/orders/my
  /// query: role (BUYER/SELLER), status, page, size
  Future<ApiResponse<OrderPageResult>> getMyOrders({
    String role = 'BUYER',
    String? status,
    int page = 1,
    int size = 10,
  }) async {
    try {
      final Map<String, dynamic> queryParams = {
        'role': role,
        'page': page,
        'size': size,
      };
      if (status != null && status.isNotEmpty) {
        queryParams['status'] = status;
      }
      final response =
          await _dio.get('/orders/my', queryParameters: queryParams);
      return _parseApiResponse<OrderPageResult>(
        response,
        (data) => OrderPageResult.fromJson(data as Map<String, dynamic>),
      );
    } on DioException catch (e) {
      return _handleDioException<OrderPageResult>(e);
    } catch (e) {
      debugPrint('[OrderApi] getMyOrders unexpected error: $e');
      return ApiResponse<OrderPageResult>(
        code: 500,
        message: '获取订单列表异常: $e',
        data: null,
      );
    }
  }

  /// 接口3: 查询订单详情
  /// GET /api/orders/{id}
  Future<ApiResponse<OrderVO>> getOrderDetail(String id) async {
    try {
      final response = await _dio.get('/orders/$id');
      return _parseApiResponse<OrderVO>(
        response,
        (data) => OrderVO.fromJson(data as Map<String, dynamic>),
      );
    } on DioException catch (e) {
      return _handleDioException<OrderVO>(e);
    } catch (e) {
      debugPrint('[OrderApi] getOrderDetail unexpected error: $e');
      return ApiResponse<OrderVO>(
        code: 500,
        message: '获取订单详情异常: $e',
        data: null,
      );
    }
  }

  /// 接口4: 卖家确认接单
  /// PUT /api/orders/{id}/confirm
  Future<ApiResponse<OrderVO>> confirmOrder(String id) async {
    try {
      final response = await _dio.put('/orders/$id/confirm');
      return _parseApiResponse<OrderVO>(
        response,
        (data) => OrderVO.fromJson(data as Map<String, dynamic>),
      );
    } on DioException catch (e) {
      return _handleDioException<OrderVO>(e);
    } catch (e) {
      debugPrint('[OrderApi] confirmOrder unexpected error: $e');
      return ApiResponse<OrderVO>(
        code: 500,
        message: '卖家确认订单异常: $e',
        data: null,
      );
    }
  }

  /// 接口5: 取消订单
  /// PUT /api/orders/{id}/cancel
  Future<ApiResponse<OrderVO>> cancelOrder({
    required String id,
    required String cancelReason,
  }) async {
    try {
      final request = CancelOrderRequest(cancelReason: cancelReason);
      final response =
          await _dio.put('/orders/$id/cancel', data: request.toJson());
      return _parseApiResponse<OrderVO>(
        response,
        (data) => OrderVO.fromJson(data as Map<String, dynamic>),
      );
    } on DioException catch (e) {
      return _handleDioException<OrderVO>(e);
    } catch (e) {
      debugPrint('[OrderApi] cancelOrder unexpected error: $e');
      return ApiResponse<OrderVO>(
        code: 500,
        message: '取消订单异常: $e',
        data: null,
      );
    }
  }

  /// 接口6: 完成交易
  /// PUT /api/orders/{id}/complete
  Future<ApiResponse<OrderVO>> completeOrder(String id) async {
    try {
      final response = await _dio.put('/orders/$id/complete');
      return _parseApiResponse<OrderVO>(
        response,
        (data) => OrderVO.fromJson(data as Map<String, dynamic>),
      );
    } on DioException catch (e) {
      return _handleDioException<OrderVO>(e);
    } catch (e) {
      debugPrint('[OrderApi] completeOrder unexpected error: $e');
      return ApiResponse<OrderVO>(
        code: 500,
        message: '完成交易异常: $e',
        data: null,
      );
    }
  }

  /// 通用 ApiResponse 反序列化封装
  ApiResponse<T> _parseApiResponse<T>(
    Response response,
    T Function(dynamic data) parser,
  ) {
    if (response.data is Map<String, dynamic>) {
      final map = response.data as Map<String, dynamic>;
      final code = map['code'] is int
          ? map['code'] as int
          : int.tryParse(map['code']?.toString() ?? '200') ?? 200;
      final message = map['message']?.toString() ?? 'success';
      final rawData = map['data'];
      final timestamp =
          map['timestamp'] is int ? map['timestamp'] as int : null;

      T? parsedData;
      if (rawData != null && code == 200) {
        try {
          parsedData = parser(rawData);
        } catch (e) {
          debugPrint('[OrderApi] parser mapping error: $e');
        }
      }

      return ApiResponse<T>(
        code: code,
        message: message,
        data: parsedData,
        timestamp: timestamp,
      );
    }

    return ApiResponse<T>(
      code: response.statusCode ?? 200,
      message: 'OK',
      data: null,
    );
  }

  /// Dio 异常统一转换为 ApiResponse
  ApiResponse<T> _handleDioException<T>(DioException e) {
    if (e.response != null && e.response!.data is Map<String, dynamic>) {
      final data = e.response!.data as Map<String, dynamic>;
      final code = data['code'] is int
          ? data['code'] as int
          : e.response!.statusCode ?? 500;
      final message = data['message']?.toString() ?? e.message ?? '网络请求失败';
      return ApiResponse<T>(
        code: code,
        message: message,
        data: null,
      );
    }
    return ApiResponse<T>(
      code: e.response?.statusCode ?? 500,
      message: e.message ?? '网络连接失败',
      data: null,
    );
  }
}
