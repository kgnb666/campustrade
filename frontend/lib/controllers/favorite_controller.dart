import 'package:flutter/material.dart';
import 'package:get/get.dart';
import '../models/favorite_model.dart';
import '../services/favorite_service.dart';
import '../utils/json_cast.dart';

/// 收藏列表状态控制器
class FavoriteController extends GetxController {
  final FavoriteService _favoriteService = FavoriteService();

  final RxList<FavoriteItemModel> favoriteList = <FavoriteItemModel>[].obs;
  final RxBool isLoading = false.obs;
  final RxBool isMoreLoading = false.obs;
  final RxBool hasMore = true.obs;
  final RxInt currentPage = 1.obs;

  @override
  void onInit() {
    super.onInit();
    loadFavorites(refresh: true);
  }

  /// 加载收藏列表
  Future<void> loadFavorites({bool refresh = false}) async {
    if (refresh) {
      currentPage.value = 1;
      hasMore.value = true;
      isLoading.value = true;
    }

    try {
      final res = await _favoriteService.getFavoriteList(
        page: currentPage.value,
        size: 10,
      );
      final List<FavoriteItemModel> items =
          res['items'] as List<FavoriteItemModel>;
      final int totalPages = asInt(res['pages'], 1);

      if (refresh) {
        favoriteList.assignAll(items);
      } else {
        favoriteList.addAll(items);
      }

      if (currentPage.value >= totalPages || items.isEmpty) {
        hasMore.value = false;
      }
    } catch (e) {
      debugPrint('[FavoriteController] loadFavorites error: $e');
    } finally {
      isLoading.value = false;
      isMoreLoading.value = false;
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
    final success = await _favoriteService.removeFavorite(goodsId);
    if (success) {
      favoriteList.removeWhere((item) => item.goodsId == goodsId);
      Get.snackbar(
        '提示',
        '已取消收藏',
        snackPosition: SnackPosition.BOTTOM,
        duration: const Duration(seconds: 2),
      );
    } else {
      Get.snackbar(
        '错误',
        '取消收藏失败，请稍后重试',
        snackPosition: SnackPosition.BOTTOM,
      );
    }
  }
}
