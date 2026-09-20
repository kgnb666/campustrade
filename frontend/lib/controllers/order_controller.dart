import 'package:dio/dio.dart';
import 'package:get/get.dart';
import '../config/app_config.dart';
import '../api/order_api.dart';
import '../models/order.dart';
import '../utils/api_error.dart';
import '../utils/app_logger.dart';

/// 订单模块状态控制器 (GetX)
/// 负责管理我的订单列表、订单详情、流转操作以及响应式 loading/error 状态
///
/// 阶段 6 起列表分页遵守统一约定：
/// - 每次请求带自增 requestId，响应回来若已不是最新请求则整份丢弃
///   （避免"下拉刷新 + 上拉加载"并发时出现"新列表 + 旧页追加"的重复/错序）；
/// - 加载更多失败回滚页码，否则下一次上拉会永久跳过该页；
/// - 控制器关闭后丢弃所有迟到写入。
///
/// 页面作用域：本控制器**不再被多个页面共用**。三个用它的页面各自持有独立实例，
/// 用 tag 区分（"我的订单"用 [tagMyOrders]、商品详情下单用 [tagCreate]、
/// 订单详情用无 tag 的默认实例）；否则任一处写入的 errorMessage / currentOrder
/// 会串到另一处（详见 `lib/utils/page_controller_scope.dart` 的说明）。
class OrderController extends GetxController {
  /// "我的订单"列表页专属实例
  static const String tagMyOrders = 'OrderController@my-orders';

  /// 商品详情页"立即购买"下单专用实例
  static const String tagCreate = 'OrderController@create-from-goods';

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

  /// 详情请求序号：控制器跨页面共享，切换订单时需作废旧详情响应（否则会出现"串单"）
  int _detailRequestSeq = 0;

  /// 列表请求的取消令牌：新请求发起时取消上一个仍在途的请求。
  ///
  /// 仅靠请求序号只能"丢弃迟到响应"，旧请求仍会真实占满一次往返（切标签、切筛选
  /// 连点时会同时挂着好几个真实请求）。这里用 CancelToken 让旧请求真正中断。
  CancelToken? _listCancelToken;

  /// 详情请求的取消令牌（同上）
  CancelToken? _detailCancelToken;

  /// 控制器是否已关闭（关闭后丢弃迟到响应）
  bool _closed = false;

  @override
  void onClose() {
    _closed = true;
    // 离开页面即释放：把仍在途的列表/详情请求真正取消掉，不再等它们跑完
    _listCancelToken?.cancel('OrderController closed');
    _detailCancelToken?.cancel('OrderController closed');
    _listCancelToken = null;
    _detailCancelToken = null;
    super.onClose();
  }

  bool _isStale(int requestId) => _closed || requestId != _listRequestSeq;

  bool _isStaleDetail(int requestId) => _closed || requestId != _detailRequestSeq;

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

    // 只有最新一次列表请求的结果有意义：取消上一个仍在途的请求
    _listCancelToken?.cancel('superseded by a newer order list request');
    final CancelToken cancelToken = CancelToken();
    _listCancelToken = cancelToken;

    try {
      final res = await _orderApi.getMyOrders(
        role: currentRole.value,
        status: currentStatusFilter.value?.code,
        page: page,
        size: AppConfig.orderPageSize,
        cancelToken: cancelToken,
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
        AppLogger.error('[OrderController] fetchMyOrders 业务失败 page=$page message=${res.message}');
        if (!refresh) currentPage.value = page - 1; // 失败回滚页码
      }
    } catch (e, stack) {
      if (_isStale(requestId)) return;
      AppLogger.error('[OrderController] fetchMyOrders page=$page error', error: e, stackTrace: stack);
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
    // 详情请求同样需要请求序号：controller 是跨页面共享的（我的订单 / 订单详情 / 商品详情都要用它），
    // 从订单 A 进详情后立刻返回再进订单 B 时，A 的迟到响应会把 currentOrder 覆盖成 A 的数据（"串单"），
    // 而页面紧接着还会读共享的 currentOrder 去拉评价状态，于是拿到的是错误订单的评价状态。
    final int detailId = ++_detailRequestSeq;
    loading.value = true;
    resetError();

    // 切换订单时取消上一个仍在途的详情请求（否则会白跑一次并可能覆盖 currentOrder）
    _detailCancelToken?.cancel('superseded by a newer order detail request');
    final CancelToken cancelToken = CancelToken();
    _detailCancelToken = cancelToken;

    try {
      final res = await _orderApi.getOrderDetail(id, cancelToken: cancelToken);
      if (_closed || _isStaleDetail(detailId)) return null; // 控制器已关闭或已有更新的详情请求：丢弃
      if (res.isSuccess && res.data != null) {
        currentOrder.value = res.data;
        _syncOrderInList(res.data!);
        return res.data;
      } else {
        errorMessage.value = res.message;
        AppLogger.error('[OrderController] fetchOrderDetail id=$id 业务失败 message=${res.message}');
        return null;
      }
    } catch (e, stack) {
      if (_closed || _isStaleDetail(detailId)) return null;
      AppLogger.error('[OrderController] fetchOrderDetail id=$id error', error: e, stackTrace: stack);
      errorMessage.value = describeApiError(e, fallback: '获取订单详情失败');
      return null;
    } finally {
      if (!_closed && !_isStaleDetail(detailId)) {
        loading.value = false;
      }
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
        AppLogger.error('[OrderController] createOrder 失败: ${res.message}');
        return null;
      }
    } catch (e, stack) {
      AppLogger.error('[OrderController] createOrder error', error: e, stackTrace: stack);
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
        AppLogger.error('[OrderController] confirmOrder id=$id 失败: ${res.message}');
        return false;
      }
    } catch (e, stack) {
      AppLogger.error('[OrderController] confirmOrder id=$id error', error: e, stackTrace: stack);
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
        AppLogger.error('[OrderController] cancelOrder id=$id 失败: ${res.message}');
        return false;
      }
    } catch (e, stack) {
      AppLogger.error('[OrderController] cancelOrder id=$id error', error: e, stackTrace: stack);
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
        AppLogger.error('[OrderController] completeOrder id=$id 失败: ${res.message}');
        return false;
      }
    } catch (e, stack) {
      AppLogger.error('[OrderController] completeOrder id=$id error', error: e, stackTrace: stack);
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
