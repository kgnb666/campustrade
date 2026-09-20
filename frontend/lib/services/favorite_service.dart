import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import '../api/dio_client.dart';
import '../models/favorite_model.dart';
import '../utils/api_error.dart';
import '../utils/json_cast.dart';

/// 收藏网络服务
///
/// 错误处理约定与 [GoodsService] 一致：失败一律抛 [ApiException]（message 为中文文案），
/// 不再把失败折叠成"空列表 / false"，避免断网与"确实没有数据"在 UI 上同形。
class FavoriteService {
  final Dio _dio = DioClient().dio;

  String _serverMessage(Response<dynamic> response, String fallback) {
    final dynamic data = response.data;
    if (data is Map) {
      final String message = (data['message'] ?? '').toString().trim();
      if (message.isNotEmpty) return message;
    }
    return fallback;
  }

  /// 添加收藏
  Future<void> addFavorite(String goodsId) async {
    try {
      final response = await _dio.post('/favorite/$goodsId');
      if (response.statusCode != 200 || response.data['code'] != 200) {
        throw ApiException(
          _serverMessage(response, '收藏失败'),
          statusCode: response.statusCode,
        );
      }
    } catch (e) {
      debugPrint('[FavoriteService] addFavorite goodsId=$goodsId error: $e');
      throw ApiException.from(e, fallback: '收藏失败');
    }
  }

  /// 取消收藏
  Future<void> removeFavorite(String goodsId) async {
    try {
      final response = await _dio.delete('/favorite/$goodsId');
      if (response.statusCode != 200 || response.data['code'] != 200) {
        throw ApiException(
          _serverMessage(response, '取消收藏失败'),
          statusCode: response.statusCode,
        );
      }
    } catch (e) {
      debugPrint('[FavoriteService] removeFavorite goodsId=$goodsId error: $e');
      throw ApiException.from(e, fallback: '取消收藏失败');
    }
  }

  /// 检查是否已收藏
  Future<bool> checkFavorite(String goodsId) async {
    try {
      final response = await _dio.get('/favorite/check/$goodsId');
      if (response.statusCode == 200 && response.data['code'] == 200) {
        return response.data['data'] == true;
      }
      throw ApiException(
        _serverMessage(response, '收藏状态查询失败'),
        statusCode: response.statusCode,
      );
    } catch (e) {
      debugPrint('[FavoriteService] checkFavorite goodsId=$goodsId error: $e');
      throw ApiException.from(e, fallback: '收藏状态查询失败');
    }
  }

  /// 分页获取我的收藏列表
  Future<Map<String, dynamic>> getFavoriteList({int page = 1, int size = 10}) async {
    try {
      final response = await _dio.get('/favorite/list', queryParameters: {
        'page': page,
        'size': size,
      });
      if (response.statusCode == 200 && response.data['code'] == 200) {
        final data = response.data['data'];
        final recordsJson = data['records'] as List<dynamic>? ?? [];
        final items = recordsJson
            .map((e) => FavoriteItemModel.fromJson(e as Map<String, dynamic>))
            .toList();
        return {
          'items': items,
          'total': asInt(data['total']),
          'current': asInt(data['current'], 1),
          'pages': asInt(data['pages'], 1),
        };
      }
      throw ApiException(
        _serverMessage(response, '收藏列表加载失败'),
        statusCode: response.statusCode,
      );
    } catch (e) {
      debugPrint('[FavoriteService] getFavoriteList page=$page error: $e');
      throw ApiException.from(e, fallback: '收藏列表加载失败');
    }
  }
}
