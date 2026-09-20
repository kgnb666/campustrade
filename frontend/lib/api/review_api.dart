import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import '../models/api_response.dart';
import '../models/review.dart';
import 'dio_client.dart';

/// 评价领域核心网络 API 接口服务
/// 封装与后端 /api/reviews 相关的所有 REST 交互
class ReviewApi {
  final Dio _dio;

  ReviewApi({Dio? dio}) : _dio = dio ?? DioClient().dio;

  /// 接口1: 创建交易评价
  /// POST /api/reviews
  Future<ApiResponse<ReviewModel>> createReview(
      CreateReviewRequest request) async {
    try {
      final response = await _dio.post(
        '/reviews',
        data: request.toJson(),
      );
      return _parseApiResponse<ReviewModel>(
        response,
        (data) => ReviewModel.fromJson(data as Map<String, dynamic>),
      );
    } on DioException catch (e) {
      return _handleDioException<ReviewModel>(e);
    } catch (e) {
      debugPrint('[ReviewApi] createReview error: $e');
      return ApiResponse<ReviewModel>(
        code: 500,
        message: '创建评价失败，请稍后重试',
        data: null,
      );
    }
  }

  /// 接口2: 查看指定用户收到的评价
  /// GET /api/reviews/user/{userId}
  Future<ApiResponse<ReviewPageResult>> getReviewsByUser(
    String userId, {
    int page = 1,
    int size = 10,
  }) async {
    try {
      final response = await _dio.get(
        '/reviews/user/$userId',
        queryParameters: {
          'page': page,
          'size': size,
        },
      );
      return _parseApiResponse<ReviewPageResult>(
        response,
        (data) => ReviewPageResult.fromJson(data as Map<String, dynamic>),
      );
    } on DioException catch (e) {
      return _handleDioException<ReviewPageResult>(e);
    } catch (e) {
      debugPrint('[ReviewApi] getReviewsByUser error: $e');
      return ApiResponse<ReviewPageResult>(
        code: 500,
        message: '用户评价加载失败，请稍后重试',
        data: null,
      );
    }
  }

  /// 接口3: 查看指定商品收到的评价
  /// GET /api/reviews/goods/{goodsId}
  Future<ApiResponse<ReviewPageResult>> getReviewsByGoods(
    String goodsId, {
    int page = 1,
    int size = 10,
  }) async {
    try {
      final response = await _dio.get(
        '/reviews/goods/$goodsId',
        queryParameters: {
          'page': page,
          'size': size,
        },
      );
      return _parseApiResponse<ReviewPageResult>(
        response,
        (data) => ReviewPageResult.fromJson(data as Map<String, dynamic>),
      );
    } on DioException catch (e) {
      return _handleDioException<ReviewPageResult>(e);
    } catch (e) {
      debugPrint('[ReviewApi] getReviewsByGoods error: $e');
      return ApiResponse<ReviewPageResult>(
        code: 500,
        message: '商品评价加载失败，请稍后重试',
        data: null,
      );
    }
  }

  /// 接口4: 查询订单的双向评价状态
  /// GET /api/reviews/order/{orderId}
  Future<ApiResponse<OrderReviewStatusModel>> getOrderReviewStatus(
      String orderId) async {
    try {
      final response = await _dio.get('/reviews/order/$orderId');
      return _parseApiResponse<OrderReviewStatusModel>(
        response,
        (data) =>
            OrderReviewStatusModel.fromJson(data as Map<String, dynamic>),
      );
    } on DioException catch (e) {
      return _handleDioException<OrderReviewStatusModel>(e);
    } catch (e) {
      debugPrint('[ReviewApi] getOrderReviewStatus error: $e');
      return ApiResponse<OrderReviewStatusModel>(
        code: 500,
        message: '评价状态加载失败，请稍后重试',
        data: null,
      );
    }
  }

  /// 通用 ApiResponse 反序列化解析器
  ApiResponse<T> _parseApiResponse<T>(
    Response response,
    T Function(dynamic data) dataParser,
  ) {
    final raw = response.data;
    if (raw is Map<String, dynamic>) {
      final code = raw['code'] is int
          ? raw['code'] as int
          : (int.tryParse(raw['code']?.toString() ?? '200') ?? 200);
      final message = raw['message']?.toString() ?? 'success';
      final dataField = raw['data'];

      T? parsedData;
      if (dataField != null && (code == 200 || code == 0)) {
        try {
          parsedData = dataParser(dataField);
        } catch (e, stack) {
          // 解析失败不能静默：否则 code 仍是 200 而 data 为 null，
          // 上层会把服务端的 'success' 当错误文案展示，问题被藏起来。
          debugPrint('[_parseApiResponse] dataParser failed: $e\n$stack');
          return ApiResponse<T>(
            code: 500,
            message: '数据解析失败，请稍后重试',
            data: null,
            timestamp: raw['timestamp'] is int
                ? raw['timestamp'] as int
                : int.tryParse(raw['timestamp']?.toString() ?? '0'),
          );
        }
      }

      return ApiResponse<T>(
        code: code,
        message: message,
        data: parsedData,
        timestamp: raw['timestamp'] is int
            ? raw['timestamp'] as int
            : int.tryParse(raw['timestamp']?.toString() ?? '0'),
      );
    }

    return ApiResponse<T>(
      code: response.statusCode ?? 200,
      message: 'OK',
      data: null,
    );
  }

  /// 统一 Dio 异常处理器，按 Stage 5-C / 5-E 规范精准映射状态码与业务提示
  ApiResponse<T> _handleDioException<T>(DioException e) {
    if (e.response != null) {
      final status = e.response!.statusCode ?? 500;
      final data = e.response!.data;

      String serverMessage = '';
      int bizCode = status;
      if (data is Map<String, dynamic>) {
        serverMessage = data['message']?.toString() ?? '';
        if (data['code'] is int) {
          bizCode = data['code'] as int;
        }
      }

      String mappedMessage;
      switch (status) {
        case 400:
          mappedMessage = serverMessage.isNotEmpty
              ? serverMessage
              : '评价请求参数有误，请检查输入';
          break;
        case 401:
          mappedMessage = '登录态已过期，请重新登录';
          break;
        case 403:
          mappedMessage = '你没有权限评价该订单';
          break;
        case 404:
          mappedMessage = serverMessage.isNotEmpty ? serverMessage : '订单或资源不存在';
          break;
        case 409:
          mappedMessage = '你已经评价过该订单';
          break;
        case 422:
          mappedMessage = '订单完成后才能评价';
          break;
        default:
          mappedMessage = serverMessage.isNotEmpty
              ? serverMessage
              : '服务器开小差了 ($status)';
      }

      return ApiResponse<T>(
        code: bizCode,
        message: mappedMessage,
        data: null,
      );
    }

    // 网络连接或超时异常
    String netMsg = '网络连接失败，请稍后重试';
    if (e.type == DioExceptionType.connectionTimeout ||
        e.type == DioExceptionType.receiveTimeout ||
        e.type == DioExceptionType.sendTimeout) {
      netMsg = '网络请求超时，请检查网络后重试';
    }

    return ApiResponse<T>(
      code: 0,
      message: netMsg,
      data: null,
    );
  }
}
