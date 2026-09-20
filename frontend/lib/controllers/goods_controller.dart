import 'package:flutter/material.dart';
import 'package:get/get.dart';
import '../models/category_model.dart';
import '../models/goods_model.dart';
import '../services/goods_service.dart';
import '../utils/api_error.dart';
import '../utils/json_cast.dart';
import '../utils/ui_feedback.dart';
import '../models/status_enums.dart';

/// 商品业务状态管理控制器
///
/// 阶段 6 起的三条硬约定（列表类控制器统一遵守）：
/// 1. 失败不再静默：服务层抛 [ApiException]，这里写入 [errorMessage] 供页面渲染错误态；
///    加载更多失败还会回滚页码，避免"永久跳过一页"。
/// 2. 每次列表请求携带自增 [requestId]；响应回来时若不是最新请求，整份丢弃，
///    从而杜绝"下拉刷新 + 上拉加载"并发时的重复/错序追加。
/// 3. 控制器关闭（[_closed]）后丢弃所有迟到的写入。
class GoodsController extends GetxController {
  final GoodsService _goodsService = GoodsService();

  // 分类数据
  final RxList<CategoryModel> categories = <CategoryModel>[].obs;
  final RxnString selectedCategoryId = RxnString();

  /// 分类加载失败原因（空字符串表示正常）；页面据此给出可见的降级提示
  final RxString categoryErrorMessage = ''.obs;

  // 商品列表与分页
  final RxList<GoodsItemModel> goodsList = <GoodsItemModel>[].obs;
  final RxBool isLoading = false.obs;
  final RxBool isMoreLoading = false.obs;
  final RxBool hasMore = true.obs;
  final RxInt currentPage = 1.obs;

  /// 列表错误信息（空字符串表示正常）：非空 => 错误态；空且列表为空 => 真正的空态
  final RxString errorMessage = ''.obs;

  /// 是否存在错误（列表相关）
  bool get hasError => errorMessage.isNotEmpty;

  // 搜索关键词与热搜/历史
  final RxString searchKeyword = ''.obs;
  final RxList<String> hotKeywords = <String>[].obs;
  final RxList<String> historyKeywords = <String>[].obs;

  // 我的商品列表
  final RxList<GoodsItemModel> myGoodsList = <GoodsItemModel>[].obs;
  final RxBool isMyGoodsLoading = false.obs;
  final RxString myGoodsErrorMessage = ''.obs;

  /// 列表请求序号：每发起一次列表请求自增，用于作废在途的旧响应。
  int _listRequestSeq = 0;

  /// 控制器是否已关闭（关闭后丢弃所有迟到响应）
  bool _closed = false;

  @override
  void onInit() {
    super.onInit();
    loadCategories();
    loadGoods(refresh: true);
    loadSearchExtras();
  }

  @override
  void onClose() {
    // 没有 CancelToken 时，至少保证关闭后的响应不再写回已销毁的控制器
    _closed = true;
    super.onClose();
  }

  /// 该请求是否已被更新的请求取代（或控制器已关闭）
  bool _isStale(int requestId) => _closed || requestId != _listRequestSeq;

  /// 清除列表错误信息
  void resetError() => errorMessage.value = '';

  /// 加载热搜与搜索历史
  ///
  /// 这两项是列表页的装饰性数据，失败时保持旧值/隐藏即可（可见降级），
  /// 但必须留下日志，方便区分"后端没配热搜"与"接口挂了"。
  Future<void> loadSearchExtras() async {
    try {
      final hots = await _goodsService.getHotSearches();
      final histories = await _goodsService.getSearchHistory();
      if (_closed) return;
      hotKeywords.assignAll(hots);
      historyKeywords.assignAll(histories);
    } catch (e, stack) {
      if (_closed) return;
      debugPrint('[GoodsController] loadSearchExtras 失败（热搜/历史降级为隐藏）: $e\n$stack');
    }
  }

  /// 加载全部分类
  Future<void> loadCategories() async {
    try {
      final list = await _goodsService.getCategories();
      if (_closed) return;
      categories.assignAll(list);
      categoryErrorMessage.value = '';
    } catch (e, stack) {
      if (_closed) return;
      categoryErrorMessage.value = describeApiError(e, fallback: '分类加载失败');
      debugPrint('[GoodsController] loadCategories error: $e\n$stack');
    }
  }

  /// 加载商品列表
  ///
  /// [refresh] 为 true 时重置到第 1 页并清空错误；失败时保留已有列表（只暴露错误态）。
  Future<void> loadGoods({bool refresh = false}) async {
    if (refresh) {
      currentPage.value = 1;
      hasMore.value = true;
      isLoading.value = true;
      errorMessage.value = '';
    }

    // 自增序号：refresh 会作废所有在途的 loadMore 响应
    final int requestId = ++_listRequestSeq;
    final int page = currentPage.value;

    try {
      final res = searchKeyword.value.isNotEmpty
          ? await _goodsService.searchGoods(
              page: page,
              size: 10,
              keyword: searchKeyword.value,
              categoryId: selectedCategoryId.value,
            )
          : await _goodsService.getGoodsList(
              page: page,
              size: 10,
              categoryId: selectedCategoryId.value,
            );

      // 已被更新的请求（如刷新）取代：整份丢弃，绝不再追加，避免重复/错序
      if (_isStale(requestId)) return;

      errorMessage.value = '';
      final List<GoodsItemModel> items = res['items'] as List<GoodsItemModel>;
      final int totalPages = asInt(res['pages'], 1);

      if (refresh) {
        goodsList.assignAll(items);
      } else {
        goodsList.addAll(items);
      }

      hasMore.value = page < totalPages && items.isNotEmpty;
    } catch (e, stack) {
      if (_isStale(requestId)) return;

      errorMessage.value = describeApiError(e, fallback: '商品列表加载失败');
      debugPrint('[GoodsController] loadGoods page=$page error: $e\n$stack');

      if (!refresh) {
        // 加载更多失败必须回滚页码，否则下一次上拉会直接跳过这一页
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

  /// 加载下一页
  Future<void> loadMore() async {
    if (isLoading.value || isMoreLoading.value || !hasMore.value) return;
    isMoreLoading.value = true;
    currentPage.value++;
    await loadGoods(refresh: false);
  }

  /// 搜索关键词
  void onSearch(String keyword) {
    searchKeyword.value = keyword.trim();
    loadGoods(refresh: true);
    loadSearchExtras();
  }

  /// 切换选中分类
  void onSelectCategory(String? catId) {
    if (selectedCategoryId.value == catId) {
      selectedCategoryId.value = null; // 取消筛选
    } else {
      selectedCategoryId.value = catId;
    }
    loadGoods(refresh: true);
  }

  /// 加载我的商品列表
  Future<void> loadMyGoods() async {
    isMyGoodsLoading.value = true;
    myGoodsErrorMessage.value = '';
    try {
      final list = await _goodsService.getMyGoods();
      if (_closed) return;
      myGoodsList.assignAll(list);
    } catch (e, stack) {
      if (_closed) return;
      myGoodsErrorMessage.value = describeApiError(e, fallback: '加载我的商品失败');
      debugPrint('[GoodsController] loadMyGoods error: $e\n$stack');
      // 列表非空（刷新失败）用 snackbar 告知；列表为空时页面本身就是错误态，
      // 不再额外弹窗，避免"打开页面就弹提示"的噪音与残留定时器。
      if (myGoodsList.isNotEmpty) {
        safeSnackbar('提示', myGoodsErrorMessage.value);
      }
    } finally {
      if (!_closed) isMyGoodsLoading.value = false;
    }
  }

  /// 逻辑删除商品
  Future<void> deleteGoods(String id) async {
    try {
      await _goodsService.deleteGoods(id);
      if (_closed) return;
      myGoodsList.removeWhere((g) => g.id == id);
      goodsList.removeWhere((g) => g.id == id);
      safeSnackbar('成功', '商品已下架删除',
          backgroundColor: Colors.green.shade600,
          colorText: Colors.white);
    } catch (e, stack) {
      debugPrint('[GoodsController] deleteGoods id=$id error: $e\n$stack');
      safeSnackbar('删除失败', describeApiError(e, fallback: '删除商品失败'));
    }
  }

  /// 切换商品状态 (上架 / 下架)
  Future<void> toggleGoodsStatus(String id, String currentStatus) async {
    final targetStatus = (GoodsStatus.fromCode(currentStatus)?.isBuyable ?? false)
        ? GoodsStatus.offShelf.code
        : GoodsStatus.onSale.code;
    try {
      await _goodsService.updateGoodsStatus(id, targetStatus);
      if (_closed) return;
      await loadMyGoods();
      loadGoods(refresh: true);
      safeSnackbar(
        '成功',
        GoodsStatus.fromCode(targetStatus)?.isBuyable == true ? '商品已重新上架' : '商品已下架',
        backgroundColor: Colors.blue.shade600,
        colorText: Colors.white,
      );
    } catch (e, stack) {
      debugPrint('[GoodsController] toggleGoodsStatus id=$id error: $e\n$stack');
      safeSnackbar('操作失败', describeApiError(e, fallback: '商品状态修改失败'));
    }
  }
}
