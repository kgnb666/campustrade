import 'package:flutter/foundation.dart';
import 'package:get/get.dart';
import '../config/app_config.dart';
import '../api/order_api.dart';
import '../models/order.dart';
import '../utils/api_error.dart';

/// 订单模块状态控制器 (GetX)
/// 负责管理我的订单列表、订单详情、流转操作以及响应式 loading/error 状态
///
/// 阶段 6 起列表分页遵守统一约定：
/// - 每次请求带自增 requestId，响应回来若已不是最新请求则整份丢弃
///   （避免"下拉刷新 + 上拉加载"并发时出现"新列表 + 旧页追加"的重复/错序）；
/// - 加载更多失败回滚页码，否则下一次上拉会永久跳过该页；
/// - 控制器关闭后丢弃所有迟到写入。
class OrderController extends GetxController {
  final OrderApi _orderApi;

  OrderController({OrderApi? orderApi}) : _orderApi = orderApi ?? OrderApi();

  /// 订单列表 (支持买家或卖家视角)
  final RxList<OrderVO> orders = <OrderVO>[].obs;

  /// 当前选中的订单详情
  final Rxn<OrderVO> currentOrder = Rxn<OrderVO>();

  /// 页面加载状态 (主加载)
  final RxBool loading = false.obs;

  /// 分页上拉加载状态
  final RxBool isMoreLoading = false.obs;

  /// 是否还有更多数据
  final RxBool hasMore = true.obs;

  /// 当前页码
  final RxInt currentPage = 1.obs;

  /// 错误信息 (空字符串表示正常)
  final RxString errorMessage = ''.obs;

  /// 当前视角: BUYER (我买到的，默认) / SELLER (我卖出的)
  final RxString currentRole = 'BUYER'.obs;

  /// 当前状态过滤 (null 表示全部状态)
  final Rxn<OrderStatus> currentStatusFilter = Rxn<OrderStatus>();

  /// 列表请求序号：用于作废在途的旧响应
  int _listRequestSeq = 0;

  /// 控制器是否已关闭（关闭后丢弃迟到响应）
  bool _closed = false;

  @override
  void onClose() {
    _closed = true;
    super.onClose();
  }

  bool _isStale(int requestId) => _closed || requestId != _listRequestSeq;

  /// 是否存在错误
  bool get hasError => errorMessage.isNotEmpty;

  /// 清除错误信息
  void resetError() {
    errorMessage.value = '';
  }

  /// 清空当前订单详情
  void clearCurrentOrder() {
    currentOrder.value = null;
  }

  /// 切换角色视角 (BUYER / SELLER) 并重新拉取
  Future<void> switchRole(String role) async {
    if (currentRole.value == role) return;
    currentRole.value = role;
    await fetchMyOrders(refresh: true);
  }

  /// 切换状态过滤并重新拉取
  Future<void> filterByStatus(OrderStatus? status) async {
    currentStatusFilter.value = status;
    await fetchMyOrders(refresh: true);
  }

  /// 加载或刷新订单列表
  Future<void> fetchMyOrders({
    String? role,
    OrderStatus? status,
    bool refresh = true,
  }) async {
    if (role != null) currentRole.value = role;
    if (status != null) currentStatusFilter.value = status;

    if (refresh) {
      currentPage.value = 1;
      hasMore.value = true;
      loading.value = true;
      resetError();
    }

    // 自增序号：刷新会作废所有在途的 loadMore 响应
    final int requestId = ++_listRequestSeq;
    final int page = currentPage.value;

    try {
      final res = await _orderApi.getMyOrders(
        role: currentRole.value,
        status: currentStatusFilter.value?.code,
        page: page,
        size: AppConfig.orderPageSize,
      );

      if (_isStale(requestId)) return;

      if (res.isSuccess && res.data != null) {
        errorMessage.value = '';
        final pageData = res.data!;
        if (refresh) {
          orders.assignAll(pageData.records);
        } else {
          orders.addAll(pageData.records);
        }

        if (page >= pageData.pages || pageData.records.isEmpty) {
          hasMore.value = false;
        }
      } else {
        errorMessage.value = res.message;
        if (!refresh) currentPage.value = page - 1; // 失败回滚页码
      }
    } catch (e, stack) {
      if (_isStale(requestId)) return;
      debugPrint('[OrderController] fetchMyOrders page=$page error: $e\n$stack');
      errorMessage.value = describeApiError(e, fallback: '加载订单失败');
      if (!refresh) currentPage.value = page - 1; // 失败回滚页码
    } finally {
      if (!_isStale(requestId)) {
        loading.value = false;
        isMoreLoading.value = false;
      }
    }
  }

  /// 加载下一页
  Future<void> loadMoreOrders() async {
    if (loading.value || isMoreLoading.value || !hasMore.value) return;
    isMoreLoading.value = true;
    currentPage.value++;
    await fetchMyOrders(refresh: false);
  }

  /// 查询特定订单详情
  Future<OrderVO?> fetchOrderDetail(String id) async {
    loading.value = true;
    resetError();

    try {
      final res = await _orderApi.getOrderDetail(id);
      if (_closed) return null; // 控制器已关闭：丢弃迟到响应
      if (res.isSuccess && res.data != null) {
        currentOrder.value = res.data;
        _syncOrderInList(res.data!);
        return res.data;
      } else {
        errorMessage.value = res.message;
        return null;
      }
    } catch (e, stack) {
      debugPrint('[OrderController] fetchOrderDetail error: $e\n$stack');
      errorMessage.value = describeApiError(e, fallback: '获取订单详情失败');
      return null;
    } finally {
      loading.value = false;
    }
  }

  /// 创建订单 (买家发起)
  Future<OrderVO?> createOrder({
    required String goodsId,
    String? meetLocation,
    String? buyerMessage,
  }) async {
    loading.value = true;
    resetError();

    try {
      final res = await _orderApi.createOrder(
        goodsId: goodsId,
        meetLocation: meetLocation,
        buyerMessage: buyerMessage,
      );
      if (_closed) return null; // 控制器已关闭：丢弃迟到响应

      if (res.isSuccess && res.data != null) {
        final newOrder = res.data!;
        currentOrder.value = newOrder;
        if (currentRole.value == 'BUYER') {
          orders.insert(0, newOrder);
        }
        return newOrder;
      } else {
        errorMessage.value = res.message;
        return null;
      }
    } catch (e, stack) {
      debugPrint('[OrderController] createOrder error: $e\n$stack');
      errorMessage.value = describeApiError(e, fallback: '创建订单失败');
      return null;
    } finally {
      loading.value = false;
    }
  }

  /// 卖家接单确认 (WAIT_SELLER_CONFIRM -> WAIT_MEET)
  Future<bool> confirmOrder(String id) async {
    loading.value = true;
    resetError();

    try {
      final res = await _orderApi.confirmOrder(id);
      if (_closed) return false; // 控制器已关闭：丢弃迟到响应
      if (res.isSuccess && res.data != null) {
        final updated = res.data!;
        currentOrder.value = updated;
        _syncOrderInList(updated);
        return true;
      } else {
        errorMessage.value = res.message;
        return false;
      }
    } catch (e, stack) {
      debugPrint('[OrderController] confirmOrder error: $e\n$stack');
      errorMessage.value = describeApiError(e, fallback: '确认接单失败');
      return false;
    } finally {
      loading.value = false;
    }
  }

  /// 取消订单 (买家或卖家)
  Future<bool> cancelOrder(String id, String reason) async {
    loading.value = true;
    resetError();

    try {
      final res = await _orderApi.cancelOrder(id: id, cancelReason: reason);
      if (_closed) return false; // 控制器已关闭：丢弃迟到响应
      if (res.isSuccess && res.data != null) {
        final updated = res.data!;
        currentOrder.value = updated;
        _syncOrderInList(updated);
        return true;
      } else {
        errorMessage.value = res.message;
        return false;
      }
    } catch (e, stack) {
      debugPrint('[OrderController] cancelOrder error: $e\n$stack');
      errorMessage.value = describeApiError(e, fallback: '取消订单失败');
      return false;
    } finally {
      loading.value = false;
    }
  }

  /// 完成交易 (WAIT_MEET -> COMPLETED)
  Future<bool> completeOrder(String id) async {
    loading.value = true;
    resetError();

    try {
      final res = await _orderApi.completeOrder(id);
      if (_closed) return false; // 控制器已关闭：丢弃迟到响应
      if (res.isSuccess && res.data != null) {
        final updated = res.data!;
        currentOrder.value = updated;
        _syncOrderInList(updated);
        return true;
      } else {
        errorMessage.value = res.message;
        return false;
      }
    } catch (e, stack) {
      debugPrint('[OrderController] completeOrder error: $e\n$stack');
      errorMessage.value = describeApiError(e, fallback: '完成交易失败');
      return false;
    } finally {
      loading.value = false;
    }
  }

  /// 本地列表缓存同步
  void _syncOrderInList(OrderVO updated) {
    if (_closed) return;
    final index = orders.indexWhere((item) => item.id == updated.id);
    if (index != -1) {
      orders[index] = updated;
    }
  }
}
