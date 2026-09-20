import 'package:dio/dio.dart';
import 'package:get/get.dart';
import '../config/app_config.dart';
import '../models/favorite_model.dart';
import '../services/favorite_service.dart';
import '../utils/api_error.dart';
import '../utils/app_logger.dart';
import '../utils/json_cast.dart';
import '../utils/ui_feedback.dart';

/// 收藏列表状态控制器
///
/// 与 [GoodsController] 相同的三条约定：错误可区分（errorMessage）、
/// 请求序号作废旧响应、加载更多失败回滚页码。
class FavoriteController extends GetxController {
  final FavoriteService _favoriteService = FavoriteService();

  final RxList<FavoriteItemModel> favoriteList = <FavoriteItemModel>[].obs;
  final RxBool isLoading = false.obs;
  final RxBool isMoreLoading = false.obs;
  final RxBool hasMore = true.obs;
  final RxInt currentPage = 1.obs;

  /// 错误信息（空字符串表示正常）：非空 => 错误态；空且列表为空 => 空态
  final RxString errorMessage = ''.obs;

  bool get hasError => errorMessage.isNotEmpty;

  /// 列表请求序号：用于作废在途的旧响应
  int _listRequestSeq = 0;

  /// 列表请求的取消令牌：新请求发起时取消上一个仍在途的请求（见 [GoodsController] 同名字段）
  CancelToken? _listCancelToken;

  bool _closed = false;

  @override
  void onInit() {
    super.onInit();
    loadFavorites(refresh: true);
  }

  @override
  void onClose() {
    // 关闭后不仅不再写回状态，在途请求也一并取消
    _closed = true;
    _listCancelToken?.cancel('FavoriteController closed');
    _listCancelToken = null;
    super.onClose();
  }

  bool _isStale(int requestId) => _closed || requestId != _listRequestSeq;

  /// 清除错误信息
  void resetError() => errorMessage.value = '';

  /// 加载收藏列表
  Future<void> loadFavorites({bool refresh = false}) async {
    if (refresh) {
      currentPage.value = 1;
      hasMore.value = true;
      isLoading.value = true;
      errorMessage.value = '';
    }

    final int requestId = ++_listRequestSeq;
    final int page = currentPage.value;

    // 只有最新一次列表请求的结果有意义：取消上一个仍在途的请求
    _listCancelToken?.cancel('superseded by a newer favorite list request');
    final CancelToken cancelToken = CancelToken();
    _listCancelToken = cancelToken;

    try {
      final res = await _favoriteService.getFavoriteList(
        page: page,
        size: AppConfig.favoritePageSize,
        cancelToken: cancelToken,
      );

      if (_isStale(requestId)) return;

      errorMessage.value = '';
      final List<FavoriteItemModel> items =
          res['items'] as List<FavoriteItemModel>;
      final int totalPages = asInt(res['pages'], 1);

      if (refresh) {
        favoriteList.assignAll(items);
      } else {
        favoriteList.addAll(items);
      }

      hasMore.value = page < totalPages && items.isNotEmpty;
    } catch (e, stack) {
      if (_isStale(requestId)) return;

      errorMessage.value = describeApiError(e, fallback: '收藏列表加载失败');
      AppLogger.error('[FavoriteController] loadFavorites page=$page error', error: e, stackTrace: stack);

      if (!refresh) {
        // 回滚页码，避免永久跳过这一页
        currentPage.value = page - 1;
        safeSnackbar('加载失败', errorMessage.value);
      }
    } finally {
      if (!_isStale(requestId)) {
        isLoading.value = false;
        isMoreLoading.value = false;
      }
    }
  }

  /// 加载更多
  Future<void> loadMore() async {
    if (isLoading.value || isMoreLoading.value || !hasMore.value) return;
    isMoreLoading.value = true;
    currentPage.value++;
    await loadFavorites(refresh: false);
  }

  /// 取消收藏
  Future<void> removeFavorite(String goodsId) async {
    try {
      await _favoriteService.removeFavorite(goodsId);
      if (_closed) return;
      favoriteList.removeWhere((item) => item.goodsId == goodsId);
      safeSnackbar(
        '提示',
        '已取消收藏',
        duration: const Duration(seconds: 2),
      );
    } catch (e, stack) {
      AppLogger.error('[FavoriteController] removeFavorite goodsId=$goodsId error', error: e, stackTrace: stack);
      safeSnackbar(
        '错误',
        describeApiError(e, fallback: '取消收藏失败，请稍后重试'),
      );
    }
  }
}
