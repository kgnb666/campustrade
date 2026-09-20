import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import '../api/dio_client.dart';
import '../models/category_model.dart';
import '../models/goods_model.dart';
import '../utils/json_cast.dart';

/// 商品业务接口网络服务
class GoodsService {
  final Dio _dio = DioClient().dio;

  /// 获取商品树形分类列表
  Future<List<CategoryModel>> getCategories() async {
    try {
      final response = await _dio.get('/category/list');
      if (response.statusCode == 200 && response.data['code'] == 200) {
        final list = response.data['data'] as List<dynamic>? ?? [];
        return list
            .map((e) => CategoryModel.fromJson(e as Map<String, dynamic>))
            .toList();
      }
      return [];
    } catch (e) {
      debugPrint('[GoodsService] getCategories error: $e');
      return [];
    }
  }

  /// 分页搜索筛选商品列表
  Future<Map<String, dynamic>> getGoodsList({
    int page = 1,
    int size = 10,
    String? keyword,
    String? categoryId,
    String? schoolId,
    double? minPrice,
    double? maxPrice,
    String? conditionLevel,
  }) async {
    try {
      final Map<String, dynamic> queryParams = {
        'page': page,
        'size': size,
      };
      if (keyword != null && keyword.isNotEmpty) queryParams['keyword'] = keyword;
      if (categoryId != null) queryParams['categoryId'] = categoryId;
      if (schoolId != null) queryParams['schoolId'] = schoolId;
      if (minPrice != null) queryParams['minPrice'] = minPrice;
      if (maxPrice != null) queryParams['maxPrice'] = maxPrice;
      if (conditionLevel != null && conditionLevel.isNotEmpty) {
        queryParams['conditionLevel'] = conditionLevel;
      }

      final response = await _dio.get('/goods/list', queryParameters: queryParams);
      if (response.statusCode == 200 && response.data['code'] == 200) {
        final data = response.data['data'];
        final recordsJson = data['records'] as List<dynamic>? ?? [];
        final items = recordsJson
            .map((e) => GoodsItemModel.fromJson(e as Map<String, dynamic>))
            .toList();
        return {
          'items': items,
          'total': asInt(data['total']),
          'current': asInt(data['current'], 1),
          'pages': asInt(data['pages'], 1),
        };
      }
      return {'items': <GoodsItemModel>[], 'total': 0, 'current': 1, 'pages': 1};
    } catch (e) {
      debugPrint('[GoodsService] getGoodsList error: $e');
      return {'items': <GoodsItemModel>[], 'total': 0, 'current': 1, 'pages': 1};
    }
  }

  /// 获取商品详情
  Future<GoodsDetailModel?> getGoodsDetail(String id) async {
    try {
      final response = await _dio.get('/goods/$id');
      if (response.statusCode == 200 && response.data['code'] == 200) {
        return GoodsDetailModel.fromJson(response.data['data']);
      }
      return null;
    } catch (e) {
      debugPrint('[GoodsService] getGoodsDetail error: $e');
      return null;
    }
  }

  /// 发布商品，返回新商品 ID
  ///
  /// 返回 String：后端把 Long 型 ID 序列化为字符串，用 int 承载会在 Web 上丢精度（也会直接抛类型异常）。
  Future<String> createGoods(Map<String, dynamic> data) async {
    final response = await _dio.post('/goods', data: data);
    if (response.statusCode == 200 && response.data['code'] == 200) {
      return response.data['data']?.toString() ?? '';
    } else {
      throw Exception(response.data['message'] ?? '发布商品失败');
    }
  }

  /// 修改商品
  Future<void> updateGoods(String id, Map<String, dynamic> data) async {
    final response = await _dio.put('/goods/$id', data: data);
    if (response.statusCode != 200 || response.data['code'] != 200) {
      throw Exception(response.data['message'] ?? '修改商品失败');
    }
  }

  /// 逻辑删除商品
  Future<void> deleteGoods(String id) async {
    final response = await _dio.delete('/goods/$id');
    if (response.statusCode != 200 || response.data['code'] != 200) {
      throw Exception(response.data['message'] ?? '删除商品失败');
    }
  }

  /// 修改商品状态 (ON_SALE / OFF_SHELF)
  Future<void> updateGoodsStatus(String id, String status) async {
    final response = await _dio.put(
      '/goods/$id/status',
      data: {'status': status},
    );
    if (response.statusCode != 200 || response.data['code'] != 200) {
      throw Exception(response.data['message'] ?? '更新状态失败');
    }
  }

  /// 获取当前登录用户的全部商品
  Future<List<GoodsItemModel>> getMyGoods() async {
    try {
      final response = await _dio.get('/goods/my');
      if (response.statusCode == 200 && response.data['code'] == 200) {
        final list = response.data['data'] as List<dynamic>? ?? [];
        return list
            .map((e) => GoodsItemModel.fromJson(e as Map<String, dynamic>))
            .toList();
      }
      return [];
    } catch (e) {
      debugPrint('[GoodsService] getMyGoods error: $e');
      return [];
    }
  }

  /// 增强搜索商品（自动记录搜索历史与热搜词权重）
  Future<Map<String, dynamic>> searchGoods({
    String? keyword,
    String? categoryId,
    String? schoolId,
    double? minPrice,
    double? maxPrice,
    String? sort,
    int page = 1,
    int size = 10,
  }) async {
    try {
      final Map<String, dynamic> queryParams = {
        'page': page,
        'size': size,
      };
      if (keyword != null && keyword.trim().isNotEmpty) queryParams['keyword'] = keyword.trim();
      if (categoryId != null) queryParams['categoryId'] = categoryId;
      if (schoolId != null) queryParams['schoolId'] = schoolId;
      if (minPrice != null) queryParams['minPrice'] = minPrice;
      if (maxPrice != null) queryParams['maxPrice'] = maxPrice;
      if (sort != null && sort.isNotEmpty) queryParams['sort'] = sort;

      final response = await _dio.get('/goods/search', queryParameters: queryParams);
      if (response.statusCode == 200 && response.data['code'] == 200) {
        final data = response.data['data'];
        final recordsJson = data['records'] as List<dynamic>? ?? [];
        final items = recordsJson
            .map((e) => GoodsItemModel.fromJson(e as Map<String, dynamic>))
            .toList();
        return {
          'items': items,
          'total': asInt(data['total']),
          'current': asInt(data['current'], 1),
          'pages': asInt(data['pages'], 1),
        };
      }
      return {'items': <GoodsItemModel>[], 'total': 0, 'current': 1, 'pages': 1};
    } catch (e) {
      debugPrint('[GoodsService] searchGoods error: $e');
      return {'items': <GoodsItemModel>[], 'total': 0, 'current': 1, 'pages': 1};
    }
  }

  /// 获取全站热搜词 Top 10
  Future<List<String>> getHotSearches() async {
    try {
      final response = await _dio.get('/goods/search/hot');
      if (response.statusCode == 200 && response.data['code'] == 200) {
        final raw = response.data['data'];
        if (raw is List) {
          return raw.map((e) => e.toString()).toList();
        }
      }
      return [];
    } catch (e) {
      debugPrint('[GoodsService] getHotSearches error: $e');
      return [];
    }
  }

  /// 获取当前用户的最近搜索历史
  Future<List<String>> getSearchHistory() async {
    try {
      final response = await _dio.get('/goods/search/history');
      if (response.statusCode == 200 && response.data['code'] == 200) {
        final raw = response.data['data'];
        if (raw is List) {
          return raw.map((e) => e.toString()).toList();
        }
      }
      return [];
    } catch (e) {
      debugPrint('[GoodsService] getSearchHistory error: $e');
      return [];
    }
  }

  /// 上传图片至 MinIO
  Future<String> uploadImageBytes(Uint8List bytes, String filename) async {
    final formData = FormData.fromMap({
      'file': MultipartFile.fromBytes(bytes, filename: filename),
    });

    final response = await _dio.post('/file/upload', data: formData);
    if (response.statusCode == 200 && response.data['code'] == 200) {
      return response.data['data'] as String;
    } else {
      throw Exception(response.data['message'] ?? '图片上传失败');
    }
  }
}
