import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import '../api/dio_client.dart';
import '../models/history_model.dart';
import '../utils/json_cast.dart';

/// 浏览足迹网络服务
class HistoryService {
  final Dio _dio = DioClient().dio;

  /// 分页获取我的浏览历史
  Future<Map<String, dynamic>> getHistoryList({int page = 1, int size = 20}) async {
    try {
      final response = await _dio.get('/history/list', queryParameters: {
        'page': page,
        'size': size,
      });
      if (response.statusCode == 200 && response.data['code'] == 200) {
        final data = response.data['data'];
        final recordsJson = data['records'] as List<dynamic>? ?? [];
        final items = recordsJson
            .map((e) => HistoryItemModel.fromJson(e as Map<String, dynamic>))
            .toList();
        return {
          'items': items,
          'total': asInt(data['total']),
          'current': asInt(data['current'], 1),
          'pages': asInt(data['pages'], 1),
        };
      }
      return {'items': <HistoryItemModel>[], 'total': 0, 'current': 1, 'pages': 1};
    } catch (e) {
      debugPrint('[HistoryService] getHistoryList error: $e');
      return {'items': <HistoryItemModel>[], 'total': 0, 'current': 1, 'pages': 1};
    }
  }
}
