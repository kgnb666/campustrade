import 'package:dio/dio.dart';
import '../api/dio_client.dart';
import '../models/history_model.dart';
import '../utils/api_error.dart';
import '../utils/app_logger.dart';
import '../utils/json_cast.dart';

/// 浏览足迹网络服务
///
/// 错误处理约定与 [GoodsService] 一致：失败一律抛 [ApiException]（message 为中文文案），
/// 不再把失败折叠成空列表，"足迹被清空"与"加载失败"因此在 UI 上可分。
class HistoryService {
  final Dio _dio = DioClient().dio;

  String _serverMessage(Response<dynamic> response, String fallback) {
    final dynamic data = response.data;
    if (data is Map) {
      final String message = (data['message'] ?? '').toString().trim();
      if (message.isNotEmpty) return message;
    }
    return fallback;
  }

  /// 分页获取我的浏览历史
  ///
  /// [cancelToken] 由控制器持有：刷新/离开页面时取消在途请求（见 [GoodsService] 的说明）。
  Future<Map<String, dynamic>> getHistoryList({
    int page = 1,
    int size = 20,
    CancelToken? cancelToken,
  }) async {
    try {
      final response = await _dio.get('/history/list', queryParameters: {
        'page': page,
        'size': size,
      }, cancelToken: cancelToken);
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
      throw ApiException(
        _serverMessage(response, '浏览足迹加载失败'),
        statusCode: response.statusCode,
      );
    } catch (e) {
      AppLogger.warn('[HistoryService] getHistoryList page=$page error', error: e);
      throw ApiException.from(e, fallback: '浏览足迹加载失败');
    }
  }
}
