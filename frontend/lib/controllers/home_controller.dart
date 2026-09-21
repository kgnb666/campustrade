import 'package:dio/dio.dart';
import 'package:get/get.dart';

import '../api/order_api.dart';
import '../controllers/auth_controller.dart';
import '../models/goods_model.dart';
import '../models/order_summary.dart';
import '../services/goods_service.dart';
import '../utils/api_error.dart';
import '../utils/app_logger.dart';

/// 首页状态控制器（"我的待办" + "最新商品"）
///
/// <h2>为什么首页需要自己的控制器</h2>
/// 首页的两块内容（待办汇总、最新商品）与集市页/我的订单页的两块列表是**不同的请求与状态**：
/// 集市页的 `GoodsController` 持有分类、搜索关键词、分页游标；"我的订单"的
/// `OrderController` 持有视角与状态筛选。如果首页复用它们中的任何一个：
/// 要么把首页的 6 条商品塞进分页状态机（用户再进集市就会看到"第 1 页只有 6 条"的怪状态），
/// 要么首页的错误信息串到订单列表页（参见 `lib/utils/page_controller_scope.dart` 记录的实测缺陷）。
/// 因此这里用独立实例，只做两件事，并遵守项目既有约定：
///
/// 1. 失败不静默：写入 [todoErrorMessage] / [latestErrorMessage] 供页面渲染"加载失败 + 重试"；
/// 2. 每次请求带自增序号 + CancelToken：刷新连点时旧请求被取消，迟到响应整份丢弃；
/// 3. 控制器关闭（[onClose]）后丢弃所有迟到写入。
///
/// 未登录时**不请求**待办接口（它按定义只返回"我自己的"数据，未登录必然 401）：
/// 直接清空并交给页面隐藏整个待办区块。
class HomeController extends GetxController {
  /// 首页"最新商品"展示条数。
  static const int latestGoodsSize = 6;

  final OrderApi _orderApi;
  final GoodsService _goodsService;

  HomeController({OrderApi? orderApi, GoodsService? goodsService})
      : _orderApi = orderApi ?? OrderApi(),
        _goodsService = goodsService ?? GoodsService();

  // ==========================================================================
  // 我的待办
  // ==========================================================================

  /// 待办汇总；null 表示"未登录 / 尚未取到"（页面用 [OrderTodoSummary.empty] 渲染 0）
  final Rxn<OrderTodoSummary> todoSummary = Rxn<OrderTodoSummary>();

  /// 待办加载中
  final RxBool todoLoading = false.obs;

  /// 待办加载失败原因（空字符串表示正常）
  final RxString todoErrorMessage = ''.obs;

  /// 待办是否加载失败（页面据此显示"待办加载失败，点击重试"）
  bool get hasTodoError => todoErrorMessage.isNotEmpty;

  // ==========================================================================
  // 最新商品
  // ==========================================================================

  /// 最新在售商品（后端已按 created_time 倒序，这里只取前 [latestGoodsSize] 条）
  final RxList<GoodsItemModel> latestGoods = <GoodsItemModel>[].obs;

  /// 最新商品加载中
  final RxBool latestLoading = false.obs;

  /// 最新商品加载失败原因（空字符串表示正常）
  final RxString latestErrorMessage = ''.obs;

  /// 最新商品是否加载失败
  bool get hasLatestError => latestErrorMessage.isNotEmpty;

  // ==========================================================================
  // 内部状态
  // ==========================================================================

  /// 待办请求序号
  int _todoRequestSeq = 0;

  /// 最新商品请求序号
  int _latestRequestSeq = 0;

  /// 待办请求取消令牌（新请求发起时取消上一个仍在途的请求）
  CancelToken? _todoCancelToken;

  /// 最新商品请求取消令牌
  CancelToken? _latestCancelToken;

  /// 控制器是否已关闭（关闭后丢弃所有迟到响应）
  bool _closed = false;

  @override
  void onInit() {
    super.onInit();
    loadAll();
  }

  @override
  void onClose() {
    _closed = true;
    _todoCancelToken?.cancel('HomeController closed');
    _latestCancelToken?.cancel('HomeController closed');
    _todoCancelToken = null;
    _latestCancelToken = null;
    super.onClose();
  }

  /// 取当前登录态。
  ///
  /// 用"查不到就当作未登录"而不是 `Get.find`：首页控制器可能在只有页面、
  /// 没有全局 AuthController 的环境里被构造（单测直接驱动控制器、页面被非路由方式嵌入），
  /// 那种情况下抛异常会让整个首页白屏，而未登录本来就是合法状态。
  AuthController? get _auth =>
      Get.isRegistered<AuthController>() ? Get.find<AuthController>() : null;

  /// 当前是否已登录
  bool get isLoggedIn => _auth?.isLoggedIn.value ?? false;

  /// 同时加载待办与最新商品（首页首帧、下拉刷新、错误重试都走这里）
  Future<void> loadAll() async {
    await Future.wait<void>([
      loadTodoSummary(),
      loadLatestGoods(),
    ]);
  }

  /// 加载"我的待办"汇总。
  ///
  /// 未登录时不发请求：该接口只返回当前用户自己的数据，未登录调用必然 401，
  /// 那会让未登录用户看到一个莫名其妙的"待办加载失败"。
  Future<void> loadTodoSummary() async {
    if (!isLoggedIn) {
      _todoCancelToken?.cancel('not logged in');
      todoSummary.value = null;
      todoLoading.value = false;
      todoErrorMessage.value = '';
      return;
    }

    todoLoading.value = true;
    todoErrorMessage.value = '';

    final int requestId = ++_todoRequestSeq;

    _todoCancelToken?.cancel('superseded by a newer todo summary request');
    final CancelToken cancelToken = CancelToken();
    _todoCancelToken = cancelToken;

    try {
      final res = await _orderApi.getTodoSummary(cancelToken: cancelToken);
      if (_isStaleTodo(requestId)) return;

      if (res.isSuccess && res.data != null) {
        todoSummary.value = res.data;
      } else {
        todoErrorMessage.value =
            res.message.isEmpty ? '待办加载失败' : res.message;
        AppLogger.error('[HomeController] loadTodoSummary 业务失败: ${res.message}');
      }
    } catch (e, stack) {
      if (_isStaleTodo(requestId)) return;
      todoErrorMessage.value = describeApiError(e, fallback: '待办加载失败');
      AppLogger.error('[HomeController] loadTodoSummary error', error: e, stackTrace: stack);
    } finally {
      if (!_isStaleTodo(requestId)) {
        todoLoading.value = false;
      }
    }
  }

  /// 加载"最新商品"（最新 6 个在售商品）。
  Future<void> loadLatestGoods() async {
    latestLoading.value = true;
    latestErrorMessage.value = '';

    final int requestId = ++_latestRequestSeq;

    _latestCancelToken?.cancel('superseded by a newer latest goods request');
    final CancelToken cancelToken = CancelToken();
    _latestCancelToken = cancelToken;

    try {
      final res = await _goodsService.getGoodsList(
        page: 1,
        size: latestGoodsSize,
        cancelToken: cancelToken,
      );
      if (_isStaleLatest(requestId)) return;

      final List<GoodsItemModel> items = res['items'] as List<GoodsItemModel>;
      latestGoods.assignAll(items);
    } catch (e, stack) {
      if (_isStaleLatest(requestId)) return;
      latestErrorMessage.value = describeApiError(e, fallback: '最新商品加载失败');
      AppLogger.error('[HomeController] loadLatestGoods error', error: e, stackTrace: stack);
    } finally {
      if (!_isStaleLatest(requestId)) {
        latestLoading.value = false;
      }
    }
  }

  /// 重试待办（页面错误态上的"点击重试"）
  Future<void> retryTodo() => loadTodoSummary();

  /// 重试最新商品
  Future<void> retryLatestGoods() => loadLatestGoods();

  bool _isStaleTodo(int requestId) => _closed || requestId != _todoRequestSeq;

  bool _isStaleLatest(int requestId) => _closed || requestId != _latestRequestSeq;
}
