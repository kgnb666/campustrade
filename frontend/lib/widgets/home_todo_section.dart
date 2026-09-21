import 'package:flutter/material.dart';
import 'package:get/get.dart';

import '../controllers/home_controller.dart';
import '../models/order.dart';
import '../models/order_summary.dart';
import '../pages/order/my_orders_page.dart';

/// 首页「我的待办」区块（仅登录用户可见）。
///
/// 三个入口与"我的订单"页面的筛选参数一一对应：
/// - 待我确认 → 我的订单 · 卖家视角 · 待确认
/// - 待面交   → 我的订单 · 待面交
/// - 待评价   → 我的订单 · 已完成
///
/// 数量来自 `GET /api/orders/summary`（后端一条 SQL 聚合，"待评价"必须由后端算，
/// 前端拿订单状态推不出来）。
///
/// 三个入口**始终存在**：数量为 0 时只把数字弱化，不隐藏入口——否则用户会以为
/// "没有这个功能"，而实际上只是暂时没有待办。
class HomeTodoSection extends StatelessWidget {
  const HomeTodoSection({super.key, required this.controller});

  final HomeController controller;

  /// 加载中占位的高度（与卡片内容高度一致，避免数据回来后布局跳动）
  static const double _placeholderHeight = 86;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Icon(Icons.assignment_turned_in_outlined, size: 20, color: theme.colorScheme.primary),
            const SizedBox(width: 6),
            Text(
              '我的待办',
              style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold),
            ),
          ],
        ),
        const SizedBox(height: 10),
        Obx(() {
          if (controller.hasTodoError) {
            return _buildErrorState(theme);
          }
          if (controller.todoLoading.value && controller.todoSummary.value == null) {
            return _buildLoadingState(theme);
          }
          final summary = controller.todoSummary.value ?? OrderTodoSummary.empty;
          return _buildItems(summary, theme);
        }),
      ],
    );
  }

  /// 正常态：三张等宽卡片
  Widget _buildItems(OrderTodoSummary summary, ThemeData theme) {
    return Row(
      children: [
        Expanded(
          child: _TodoItem(
            label: '待我确认',
            hint: '买家已下单，等我接单',
            icon: Icons.pending_actions_outlined,
            count: summary.pendingSellerConfirm,
            onTap: () => MyOrdersPage.open(
              role: MyOrdersPage.roleSeller,
              status: OrderStatus.waitSellerConfirm,
            ),
          ),
        ),
        const SizedBox(width: 10),
        Expanded(
          child: _TodoItem(
            label: '待面交',
            hint: '约个时间地点当面交易',
            icon: Icons.handshake_outlined,
            count: summary.waitMeet,
            onTap: () => MyOrdersPage.open(status: OrderStatus.waitMeet),
          ),
        ),
        const SizedBox(width: 10),
        Expanded(
          child: _TodoItem(
            label: '待评价',
            hint: '交易完成，去评一评对方',
            icon: Icons.rate_review_outlined,
            count: summary.toReview,
            onTap: () => MyOrdersPage.open(status: OrderStatus.completed),
          ),
        ),
      ],
    );
  }

  /// 加载中：静态骨架（刻意不用转圈动画——它会一直转，既刺眼又让页面等不到"稳定"）
  Widget _buildLoadingState(ThemeData theme) {
    return Row(
      children: List<Widget>.generate(3, (index) {
        return Expanded(
          child: Container(
            height: _placeholderHeight,
            margin: EdgeInsets.only(right: index == 2 ? 0 : 10),
            decoration: BoxDecoration(
              color: Colors.grey.shade100,
              borderRadius: BorderRadius.circular(14),
            ),
          ),
        );
      }),
    );
  }

  /// 失败态：可见且可重试，且**只影响这一块**——最新商品区块照常渲染。
  Widget _buildErrorState(ThemeData theme) {
    return Card(
      elevation: 0,
      color: Colors.orange.withAlpha(18),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(14),
        side: BorderSide(color: Colors.orange.withAlpha(70)),
      ),
      child: InkWell(
        borderRadius: BorderRadius.circular(14),
        onTap: () => controller.retryTodo(),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 18),
          child: Row(
            children: [
              Icon(Icons.error_outline, size: 20, color: Colors.orange.shade800),
              const SizedBox(width: 10),
              const Expanded(
                child: Text(
                  '待办加载失败，点击重试',
                  style: TextStyle(fontSize: 13, fontWeight: FontWeight.bold),
                ),
              ),
              Icon(Icons.refresh, size: 18, color: Colors.orange.shade800),
            ],
          ),
        ),
      ),
    );
  }
}

/// 单个待办入口
class _TodoItem extends StatelessWidget {
  const _TodoItem({
    required this.label,
    required this.hint,
    required this.icon,
    required this.count,
    required this.onTap,
  });

  final String label;
  final String hint;
  final IconData icon;
  final int count;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final bool hasItems = count > 0;

    return Card(
      elevation: 1,
      margin: EdgeInsets.zero,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
      child: InkWell(
        borderRadius: BorderRadius.circular(14),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Icon(
                icon,
                size: 18,
                color: hasItems ? theme.colorScheme.primary : Colors.grey.shade400,
              ),
              const SizedBox(height: 6),
              // 数量为 0 时弱化颜色，但数字与入口都保留
              Text(
                '$count',
                style: TextStyle(
                  fontSize: 22,
                  fontWeight: FontWeight.bold,
                  color: hasItems ? theme.colorScheme.primary : Colors.grey.shade400,
                ),
              ),
              const SizedBox(height: 2),
              Text(
                label,
                style: const TextStyle(fontSize: 13, fontWeight: FontWeight.bold),
              ),
              const SizedBox(height: 4),
              Text(
                hint,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(fontSize: 11, color: Colors.grey.shade600),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
