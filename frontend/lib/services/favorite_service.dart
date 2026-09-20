import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import '../api/dio_client.dart';
import '../models/favorite_model.dart';
import '../utils/json_cast.dart';

/// 收藏网络服务
class FavoriteService {
  final Dio _dio = DioClient().dio;

  /// 添加收藏
  Future<bool> addFavorite(String goodsId) async {
    try {
      final response = await _dio.post('/favorite/$goodsId');
      return response.statusCode == 200 && response.data['code'] == 200;
    } catch (e) {
      debugPrint('[FavoriteService] addFavorite error: $e');
      return false;
    }
  }

  /// 取消收藏
  Future<bool> removeFavorite(String goodsId) async {
    try {
      final response = await _dio.delete('/favorite/$goodsId');
      return response.statusCode == 200 && response.data['code'] == 200;
    } catch (e) {
      debugPrint('[FavoriteService] removeFavorite error: $e');
      return false;
    }
  }

  /// 检查是否已收藏
  Future<bool> checkFavorite(String goodsId) async {
    try {
      final response = await _dio.get('/favorite/check/$goodsId');
      if (response.statusCode == 200 && response.data['code'] == 200) {
        return response.data['data'] == true;
      }
      return false;
    } catch (e) {
      debugPrint('[FavoriteService] checkFavorite error: $e');
      return false;
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
      return {'items': <FavoriteItemModel>[], 'total': 0, 'current': 1, 'pages': 1};
    } catch (e) {
      debugPrint('[FavoriteService] getFavoriteList error: $e');
      return {'items': <FavoriteItemModel>[], 'total': 0, 'current': 1, 'pages': 1};
    }
  }
}
