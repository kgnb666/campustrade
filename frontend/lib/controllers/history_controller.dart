import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:get/get.dart';
import '../config/app_config.dart';
import '../models/history_model.dart';
import '../services/history_service.dart';
import '../utils/api_error.dart';
import '../utils/json_cast.dart';
import '../utils/ui_feedback.dart';

/// 浏览足迹状态控制器
///
/// 与 [GoodsController] 相同的三条约定：错误可区分（errorMessage）、
/// 请求序号作废旧响应、加载更多失败回滚页码。
class HistoryController extends GetxController {
  final HistoryService _historyService = HistoryService();

  final RxList<HistoryItemModel> historyList = <HistoryItemModel>[].obs;
  final RxBool isLoading = false.obs;
  final RxBool isMoreLoading = false.obs;
  final RxBool hasMore = true.obs;
  final RxInt currentPage = 1.obs;

  /// 错误信息（空字符串表示正常）：非空 => 错误态；空且列表为空 => 空态
  final RxString errorMessage = ''.obs;

  bool get hasError => errorMessage.isNotEmpty;

  /// 列表请求序号：用于作废在途的旧响应
  int _listRequestSeq = 0;

  bool _closed = false;

  @override
  void onInit() {
    super.onInit();
    loadHistory(refresh: true);
  }

  @override
  void onClose() {
    _closed = true;
    super.onClose();
  }

  bool _isStale(int requestId) => _closed || requestId != _listRequestSeq;

  /// 清除错误信息
  void resetError() => errorMessage.value = '';

  /// 加载足迹列表
  Future<void> loadHistory({bool refresh = false}) async {
    if (refresh) {
      currentPage.value = 1;
      hasMore.value = true;
      isLoading.value = true;
      errorMessage.value = '';
    }

    final int requestId = ++_listRequestSeq;
    final int page = currentPage.value;

    try {
      final res = await _historyService.getHistoryList(
        page: page,
        size: AppConfig.historyPageSize,
      );

      if (_isStale(requestId)) return;

      errorMessage.value = '';
      final List<HistoryItemModel> items =
          res['items'] as List<HistoryItemModel>;
      final int totalPages = asInt(res['pages'], 1);

      if (refresh) {
        historyList.assignAll(items);
      } else {
        historyList.addAll(items);
      }

      hasMore.value = page < totalPages && items.isNotEmpty;
    } catch (e, stack) {
      if (_isStale(requestId)) return;

      errorMessage.value = describeApiError(e, fallback: '浏览足迹加载失败');
      debugPrint('[HistoryController] loadHistory page=$page error: $e\n$stack');

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
    await loadHistory(refresh: false);
  }
}
