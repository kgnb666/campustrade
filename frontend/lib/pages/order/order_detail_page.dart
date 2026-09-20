import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:get/get.dart';
import '../../controllers/auth_controller.dart';
import '../../controllers/order_controller.dart';
import '../../controllers/review_controller.dart';
import '../../models/order.dart';
import '../../models/review.dart';
import '../../utils/name_utils.dart';
import '../../widgets/goods_thumbnail.dart';
import '../review/create_review_sheet.dart';

/// 订单详情浏览页面
/// 展示订单全状态时间线、商品交易快照、买卖双方脱敏资料、约定面交地点与留言
class OrderDetailPage extends StatefulWidget {
  const OrderDetailPage({super.key});

  @override
  State<OrderDetailPage> createState() => _OrderDetailPageState();
}

class _OrderDetailPageState extends State<OrderDetailPage> {
  late final OrderController _orderController;
  late final ReviewController _reviewController;
  String? _orderId;

  @override
  void initState() {
    super.initState();
    _orderController = Get.isRegistered<OrderController>()
        ? Get.find<OrderController>()
        : Get.put(OrderController());
    _reviewController = Get.isRegistered<ReviewController>()
        ? Get.find<ReviewController>()
        : Get.put(ReviewController());

    final args = Get.arguments;
    if (args != null) {
      // 订单 ID 保持字符串原样，避免 Web 端 int 精度截断
      _orderId = args.toString();
    }

    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_orderId != null) {
        _orderController.fetchOrderDetail(_orderId!).then((_) {
          final ord = _orderController.currentOrder.value;
          if (ord != null && ord.orderStatus == OrderStatus.completed) {
            _reviewController.fetchOrderReviewStatus(ord.id);
          }
        });
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Scaffold(
      appBar: AppBar(
        title: const Text('订单详情'),
        centerTitle: true,
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            tooltip: '刷新详情',
            onPressed: () {
              if (_orderId != null) {
                _orderController.fetchOrderDetail(_orderId!);
              }
            },
          ),
        ],
      ),
      body: Obx(() {
        // 1. 加载中
        if (_orderController.loading.value &&
            _orderController.currentOrder.value == null) {
          return const Center(child: CircularProgressIndicator());
        }

        // 2. 错误处理
        if (_orderController.hasError &&
            _orderController.currentOrder.value == null) {
          return _buildErrorState(theme);
        }

        final order = _orderController.currentOrder.value;
        if (order == null) {
          return _buildNotFoundState(theme);
        }

        // 3. 正常详情展示
        return RefreshIndicator(
          onRefresh: () async {
            if (_orderId != null) {
              await _orderController.fetchOrderDetail(_orderId!);
              final ord = _orderController.currentOrder.value;
              if (ord != null && ord.orderStatus == OrderStatus.completed) {
                await _reviewController.fetchOrderReviewStatus(ord.id);
              }
            }
          },
          child: SingleChildScrollView(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
            child: Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 720),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    // A. 状态时间线组件
                    _buildTimelineCard(order, theme),
                    const SizedBox(height: 14),

                    // Stage 5-E: 交易评价与互评状态卡片 (当订单已完成时展示)
                    if (order.orderStatus == OrderStatus.completed) ...[
                      _buildReviewCard(order, theme),
                      const SizedBox(height: 14),
                    ],

                    // B. 商品交易快照卡片 (防篡改)
                    _buildGoodsSnapshotCard(order, theme),
                    const SizedBox(height: 14),

                    // C. 约定面交与留言卡片
                    _buildMeetAndMessageCard(order, theme),
                    const SizedBox(height: 14),

                    // D. 交易双方脱敏资料卡片
                    _buildPartiesCard(order, theme),
                    const SizedBox(height: 14),

                    // E. 订单元数据信息卡片 (订单号、各时间节点)
                    _buildOrderMetaCard(order, theme),
                    const SizedBox(height: 24),
                  ],
                ),
              ),
            ),
          ),
        );
      }),
      bottomNavigationBar: Obx(() =>
          _buildBottomActionBar(_orderController.currentOrder.value, theme) ??
          const SizedBox.shrink()),
    );
  }

  /// 底部交易状态操作栏 (严格根据 orderStatus 动态渲染，终态保护不渲染任何操作按钮)
  Widget? _buildBottomActionBar(OrderVO? order, ThemeData theme) {
    if (order == null) return null;

    final status = order.orderStatus;

    final auth =
        Get.isRegistered<AuthController>() ? Get.find<AuthController>() : null;
    final currentUserId = auth?.currentUser.value?.id;
    // 身份只依据服务端返回的 buyerId/sellerId 与当前登录用户比较。
    // 绝不引入 _orderController.currentRole（那是订单列表页的标签页选项，跨页面共享），
    // 否则买家在"我的卖出"标签下返回后打开自己的买家订单，会被误判成卖家并看到
    // "确认接单/去评价"这类卖家操作。
    final isSeller = currentUserId != null &&
        (order.sellerId == currentUserId || order.seller?.id == currentUserId);

    // 未知状态：只读展示，不渲染任何操作按钮
    // （服务端不认得的流转，前端更不能给出口子；已知状态的判定全部来自枚举）
    if (status.isReadOnly) {
      return null;
    }

    // 终态与评价状态处理
    if (status == OrderStatus.cancelled) {
      return null;
    }
    if (status == OrderStatus.completed) {
      final reviewStatus = _reviewController.orderReviewStatusMap[order.id];
      if (reviewStatus != null && reviewStatus.canReview) {
        return Container(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
          decoration: BoxDecoration(
            color: Colors.white,
            boxShadow: [
              BoxShadow(
                color: Colors.black.withValues(alpha: 0.05),
                blurRadius: 10,
                offset: const Offset(0, -2),
              ),
            ],
          ),
          child: SafeArea(
            top: false,
            child: ElevatedButton.icon(
              style: ElevatedButton.styleFrom(
                backgroundColor: theme.colorScheme.primary,
                foregroundColor: Colors.white,
                padding: const EdgeInsets.symmetric(vertical: 13),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(10),
                ),
              ),
              icon: const Icon(Icons.rate_review, size: 18),
              label: const Text(
                '去评价',
                style: TextStyle(fontSize: 15, fontWeight: FontWeight.bold),
              ),
              onPressed: () => CreateReviewSheet.show(
                context,
                order: order,
                isBuyer: !isSeller,
                onSuccess: () =>
                    _reviewController.fetchOrderReviewStatus(order.id),
              ),
            ),
          ),
        );
      }
      return null;
    }

    final List<Widget> actionButtons = [];

    // 取消订单按钮 (WAIT_SELLER_CONFIRM 或 WAIT_MEET 时可用)
    if (status == OrderStatus.waitSellerConfirm ||
        status == OrderStatus.waitMeet) {
      actionButtons.add(
        Expanded(
          child: OutlinedButton(
            style: OutlinedButton.styleFrom(
              foregroundColor: Colors.red.shade700,
              side: BorderSide(color: Colors.red.shade300),
              padding: const EdgeInsets.symmetric(vertical: 13),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(10),
              ),
            ),
            onPressed: () => _showCancelOrderDialog(context, order),
            child: const Text('取消订单'),
          ),
        ),
      );
    }

    // 卖家确认接单 (仅 WAIT_SELLER_CONFIRM 且卖家身份可见)
    if (status == OrderStatus.waitSellerConfirm && isSeller) {
      if (actionButtons.isNotEmpty) {
        actionButtons.add(const SizedBox(width: 12));
      }
      actionButtons.add(
        Expanded(
          child: ElevatedButton(
            style: ElevatedButton.styleFrom(
              backgroundColor: theme.colorScheme.primary,
              foregroundColor: Colors.white,
              padding: const EdgeInsets.symmetric(vertical: 13),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(10),
              ),
            ),
            onPressed: () => _handleConfirmOrder(order),
            child: const Text(
              '确认接单',
              style: TextStyle(fontWeight: FontWeight.bold),
            ),
          ),
        ),
      );
    }

    // 买卖双方完成交易 (仅 WAIT_MEET 时可见)
    if (status == OrderStatus.waitMeet) {
      if (actionButtons.isNotEmpty) {
        actionButtons.add(const SizedBox(width: 12));
      }
      actionButtons.add(
        Expanded(
          child: ElevatedButton(
            style: ElevatedButton.styleFrom(
              backgroundColor: Colors.green.shade700,
              foregroundColor: Colors.white,
              padding: const EdgeInsets.symmetric(vertical: 13),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(10),
              ),
            ),
            onPressed: () => _showCompleteConfirmDialog(context, order),
            child: const Text(
              '完成交易',
              style: TextStyle(fontWeight: FontWeight.bold),
            ),
          ),
        ),
      );
    }

    if (actionButtons.isEmpty) return null;

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      decoration: BoxDecoration(
        color: theme.colorScheme.surface,
        boxShadow: [
          BoxShadow(
            color: Colors.black.withAlpha(15),
            blurRadius: 6,
            offset: const Offset(0, -2),
          ),
        ],
      ),
      child: SafeArea(
        child: Row(children: actionButtons),
      ),
    );
  }

  /// 卖家接单确认
  Future<void> _handleConfirmOrder(OrderVO order) async {
    final success = await _orderController.confirmOrder(order.id);
    if (success) {
      Get.snackbar(
        '接单成功',
        '已确认接单，请及时与买家约定线下交付',
        snackPosition: SnackPosition.BOTTOM,
      );
    } else {
      Get.snackbar(
        '接单失败',
        _orderController.errorMessage.value.isNotEmpty
            ? _orderController.errorMessage.value
            : '操作失败，请稍后重试',
        snackPosition: SnackPosition.BOTTOM,
      );
    }
  }

  /// 完成交易二次确认弹窗
  void _showCompleteConfirmDialog(BuildContext context, OrderVO order) {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('完成交易确认'),
        content: const Text('确认双方已经完成线下面交？'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('取消'),
          ),
          ElevatedButton(
            style: ElevatedButton.styleFrom(
              backgroundColor: Colors.green.shade700,
              foregroundColor: Colors.white,
            ),
            onPressed: () async {
              Navigator.pop(ctx);
              final success = await _orderController.completeOrder(order.id);
              if (success) {
                _reviewController.fetchOrderReviewStatus(order.id);
                Get.snackbar(
                  '交易完成',
                  '双方线下面交已达成，交易顺利结束',
                  snackPosition: SnackPosition.BOTTOM,
                );
              } else {
                Get.snackbar(
                  '操作失败',
                  _orderController.errorMessage.value.isNotEmpty
                      ? _orderController.errorMessage.value
                      : '完成交易失败，请稍后重试',
                  snackPosition: SnackPosition.BOTTOM,
                );
              }
            },
            child: const Text('确认完成'),
          ),
        ],
      ),
    );
  }

  /// 取消订单弹窗 (必须填写 cancelReason)
  ///
  /// 输入控制器由弹窗自身的 State 持有并 dispose（原先在弹窗外创建且从不释放）。
  void _showCancelOrderDialog(BuildContext context, OrderVO order) {
    showDialog(
      context: context,
      builder: (ctx) => _CancelOrderDialog(
        onSubmit: (reason) => _handleCancelOrder(order, reason),
      ),
    );
  }

  /// 执行取消订单并给出结果提示（供取消弹窗回调）
  Future<void> _handleCancelOrder(OrderVO order, String reason) async {
    final success = await _orderController.cancelOrder(order.id, reason);
    if (success) {
      Get.snackbar(
        '订单已取消',
        '订单已成功终止并已更新流转状态',
        snackPosition: SnackPosition.BOTTOM,
      );
    } else {
      Get.snackbar(
        '取消失败',
        _orderController.errorMessage.value.isNotEmpty
            ? _orderController.errorMessage.value
            : '取消订单失败，请稍后重试',
        snackPosition: SnackPosition.BOTTOM,
      );
    }
  }

  /// 状态时间线卡片
  Widget _buildTimelineCard(OrderVO order, ThemeData theme) {
    final isCancelled = order.orderStatus == OrderStatus.cancelled;
    final isUnknown = order.orderStatus.isReadOnly;

    return Card(
      elevation: 1.5,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      child: Padding(
        padding: const EdgeInsets.all(18.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  isCancelled
                      ? Icons.cancel_outlined
                      : (isUnknown
                          ? Icons.help_outline
                          : Icons.timeline_outlined),
                  color: isCancelled
                      ? Colors.red
                      : (isUnknown
                          ? Colors.grey
                          : theme.colorScheme.primary),
                  size: 20,
                ),
                const SizedBox(width: 8),
                Text(
                  '订单流转进度',
                  style: theme.textTheme.titleMedium?.copyWith(
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const Spacer(),
                _buildStatusChip(order.orderStatus, order.statusText),
              ],
            ),
            const Divider(height: 24),

            if (isCancelled) ...[
              // 取消流转时间线展示
              _buildCancelledTimeline(order, theme),
            ] else if (isUnknown) ...[
              // 未知状态：不渲染任何流转步骤（此前会错误高亮"待卖家确认"）
              _buildUnknownStatusNotice(order, theme),
            ] else ...[
              // 正常三步时间线展示
              _buildNormalTimeline(order, theme),
            ],
          ],
        ),
      ),
    );
  }

  /// 未知订单状态提示：只读展示服务端原文，不渲染任何流转步骤或操作入口。
  ///
  /// 此前未知状态会被静默回落到"待卖家确认"，于是时间线高亮第一步、卖家还会看到
  /// "确认接单"按钮——但那是个服务端根本不接受的流转。
  Widget _buildUnknownStatusNotice(OrderVO order, ThemeData theme) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(Icons.info_outline, size: 18, color: Colors.grey.shade600),
        const SizedBox(width: 8),
        Expanded(
          child: Text(
            '当前订单状态为「${order.statusText}」，本版本暂不支持对该状态的展示与操作。'
            '请稍后刷新，或联系平台客服核实。',
            style: TextStyle(fontSize: 13, height: 1.5, color: Colors.grey.shade800),
          ),
        ),
      ],
    );
  }

  /// 正常流转时间线 (待确认 -> 待面交 -> 已完成)
  Widget _buildNormalTimeline(OrderVO order, ThemeData theme) {
    final currentStatus = order.orderStatus;

    // 步骤激活索引: 0=WAIT_SELLER_CONFIRM, 1=WAIT_MEET, 2=COMPLETED
    int activeStep = 0;
    if (currentStatus == OrderStatus.waitMeet) {
      activeStep = 1;
    } else if (currentStatus == OrderStatus.completed) {
      activeStep = 2;
    }

    final steps = [
      {
        'title': '待卖家确认',
        'subtitle': order.createdTime ?? '买家已下单',
        'icon': Icons.assignment_outlined,
      },
      {
        'title': '待面交',
        'subtitle': order.confirmedTime ?? '双方约定面交',
        'icon': Icons.handshake_outlined,
      },
      {
        'title': '已完成',
        'subtitle': order.completedTime ?? '交易达成',
        'icon': Icons.check_circle_outline,
      },
    ];

    return Column(
      children: List.generate(steps.length, (index) {
        final step = steps[index];
        final isDone = index < activeStep;
        final isCurrent = index == activeStep;
        final isPending = index > activeStep;
        final isLast = index == steps.length - 1;

        Color dotColor;
        Color titleColor;
        if (isDone) {
          dotColor = Colors.green;
          titleColor = Colors.black87;
        } else if (isCurrent) {
          dotColor = theme.colorScheme.primary;
          titleColor = theme.colorScheme.primary;
        } else {
          dotColor = Colors.grey.shade300;
          titleColor = Colors.grey.shade500;
        }

        return IntrinsicHeight(
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // 节点指示器与连接线
              Column(
                children: [
                  Container(
                    width: 28,
                    height: 28,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      color: isDone || isCurrent
                          ? dotColor.withAlpha(30)
                          : Colors.grey.shade100,
                      border: Border.all(
                        color: dotColor,
                        width: isCurrent ? 2.5 : 1.5,
                      ),
                    ),
                    child: Icon(
                      isDone
                          ? Icons.check
                          : (step['icon'] as IconData),
                      size: 15,
                      color: dotColor,
                    ),
                  ),
                  if (!isLast)
                    Expanded(
                      child: Container(
                        width: 2,
                        color: isDone ? Colors.green : Colors.grey.shade200,
                        margin: const EdgeInsets.symmetric(vertical: 4),
                      ),
                    ),
                ],
              ),
              const SizedBox(width: 14),

              // 步骤标题与时间文字
              Expanded(
                child: Padding(
                  padding: EdgeInsets.only(bottom: isLast ? 0 : 20.0),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        step['title'] as String,
                        style: TextStyle(
                          fontSize: 14,
                          fontWeight: isCurrent
                              ? FontWeight.bold
                              : (isDone ? FontWeight.w600 : FontWeight.normal),
                          color: titleColor,
                        ),
                      ),
                      const SizedBox(height: 2),
                      Text(
                        step['subtitle'] as String,
                        style: TextStyle(
                          fontSize: 12,
                          color: isPending
                              ? Colors.grey.shade400
                              : Colors.grey.shade600,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        );
      }),
    );
  }

  /// 已取消流转时间线展示
  Widget _buildCancelledTimeline(OrderVO order, ThemeData theme) {
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: Colors.red.shade50.withAlpha(120),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: Colors.red.shade200),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(Icons.info_outline, size: 18, color: Colors.red.shade700),
              const SizedBox(width: 6),
              Text(
                '订单已终止流转',
                style: TextStyle(
                  fontWeight: FontWeight.bold,
                  fontSize: 14,
                  color: Colors.red.shade800,
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          if (order.cancelReason != null && order.cancelReason!.isNotEmpty) ...[
            Text(
              '取消原因: ${order.cancelReason}',
              style: TextStyle(fontSize: 13, color: Colors.red.shade900),
            ),
            const SizedBox(height: 4),
          ],
          if (order.cancelledTime != null)
            Text(
              '取消时间: ${order.cancelledTime}',
              style: TextStyle(fontSize: 12, color: Colors.grey.shade700),
            ),
        ],
      ),
    );
  }

  /// 商品交易快照卡片 (防篡改)
  Widget _buildGoodsSnapshotCard(OrderVO order, ThemeData theme) {
    return Card(
      elevation: 1.5,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.shield_outlined, size: 18, color: Colors.teal),
                const SizedBox(width: 6),
                const Text(
                  '商品交易快照 (防篡改)',
                  style: TextStyle(
                    fontWeight: FontWeight.bold,
                    fontSize: 14,
                    color: Colors.teal,
                  ),
                ),
              ],
            ),
            const Divider(height: 20),
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // 商品快照缩略图
                ClipRRect(
                  borderRadius: BorderRadius.circular(10),
                  child: SizedBox(
                    width: 84,
                    height: 84,
                    child: GoodsThumbnail(
                      imageUrl: order.goodsImageSnapshot,
                      width: 84,
                      height: 84,
                      borderRadius: 0,
                      placeholderIcon: Icons.receipt_long_outlined,
                    ),
                  ),
                ),
                const SizedBox(width: 14),

                // 标题与快照价格
                Expanded(
                  child: SizedBox(
                    height: 84,
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text(
                          order.goodsTitleSnapshot,
                          style: const TextStyle(
                            fontSize: 15,
                            fontWeight: FontWeight.w600,
                            height: 1.3,
                          ),
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                        ),
                        Row(
                          crossAxisAlignment: CrossAxisAlignment.baseline,
                          textBaseline: TextBaseline.alphabetic,
                          children: [
                            Text(
                              '成交价: ¥',
                              style: TextStyle(
                                fontSize: 13,
                                color: Colors.grey.shade700,
                              ),
                            ),
                            Text(
                              order.goodsPriceSnapshot.toStringAsFixed(2),
                              style: TextStyle(
                                fontSize: 20,
                                fontWeight: FontWeight.bold,
                                color: theme.colorScheme.primary,
                              ),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  /// 约定面交与留言卡片
  Widget _buildMeetAndMessageCard(OrderVO order, ThemeData theme) {
    return Card(
      elevation: 1.5,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.place_outlined, size: 18, color: Colors.indigo),
                const SizedBox(width: 6),
                Text(
                  '面交约定与沟通留言',
                  style: theme.textTheme.titleMedium?.copyWith(
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ],
            ),
            const Divider(height: 20),

            // 面交地点
            _buildInfoRow(
              label: '约定地点',
              value: order.meetLocation ?? '未指定地点，双方通过校园面交完成',
              icon: Icons.location_on,
              iconColor: Colors.deepOrange,
            ),
            const SizedBox(height: 12),

            // 买家留言
            _buildInfoRow(
              label: '买家留言',
              value: order.buyerMessage != null && order.buyerMessage!.isNotEmpty
                  ? order.buyerMessage!
                  : '无买家留言',
              icon: Icons.chat_bubble_outline,
              iconColor: Colors.blue,
            ),

            // 卖家回复
            if (order.sellerReply != null && order.sellerReply!.isNotEmpty) ...[
              const SizedBox(height: 12),
              _buildInfoRow(
                label: '卖家回复',
                value: order.sellerReply!,
                icon: Icons.reply_outlined,
                iconColor: Colors.teal,
              ),
            ],
          ],
        ),
      ),
    );
  }

  /// 交易双方脱敏资料卡片
  Widget _buildPartiesCard(OrderVO order, ThemeData theme) {
    return Card(
      elevation: 1.5,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.people_alt_outlined,
                    size: 18, color: Colors.purple),
                const SizedBox(width: 6),
                Text(
                  '交易当事人信息',
                  style: theme.textTheme.titleMedium?.copyWith(
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ],
            ),
            const Divider(height: 20),
            Row(
              children: [
                // 买家方
                Expanded(
                  child: _buildUserSubCard(
                    title: '买家',
                    user: order.buyer,
                    theme: theme,
                    color: Colors.blue,
                  ),
                ),
                const SizedBox(width: 12),
                // 卖家方
                Expanded(
                  child: _buildUserSubCard(
                    title: '卖家',
                    user: order.seller,
                    theme: theme,
                    color: Colors.orange,
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  /// 用户脱敏信息子卡片
  Widget _buildUserSubCard({
    required String title,
    required OrderUserInfo? user,
    required ThemeData theme,
    required MaterialColor color,
  }) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: color.shade50.withAlpha(100),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: color.shade200, width: 0.8),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                decoration: BoxDecoration(
                  color: color.shade700,
                  borderRadius: BorderRadius.circular(4),
                ),
                child: Text(
                  title,
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 11,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 10),
          Row(
            children: [
              CircleAvatar(
                radius: 18,
                backgroundColor: color.shade200,
                backgroundImage: user?.avatar != null && user!.avatar!.isNotEmpty
                    ? NetworkImage(user.avatar!)
                    : null,
                child: user?.avatar == null || user!.avatar!.isEmpty
                    ? Builder(builder: (_) {
                        final name = user?.nickname?.isNotEmpty == true
                            ? user!.nickname!
                            : (user?.username.isNotEmpty == true
                                ? user!.username
                                : title);
                        return Text(
                          name.isNotEmpty ? name[0].toUpperCase() : 'U',
                          style: TextStyle(
                            color: color.shade900,
                            fontWeight: FontWeight.bold,
                          ),
                        );
                      })
                    : null,
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      user?.nickname ?? user?.username ?? '匿名用户',
                      style: const TextStyle(
                        fontSize: 13,
                        fontWeight: FontWeight.bold,
                      ),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                    if (user?.username != null)
                      Text(
                        '@${user!.username}',
                        style: TextStyle(
                          fontSize: 11,
                          color: Colors.grey.shade600,
                        ),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                  ],
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  /// 订单元数据信息卡片 (订单号、时间等)
  Widget _buildOrderMetaCard(OrderVO order, ThemeData theme) {
    return Card(
      elevation: 1.5,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.receipt_outlined,
                    size: 18, color: Colors.grey),
                const SizedBox(width: 6),
                Text(
                  '订单编号与时间节点',
                  style: theme.textTheme.titleMedium?.copyWith(
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ],
            ),
            const Divider(height: 20),

            // 订单号带复制功能
            Row(
              children: [
                const SizedBox(
                  width: 80,
                  child: Text(
                    '业务订单号',
                    style: TextStyle(fontSize: 13, color: Colors.grey),
                  ),
                ),
                Expanded(
                  child: Text(
                    order.orderNo,
                    style: const TextStyle(
                      fontSize: 13,
                      fontFamily: 'monospace',
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
                InkWell(
                  onTap: () {
                    Clipboard.setData(ClipboardData(text: order.orderNo));
                    Get.snackbar(
                      '已复制',
                      '订单号已复制到剪贴板',
                      snackPosition: SnackPosition.BOTTOM,
                      duration: const Duration(seconds: 1),
                    );
                  },
                  child: const Padding(
                    padding: EdgeInsets.all(4.0),
                    child: Icon(Icons.copy, size: 16, color: Colors.grey),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),

            _buildMetaRow('创建时间', order.createdTime ?? '-'),
            if (order.confirmedTime != null) ...[
              const SizedBox(height: 8),
              _buildMetaRow('接单时间', order.confirmedTime!),
            ],
            if (order.completedTime != null) ...[
              const SizedBox(height: 8),
              _buildMetaRow('完成时间', order.completedTime!),
            ],
            if (order.cancelledTime != null) ...[
              const SizedBox(height: 8),
              _buildMetaRow('取消时间', order.cancelledTime!),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildMetaRow(String label, String value) {
    return Row(
      children: [
        SizedBox(
          width: 80,
          child: Text(
            label,
            style: const TextStyle(fontSize: 13, color: Colors.grey),
          ),
        ),
        Expanded(
          child: Text(
            value,
            style: const TextStyle(fontSize: 13, color: Colors.black87),
          ),
        ),
      ],
    );
  }

  Widget _buildInfoRow({
    required String label,
    required String value,
    required IconData icon,
    required Color iconColor,
  }) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(icon, size: 16, color: iconColor),
        const SizedBox(width: 8),
        SizedBox(
          width: 68,
          child: Text(
            label,
            style: const TextStyle(fontSize: 13, color: Colors.grey),
          ),
        ),
        Expanded(
          child: Text(
            value,
            style: const TextStyle(fontSize: 13, color: Colors.black87),
          ),
        ),
      ],
    );
  }

  Widget _buildStatusChip(OrderStatus status, String description) {
    Color textColor;
    Color bgColor;

    switch (status) {
      case OrderStatus.waitSellerConfirm:
        textColor = Colors.orange.shade800;
        bgColor = Colors.orange.shade50;
        break;
      case OrderStatus.waitMeet:
        textColor = Colors.blue.shade800;
        bgColor = Colors.blue.shade50;
        break;
      case OrderStatus.completed:
        textColor = Colors.green.shade800;
        bgColor = Colors.green.shade50;
        break;
      case OrderStatus.cancelled:
        textColor = Colors.grey.shade700;
        bgColor = Colors.grey.shade200;
        break;
      case OrderStatus.unknown:
        // 未知状态：中性配色，文案为服务端原文（见 OrderVO#statusText）
        textColor = Colors.grey.shade800;
        bgColor = Colors.grey.shade100;
        break;
    }

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: bgColor,
        borderRadius: BorderRadius.circular(6),
        border: Border.all(color: textColor.withAlpha(60), width: 0.8),
      ),
      child: Text(
        description,
        style: TextStyle(
          color: textColor,
          fontSize: 12,
          fontWeight: FontWeight.bold,
        ),
      ),
    );
  }

  Widget _buildErrorState(ThemeData theme) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24.0),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.error_outline, size: 64, color: Colors.red.shade300),
            const SizedBox(height: 16),
            Text(
              '加载订单详情失败',
              style: theme.textTheme.titleMedium?.copyWith(
                fontWeight: FontWeight.bold,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              _orderController.errorMessage.value,
              style: TextStyle(fontSize: 13, color: Colors.grey.shade600),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 20),
            ElevatedButton.icon(
              onPressed: () {
                if (_orderId != null) {
                  _orderController.fetchOrderDetail(_orderId!);
                }
              },
              icon: const Icon(Icons.refresh, size: 18),
              label: const Text('点击重试'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildNotFoundState(ThemeData theme) {
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(Icons.search_off_outlined, size: 64, color: Colors.grey.shade400),
          const SizedBox(height: 16),
          const Text('未找到对应订单', style: TextStyle(fontSize: 16)),
          const SizedBox(height: 16),
          ElevatedButton(
            onPressed: () => Get.back(),
            child: const Text('返回列表'),
          ),
        ],
      ),
    );
  }

  /// 交易评价与双方互评卡片 (仅当订单为 COMPLETED 时展示)
  Widget _buildReviewCard(OrderVO order, ThemeData theme) {
    return Obx(() {
      final reviewStatus = _reviewController.orderReviewStatusMap[order.id];
      final isLoading =
          _reviewController.loadingOrderStatus.value && reviewStatus == null;

      final auth =
          Get.isRegistered<AuthController>() ? Get.find<AuthController>() : null;
      final currentUserId = auth?.currentUser.value?.id;
      // 同上：只按服务端的 buyerId/sellerId 判定，不看列表页的标签页选择
      final isSeller = currentUserId != null &&
          (order.sellerId == currentUserId || order.seller?.id == currentUserId);

      return Card(
        elevation: 1,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // 标题行
              Row(
                children: [
                  Icon(Icons.rate_review_outlined,
                      size: 20, color: theme.colorScheme.primary),
                  const SizedBox(width: 8),
                  Text(
                    '交易评价',
                    style: theme.textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  const Spacer(),
                  if (reviewStatus != null && reviewStatus.bothReviewed)
                    Container(
                      padding: const EdgeInsets.symmetric(
                          horizontal: 8, vertical: 2),
                      decoration: BoxDecoration(
                        color: Colors.green.withValues(alpha: 0.1),
                        borderRadius: BorderRadius.circular(6),
                      ),
                      child: const Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Icon(Icons.check_circle,
                              size: 14, color: Colors.green),
                          SizedBox(width: 4),
                          Text(
                            '互评完成',
                            style: TextStyle(
                              fontSize: 12,
                              color: Colors.green,
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                        ],
                      ),
                    ),
                ],
              ),
              const Divider(height: 20),

              // 加载中或未就绪
              if (isLoading || reviewStatus == null)
                const Center(
                  child: Padding(
                    padding: EdgeInsets.symmetric(vertical: 8),
                    child: Text('正在获取评价信息...',
                        style: TextStyle(color: Colors.grey, fontSize: 13)),
                  ),
                )
              else ...[
                // 1. 若可评价，展示去评价入口区域
                if (reviewStatus.canReview) ...[
                  Container(
                    padding: const EdgeInsets.all(12),
                    decoration: BoxDecoration(
                      color: theme.colorScheme.primaryContainer
                          .withValues(alpha: 0.3),
                      borderRadius: BorderRadius.circular(10),
                      border: Border.all(
                          color: theme.colorScheme.primary
                              .withValues(alpha: 0.2)),
                    ),
                    child: Row(
                      children: [
                        Icon(Icons.info_outline,
                            size: 20, color: theme.colorScheme.primary),
                        const SizedBox(width: 10),
                        const Expanded(
                          child: Text(
                            '线下面交已达成，快对本次交易与对方同学发表评价吧！',
                            style: TextStyle(fontSize: 13),
                          ),
                        ),
                        const SizedBox(width: 8),
                        ElevatedButton(
                          style: ElevatedButton.styleFrom(
                            backgroundColor: theme.colorScheme.primary,
                            foregroundColor: Colors.white,
                            padding: const EdgeInsets.symmetric(
                                horizontal: 14, vertical: 8),
                          ),
                          onPressed: () => CreateReviewSheet.show(
                            context,
                            order: order,
                            isBuyer: !isSeller,
                            onSuccess: () =>
                                _reviewController.fetchOrderReviewStatus(order.id),
                          ),
                          child: const Text('去评价'),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 12),
                ],

                // 2. 我的评价展示
                if (reviewStatus.myReview != null) ...[
                  _buildSingleReviewItem(
                    title: '我的评价',
                    review: reviewStatus.myReview!,
                    theme: theme,
                    isMine: true,
                  ),
                  const SizedBox(height: 10),
                ] else if (!reviewStatus.canReview &&
                    reviewStatus.reasonIfNotEligible != null) ...[
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 4),
                    child: Text(
                      reviewStatus.reasonIfNotEligible!,
                      style: const TextStyle(fontSize: 12, color: Colors.grey),
                    ),
                  ),
                ],

                // 3. 对方评价展示
                if (reviewStatus.peerReview != null) ...[
                  _buildSingleReviewItem(
                    title: reviewStatus.isBuyer ? '卖家评价' : '买家评价',
                    review: reviewStatus.peerReview!,
                    theme: theme,
                    isMine: false,
                  ),
                ] else if (reviewStatus.myReview != null) ...[
                  Container(
                    padding: const EdgeInsets.all(10),
                    decoration: BoxDecoration(
                      color: Colors.grey.shade50,
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: Row(
                      children: [
                        Icon(Icons.hourglass_empty,
                            size: 16, color: Colors.grey.shade600),
                        const SizedBox(width: 6),
                        Text(
                          '等待对方评价中...',
                          style: TextStyle(
                              color: Colors.grey.shade600, fontSize: 13),
                        ),
                      ],
                    ),
                  ),
                ],
              ],
            ],
          ),
        ),
      );
    });
  }

  /// 单个评价详情区块 (包含头像昵称、星级、标签、正文内容、发布时间)
  Widget _buildSingleReviewItem({
    required String title,
    required ReviewModel review,
    required ThemeData theme,
    required bool isMine,
  }) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: isMine
            ? Colors.grey.shade50
            : theme.colorScheme.surfaceContainerHighest.withValues(alpha: 0.2),
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: Colors.grey.shade200),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              if (!isMine) ...[
                CircleAvatar(
                  radius: 14,
                  backgroundColor: theme.colorScheme.primaryContainer,
                  backgroundImage: review.displayAvatar != null
                      ? NetworkImage(review.displayAvatar!)
                      : null,
                  child: review.displayAvatar == null
                      ? Text(
                          initialOf(review.displayNickname, '用户'),
                          style: const TextStyle(fontSize: 11),
                        )
                      : null,
                ),
                const SizedBox(width: 8),
              ],
              Text(
                title,
                style: const TextStyle(
                  fontWeight: FontWeight.bold,
                  fontSize: 13,
                ),
              ),
              if (!isMine) ...[
                const SizedBox(width: 4),
                Text(
                  '(${review.displayNickname})',
                  style: TextStyle(fontSize: 12, color: Colors.grey.shade600),
                ),
              ],
              if (review.isAnonymous) ...[
                const SizedBox(width: 6),
                Container(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 5, vertical: 1),
                  decoration: BoxDecoration(
                    color: Colors.grey.shade200,
                    borderRadius: BorderRadius.circular(4),
                  ),
                  child: const Text(
                    '匿名',
                    style: TextStyle(fontSize: 10, color: Colors.black54),
                  ),
                ),
              ],
              const Spacer(),
              // 星级展示
              Row(
                mainAxisSize: MainAxisSize.min,
                children: List.generate(5, (index) {
                  final filled = index < review.score;
                  return Icon(
                    filled ? Icons.star_rounded : Icons.star_outline_rounded,
                    size: 16,
                    color:
                        filled ? Colors.amber.shade600 : Colors.grey.shade300,
                  );
                }),
              ),
            ],
          ),
          if (review.tags.isNotEmpty) ...[
            const SizedBox(height: 8),
            Wrap(
              spacing: 6,
              runSpacing: 4,
              children: review.tags.map((tag) {
                return Container(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                  decoration: BoxDecoration(
                    color: Colors.white,
                    borderRadius: BorderRadius.circular(4),
                    border: Border.all(color: Colors.grey.shade300),
                  ),
                  child: Text(
                    tag,
                    style:
                        TextStyle(fontSize: 11, color: Colors.grey.shade800),
                  ),
                );
              }).toList(),
            ),
          ],
          if (review.content != null && review.content!.isNotEmpty) ...[
            const SizedBox(height: 8),
            Text(
              review.content!,
              style: const TextStyle(fontSize: 13, height: 1.4),
            ),
          ],
          if (review.createdTime != null) ...[
            const SizedBox(height: 6),
            Align(
              alignment: Alignment.centerRight,
              child: Text(
                review.createdTime!,
                style: TextStyle(fontSize: 11, color: Colors.grey.shade500),
              ),
            ),
          ],
        ],
      ),
    );
  }
}

/// 取消订单弹窗（独立 StatefulWidget）
///
/// 输入控制器由弹窗自身持有并在 [State.dispose] 释放；提交时先取值再关闭弹窗，
/// 保证异步回调执行时控制器仍未被销毁。视觉与交互与改造前一致。
class _CancelOrderDialog extends StatefulWidget {
  const _CancelOrderDialog({required this.onSubmit});

  /// 原因校验通过且弹窗关闭后回调（异步执行取消接口）
  final Future<void> Function(String reason) onSubmit;

  @override
  State<_CancelOrderDialog> createState() => _CancelOrderDialogState();
}

class _CancelOrderDialogState extends State<_CancelOrderDialog> {
  final TextEditingController _reasonController = TextEditingController();

  @override
  void dispose() {
    _reasonController.dispose();
    super.dispose();
  }

  Future<void> _confirm() async {
    final reason = _reasonController.text.trim();
    if (reason.isEmpty) {
      Get.snackbar(
        '提示',
        '取消原因不能为空，请填写具体原因',
        snackPosition: SnackPosition.BOTTOM,
      );
      return;
    }
    if (!mounted) return;
    Navigator.pop(context);
    await widget.onSubmit(reason);
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('取消订单'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            '取消订单后将释放商品锁定，该操作不可逆。请输入取消原因：',
            style: TextStyle(fontSize: 13, color: Colors.black87),
          ),
          const SizedBox(height: 14),
          TextField(
            controller: _reasonController,
            maxLines: 3,
            decoration: const InputDecoration(
              labelText: '取消原因 (必填)',
              hintText: '如：面交时间冲突、双方协商一致取消等',
              border: OutlineInputBorder(),
            ),
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('暂不取消'),
        ),
        ElevatedButton(
          style: ElevatedButton.styleFrom(
            backgroundColor: Colors.red.shade700,
            foregroundColor: Colors.white,
          ),
          onPressed: _confirm,
          child: const Text('确认取消'),
        ),
      ],
    );
  }
}
