import 'package:dio/dio.dart';
// Uint8List（图片字节）
import 'dart:typed_data';
import '../api/dio_client.dart';
import '../models/category_model.dart';
import '../models/goods_model.dart';
import '../utils/api_error.dart';
import '../utils/app_logger.dart';
import '../utils/json_cast.dart';

/// 商品业务接口网络服务
///
/// 错误处理约定（与阶段 6 的"错误态与空态必须可分"一致）：
/// 服务层**不再**把异常吞掉返回空集合/ null，而是统一抛出 [ApiException]，
/// 由控制器区分"请求失败(errorMessage 非空)"与"确实没有数据(空列表)"。
/// [ApiException.message] 已是可直接展示的中文文案。
///
/// 可取消：列表与详情方法都接受可选的 `cancelToken`，由控制器持有并在
/// "发起新请求 / 控制器关闭"时 `cancel()`，避免快速切分类或搜索时同时挂着
/// 多个真实请求（只丢弃迟到响应并不节省一次往返，也不会停止后端继续处理）。
class GoodsService {
  final Dio _dio = DioClient().dio;

  /// 安全取出服务端业务提示（响应体结构：{code,message,data,timestamp}），
  /// 拿不到时回退为调用方给的中文兜底文案。
  String _serverMessage(Response<dynamic> response, String fallback) {
    final dynamic data = response.data;
    if (data is Map) {
      final String message = (data['message'] ?? '').toString().trim();
      if (message.isNotEmpty) return message;
    }
    return fallback;
  }

  /// 获取商品树形分类列表
  Future<List<CategoryModel>> getCategories({CancelToken? cancelToken}) async {
    try {
      final response = await _dio.get('/category/list', cancelToken: cancelToken);
      if (response.statusCode == 200 && response.data['code'] == 200) {
        final list = response.data['data'] as List<dynamic>? ?? [];
        return list
            .map((e) => CategoryModel.fromJson(e as Map<String, dynamic>))
            .toList();
      }
      throw ApiException(
        _serverMessage(response, '分类加载失败'),
        statusCode: response.statusCode,
      );
    } catch (e) {
      AppLogger.warn('[GoodsService] getCategories error', error: e);
      throw ApiException.from(e, fallback: '分类加载失败');
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
    CancelToken? cancelToken,
  }) async {
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

    try {
      final response = await _dio
          .get('/goods/list', queryParameters: queryParams, cancelToken: cancelToken);
      return _parseGoodsPage(response);
    } catch (e) {
      AppLogger.warn('[GoodsService] getGoodsList page=$page error', error: e);
      throw ApiException.from(e, fallback: '商品列表加载失败');
    }
  }

  /// 解析分页响应：非 200 一律抛异常，绝不把失败当空列表返回
  Map<String, dynamic> _parseGoodsPage(Response<dynamic> response) {
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
    throw ApiException(
      _serverMessage(response, '商品列表加载失败'),
      statusCode: response.statusCode,
    );
  }

  /// 获取商品详情
  ///
  /// 返回 null 仅表示"服务端成功响应但没有数据"，请求失败一律抛 [ApiException]，
  /// 调用方才能把"网络断了"和"商品真的不存在"分开提示。
  Future<GoodsDetailModel?> getGoodsDetail(String id, {CancelToken? cancelToken}) async {
    try {
      final response = await _dio.get('/goods/$id', cancelToken: cancelToken);
      if (response.statusCode == 200 && response.data['code'] == 200) {
        final data = response.data['data'];
        if (data == null) return null;
        return GoodsDetailModel.fromJson(data);
      }
      throw ApiException(
        _serverMessage(response, '商品详情加载失败'),
        statusCode: response.statusCode,
      );
    } catch (e) {
      AppLogger.warn('[GoodsService] getGoodsDetail id=$id error', error: e);
      throw ApiException.from(e, fallback: '商品详情加载失败');
    }
  }

  /// 发布商品，返回新商品 ID
  ///
  /// 返回 String：后端把 Long 型 ID 序列化为字符串，用 int 承载会在 Web 上丢精度（也会直接抛类型异常）。
  Future<String> createGoods(Map<String, dynamic> data) async {
    try {
      final response = await _dio.post('/goods', data: data);
      if (response.statusCode == 200 && response.data['code'] == 200) {
        return response.data['data']?.toString() ?? '';
      }
      throw ApiException(
        _serverMessage(response, '发布商品失败'),
        statusCode: response.statusCode,
      );
    } catch (e) {
      AppLogger.error('[GoodsService] createGoods error', error: e);
      throw ApiException.from(e, fallback: '发布商品失败');
    }
  }

  /// 修改商品
  Future<void> updateGoods(String id, Map<String, dynamic> data) async {
    try {
      final response = await _dio.put('/goods/$id', data: data);
      if (response.statusCode != 200 || response.data['code'] != 200) {
        throw ApiException(
        _serverMessage(response, '修改商品失败'),
        statusCode: response.statusCode,
      );
      }
    } catch (e) {
      AppLogger.error('[GoodsService] updateGoods id=$id error', error: e);
      throw ApiException.from(e, fallback: '修改商品失败');
    }
  }

  /// 逻辑删除商品
  Future<void> deleteGoods(String id) async {
    try {
      final response = await _dio.delete('/goods/$id');
      if (response.statusCode != 200 || response.data['code'] != 200) {
        throw ApiException(
        _serverMessage(response, '删除商品失败'),
        statusCode: response.statusCode,
      );
      }
    } catch (e) {
      AppLogger.error('[GoodsService] deleteGoods id=$id error', error: e);
      throw ApiException.from(e, fallback: '删除商品失败');
    }
  }

  /// 修改商品状态 (ON_SALE / OFF_SHELF)
  Future<void> updateGoodsStatus(String id, String status) async {
    try {
      final response = await _dio.put(
        '/goods/$id/status',
        data: {'status': status},
      );
      if (response.statusCode != 200 || response.data['code'] != 200) {
        throw ApiException(
        _serverMessage(response, '更新状态失败'),
        statusCode: response.statusCode,
      );
      }
    } catch (e) {
      AppLogger.error('[GoodsService] updateGoodsStatus id=$id error', error: e);
      throw ApiException.from(e, fallback: '更新状态失败');
    }
  }

  /// 获取当前登录用户的全部商品
  Future<List<GoodsItemModel>> getMyGoods({CancelToken? cancelToken}) async {
    try {
      final response = await _dio.get('/goods/my', cancelToken: cancelToken);
      if (response.statusCode == 200 && response.data['code'] == 200) {
        final list = response.data['data'] as List<dynamic>? ?? [];
        return list
            .map((e) => GoodsItemModel.fromJson(e as Map<String, dynamic>))
            .toList();
      }
      throw ApiException(
        _serverMessage(response, '我的商品加载失败'),
        statusCode: response.statusCode,
      );
    } catch (e) {
      AppLogger.warn('[GoodsService] getMyGoods error', error: e);
      throw ApiException.from(e, fallback: '我的商品加载失败');
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
    CancelToken? cancelToken,
  }) async {
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

    try {
      final response = await _dio
          .get('/goods/search', queryParameters: queryParams, cancelToken: cancelToken);
      return _parseGoodsPage(response);
    } catch (e) {
      AppLogger.warn('[GoodsService] searchGoods page=$page error', error: e);
      throw ApiException.from(e, fallback: '搜索商品失败');
    }
  }

  /// 获取全站热搜词 Top 10
  Future<List<String>> getHotSearches({CancelToken? cancelToken}) async {
    try {
      final response = await _dio.get('/goods/search/hot', cancelToken: cancelToken);
      if (response.statusCode == 200 && response.data['code'] == 200) {
        final raw = response.data['data'];
        if (raw is List) {
          return raw.map((e) => e.toString()).toList();
        }
        return <String>[];
      }
      throw ApiException(
        _serverMessage(response, '热搜加载失败'),
        statusCode: response.statusCode,
      );
    } catch (e) {
      AppLogger.warn('[GoodsService] getHotSearches error', error: e);
      throw ApiException.from(e, fallback: '热搜加载失败');
    }
  }

  /// 获取当前用户的最近搜索历史
  Future<List<String>> getSearchHistory({CancelToken? cancelToken}) async {
    try {
      final response = await _dio.get('/goods/search/history', cancelToken: cancelToken);
      if (response.statusCode == 200 && response.data['code'] == 200) {
        final raw = response.data['data'];
        if (raw is List) {
          return raw.map((e) => e.toString()).toList();
        }
        return <String>[];
      }
      throw ApiException(
        _serverMessage(response, '搜索历史加载失败'),
        statusCode: response.statusCode,
      );
    } catch (e) {
      AppLogger.warn('[GoodsService] getSearchHistory error', error: e);
      throw ApiException.from(e, fallback: '搜索历史加载失败');
    }
  }

  /// 上传图片至 MinIO
  Future<String> uploadImageBytes(Uint8List bytes, String filename) async {
    final formData = FormData.fromMap({
      'file': MultipartFile.fromBytes(bytes, filename: filename),
    });

    try {
      final response = await _dio.post('/file/upload', data: formData);
      if (response.statusCode == 200 && response.data['code'] == 200) {
        return response.data['data'] as String;
      }
      throw ApiException(
        _serverMessage(response, '图片上传失败'),
        statusCode: response.statusCode,
      );
    } catch (e) {
      AppLogger.error('[GoodsService] uploadImageBytes file=$filename error', error: e);
      throw ApiException.from(e, fallback: '图片上传失败');
    }
  }
}
