import 'package:dio/dio.dart';
import '../api/dio_client.dart';
import '../models/ai_model.dart';
import '../utils/app_logger.dart';

/// DeepSeek AI 商品助手网络服务
class AiService {
  final Dio _dio = DioClient().dio;

  /// AI 智能润色商品描述
  Future<AiDescriptionModel?> generateDescription({
    required String title,
    String? roughDescription,
    String? conditionLevel,
    List<String>? tags,
  }) async {
    try {
      final response = await _dio.post(
        '/ai/goods/description',
        data: {
          'title': title,
          'roughDescription': roughDescription ?? '',
          'conditionLevel': conditionLevel ?? '良好',
          'tags': tags ?? [],
        },
      );
      if (response.statusCode == 200 && response.data['code'] == 200) {
        return AiDescriptionModel.fromJson(response.data['data']);
      }
      throw Exception(response.data['message'] ?? 'AI 生成描述失败');
    } catch (e) {
      AppLogger.error('[AiService] generateDescription error', error: e);
      rethrow;
    }
  }

  /// AI 智能推荐分类
  Future<AiCategoryModel?> recommendCategory({
    required String title,
    String? description,
  }) async {
    try {
      final response = await _dio.post(
        '/ai/goods/category',
        data: {
          'title': title,
          'description': description ?? '',
        },
      );
      if (response.statusCode == 200 && response.data['code'] == 200) {
        return AiCategoryModel.fromJson(response.data['data']);
      }
      throw Exception(response.data['message'] ?? 'AI 推荐分类失败');
    } catch (e) {
      AppLogger.error('[AiService] recommendCategory error', error: e);
      rethrow;
    }
  }

  /// AI 智能估价建议
  Future<AiPriceModel?> suggestPrice({
    required String title,
    String? description,
    double? originalPrice,
    String? conditionLevel,
  }) async {
    try {
      final response = await _dio.post(
        '/ai/goods/price',
        data: {
          'title': title,
          'description': description ?? '',
          'originalPrice': originalPrice,
          'conditionLevel': conditionLevel ?? '良好',
        },
      );
      if (response.statusCode == 200 && response.data['code'] == 200) {
        return AiPriceModel.fromJson(response.data['data']);
      }
      throw Exception(response.data['message'] ?? 'AI 估价失败');
    } catch (e) {
      AppLogger.error('[AiService] suggestPrice error', error: e);
      rethrow;
    }
  }
}
