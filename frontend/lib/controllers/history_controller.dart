import 'package:flutter/material.dart';
import 'package:get/get.dart';
import '../models/history_model.dart';
import '../services/history_service.dart';
import '../utils/json_cast.dart';

/// 浏览足迹状态控制器
class HistoryController extends GetxController {
  final HistoryService _historyService = HistoryService();

  final RxList<HistoryItemModel> historyList = <HistoryItemModel>[].obs;
  final RxBool isLoading = false.obs;
  final RxBool isMoreLoading = false.obs;
  final RxBool hasMore = true.obs;
  final RxInt currentPage = 1.obs;

  @override
  void onInit() {
    super.onInit();
    loadHistory(refresh: true);
  }

  /// 加载足迹列表
  Future<void> loadHistory({bool refresh = false}) async {
    if (refresh) {
      currentPage.value = 1;
      hasMore.value = true;
      isLoading.value = true;
    }

    try {
      final res = await _historyService.getHistoryList(
        page: currentPage.value,
        size: 20,
      );
      final List<HistoryItemModel> items =
          res['items'] as List<HistoryItemModel>;
      final int totalPages = asInt(res['pages'], 1);

      if (refresh) {
        historyList.assignAll(items);
      } else {
        historyList.addAll(items);
      }

      if (currentPage.value >= totalPages || items.isEmpty) {
        hasMore.value = false;
      }
    } catch (e) {
      debugPrint('[HistoryController] loadHistory error: $e');
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
    await loadHistory(refresh: false);
  }
}
