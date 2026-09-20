import 'package:flutter/material.dart';
import 'package:get/get.dart';
import '../models/category_model.dart';
import '../models/goods_model.dart';
import '../services/goods_service.dart';
import '../utils/json_cast.dart';

/// 商品业务状态管理控制器
class GoodsController extends GetxController {
  final GoodsService _goodsService = GoodsService();

  // 分类数据
  final RxList<CategoryModel> categories = <CategoryModel>[].obs;
  final RxnString selectedCategoryId = RxnString();

  // 商品列表与分页
  final RxList<GoodsItemModel> goodsList = <GoodsItemModel>[].obs;
  final RxBool isLoading = false.obs;
  final RxBool isMoreLoading = false.obs;
  final RxBool hasMore = true.obs;
  final RxInt currentPage = 1.obs;
  // 搜索关键词与热搜/历史
  final RxString searchKeyword = ''.obs;
  final RxList<String> hotKeywords = <String>[].obs;
  final RxList<String> historyKeywords = <String>[].obs;

  // 我的商品列表
  final RxList<GoodsItemModel> myGoodsList = <GoodsItemModel>[].obs;
  final RxBool isMyGoodsLoading = false.obs;

  @override
  void onInit() {
    super.onInit();
    loadCategories();
    loadGoods(refresh: true);
    loadSearchExtras();
  }

  /// 加载热搜与搜索历史
  Future<void> loadSearchExtras() async {
    try {
      final hots = await _goodsService.getHotSearches();
      hotKeywords.assignAll(hots);
      final histories = await _goodsService.getSearchHistory();
      historyKeywords.assignAll(histories);
    } catch (_) {}
  }

  /// 加载全部分类
  Future<void> loadCategories() async {
    final list = await _goodsService.getCategories();
    categories.assignAll(list);
  }

  /// 加载商品列表
  Future<void> loadGoods({bool refresh = false}) async {
    if (refresh) {
      currentPage.value = 1;
      hasMore.value = true;
      isLoading.value = true;
    }

    try {
      final res = searchKeyword.value.isNotEmpty
          ? await _goodsService.searchGoods(
              page: currentPage.value,
              size: 10,
              keyword: searchKeyword.value,
              categoryId: selectedCategoryId.value,
            )
          : await _goodsService.getGoodsList(
              page: currentPage.value,
              size: 10,
              categoryId: selectedCategoryId.value,
            );

      final List<GoodsItemModel> items = res['items'] as List<GoodsItemModel>;
      final int totalPages = asInt(res['pages'], 1);

      if (refresh) {
        goodsList.assignAll(items);
      } else {
        goodsList.addAll(items);
      }

      hasMore.value = currentPage.value < totalPages;
    } catch (e) {
      Get.snackbar('错误', '加载商品列表失败: $e', snackPosition: SnackPosition.BOTTOM);
    } finally {
      isLoading.value = false;
      isMoreLoading.value = false;
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
    try {
      final list = await _goodsService.getMyGoods();
      myGoodsList.assignAll(list);
    } catch (e) {
      Get.snackbar('提示', '加载我的商品失败', snackPosition: SnackPosition.BOTTOM);
    } finally {
      isMyGoodsLoading.value = false;
    }
  }

  /// 逻辑删除商品
  Future<void> deleteGoods(String id) async {
    try {
      await _goodsService.deleteGoods(id);
      myGoodsList.removeWhere((g) => g.id == id);
      goodsList.removeWhere((g) => g.id == id);
      Get.snackbar('成功', '商品已下架删除',
          snackPosition: SnackPosition.BOTTOM,
          backgroundColor: Colors.green.shade600,
          colorText: Colors.white);
    } catch (e) {
      Get.snackbar('删除失败', '$e', snackPosition: SnackPosition.BOTTOM);
    }
  }

  /// 切换商品状态 (上架 / 下架)
  Future<void> toggleGoodsStatus(String id, String currentStatus) async {
    final targetStatus = currentStatus == 'ON_SALE' ? 'OFF_SHELF' : 'ON_SALE';
    try {
      await _goodsService.updateGoodsStatus(id, targetStatus);
      await loadMyGoods();
      loadGoods(refresh: true);
      Get.snackbar(
        '成功',
        targetStatus == 'ON_SALE' ? '商品已重新上架' : '商品已下架',
        snackPosition: SnackPosition.BOTTOM,
        backgroundColor: Colors.blue.shade600,
        colorText: Colors.white,
      );
    } catch (e) {
      Get.snackbar('操作失败', '$e', snackPosition: SnackPosition.BOTTOM);
    }
  }
}
