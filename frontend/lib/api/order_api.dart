import 'package:dio/dio.dart';
import '../models/api_response.dart';
import '../utils/api_error.dart';
import '../models/order.dart';
import '../utils/app_logger.dart';
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
      AppLogger.error('[OrderApi] createOrder unexpected error', error: e);
      return ApiResponse<OrderVO>(
        code: 500,
        message: '创建订单失败，请稍后重试',
        data: null,
      );
    }
  }

  /// 接口2: 分页查询当前用户的订单 (我的订单)
  /// GET /api/orders/my
  /// query: role (BUYER/SELLER), status, page, size
  /// [cancelToken] 由调用方（控制器）持有：刷新/切换筛选/离开页面时会取消在途请求，
  /// 避免快速连点时同时挂着多个真实请求（只丢弃迟到响应并不节省一次往返）。
  Future<ApiResponse<OrderPageResult>> getMyOrders({
    String role = 'BUYER',
    String? status,
    int page = 1,
    int size = 10,
    CancelToken? cancelToken,
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
      final response = await _dio.get('/orders/my',
          queryParameters: queryParams, cancelToken: cancelToken);
      return _parseApiResponse<OrderPageResult>(
        response,
        (data) => OrderPageResult.fromJson(data as Map<String, dynamic>),
      );
    } on DioException catch (e) {
      return _handleDioException<OrderPageResult>(e);
    } catch (e) {
      AppLogger.error('[OrderApi] getMyOrders unexpected error', error: e);
      return ApiResponse<OrderPageResult>(
        code: 500,
        message: '订单列表加载失败，请稍后重试',
        data: null,
      );
    }
  }

  /// 接口3: 查询订单详情
  /// GET /api/orders/{id}
  /// [cancelToken] 见 [getMyOrders] 的说明。
  Future<ApiResponse<OrderVO>> getOrderDetail(String id, {CancelToken? cancelToken}) async {
    try {
      final response = await _dio.get('/orders/$id', cancelToken: cancelToken);
      return _parseApiResponse<OrderVO>(
        response,
        (data) => OrderVO.fromJson(data as Map<String, dynamic>),
      );
    } on DioException catch (e) {
      return _handleDioException<OrderVO>(e);
    } catch (e) {
      AppLogger.error('[OrderApi] getOrderDetail unexpected error', error: e);
      return ApiResponse<OrderVO>(
        code: 500,
        message: '订单详情加载失败，请稍后重试',
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
      AppLogger.error('[OrderApi] confirmOrder unexpected error', error: e);
      return ApiResponse<OrderVO>(
        code: 500,
        message: '确认接单失败，请稍后重试',
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
      AppLogger.error('[OrderApi] cancelOrder unexpected error', error: e);
      return ApiResponse<OrderVO>(
        code: 500,
        message: '取消订单失败，请稍后重试',
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
      AppLogger.error('[OrderApi] completeOrder unexpected error', error: e);
      return ApiResponse<OrderVO>(
        code: 500,
        message: '完成交易失败，请稍后重试',
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
        } catch (e, stack) {
          // 解析失败不能静默：否则 code 仍是 200、data 为 null，上层会拿服务端的
          // 'success' 当错误文案展示，用户看到"success"却不知道哪里出错。
          AppLogger.error('[OrderApi] parser mapping error', error: e, stackTrace: stack);
          return ApiResponse<T>(
            code: 500,
            message: '数据解析失败，请稍后重试',
            data: null,
            timestamp: timestamp,
          );
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
      final message = data['message']?.toString().trim() ?? '';
      return ApiResponse<T>(
        code: code,
        // 服务端没给 message 时退回统一映射，避免把 Dio 的英文原文弹给用户
        message: message.isEmpty
            ? describeApiError(e, fallback: '请求失败，请稍后重试')
            : message,
        data: null,
      );
    }
    return ApiResponse<T>(
      code: e.response?.statusCode ?? 500,
      message: describeApiError(e, fallback: '网络连接失败，请检查网络后重试'),
      data: null,
    );
  }
}
