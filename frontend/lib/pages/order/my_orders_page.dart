import 'package:flutter/material.dart';
import 'package:get/get.dart';
import '../../controllers/order_controller.dart';
import '../../models/order.dart';
import '../../routes/app_routes.dart';
import '../../widgets/goods_thumbnail.dart';

/// 订单列表浏览页面 (我的购买 / 我的出售)
/// 提供视角切换、状态筛选、分页浏览与详情跳转功能
class MyOrdersPage extends StatefulWidget {
  const MyOrdersPage({super.key});

  @override
  State<MyOrdersPage> createState() => _MyOrdersPageState();
}

class _MyOrdersPageState extends State<MyOrdersPage>
    with SingleTickerProviderStateMixin {
  late final OrderController _orderController;
  late final TabController _tabController;
  final ScrollController _scrollController = ScrollController();

  @override
  void initState() {
    super.initState();
    _orderController = Get.isRegistered<OrderController>()
        ? Get.find<OrderController>()
        : Get.put(OrderController());

    final initialRole = _orderController.currentRole.value;
    final initialIndex = initialRole == 'SELLER' ? 1 : 0;
    _tabController = TabController(
      length: 2,
      vsync: this,
      initialIndex: initialIndex,
    );

    _tabController.addListener(() {
      if (!_tabController.indexIsChanging) {
        final newRole = _tabController.index == 1 ? 'SELLER' : 'BUYER';
        _orderController.switchRole(newRole);
      }
    });

    _scrollController.addListener(() {
      if (_scrollController.position.pixels >=
          _scrollController.position.maxScrollExtent - 200) {
        _orderController.loadMoreOrders();
      }
    });

    // 页面初始化时拉取当前列表
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _orderController.fetchMyOrders(refresh: true);
    });
  }

  @override
  void dispose() {
    _tabController.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Scaffold(
      appBar: AppBar(
        title: const Text('我的订单'),
        centerTitle: true,
        bottom: TabBar(
          controller: _tabController,
          labelColor: theme.colorScheme.primary,
          unselectedLabelColor: Colors.grey[600],
          indicatorColor: theme.colorScheme.primary,
          indicatorWeight: 3,
          tabs: const [
            Tab(
              icon: Icon(Icons.shopping_bag_outlined, size: 20),
              text: '我的购买',
            ),
            Tab(
              icon: Icon(Icons.storefront_outlined, size: 20),
              text: '我的出售',
            ),
          ],
        ),
      ),
      body: Column(
        children: [
          // 状态筛选栏
          _buildFilterBar(theme),

          // 订单列表主体内容
          Expanded(
            child: Obx(() {
              // 1. 全局初次加载状态
              if (_orderController.loading.value &&
                  _orderController.orders.isEmpty) {
                return const Center(
                  child: CircularProgressIndicator(),
                );
              }

              // 2. 错误重试状态 (列表为空且报错)
              if (_orderController.hasError &&
                  _orderController.orders.isEmpty) {
                return _buildErrorState(theme);
              }

              // 3. 空数据状态
              if (_orderController.orders.isEmpty) {
                return _buildEmptyState(theme);
              }

              // 4. 正常订单卡片列表
              return RefreshIndicator(
                onRefresh: () =>
                    _orderController.fetchMyOrders(refresh: true),
                child: ListView.separated(
                  controller: _scrollController,
                  padding: const EdgeInsets.symmetric(
                    horizontal: 14,
                    vertical: 12,
                  ),
                  itemCount: _orderController.orders.length +
                      (_orderController.hasMore.value ? 1 : 0),
                  separatorBuilder: (_, _) => const SizedBox(height: 12),
                  itemBuilder: (context, index) {
                    if (index == _orderController.orders.length) {
                      return _buildLoadingMoreFooter();
                    }
                    final order = _orderController.orders[index];
                    return _buildOrderCard(context, order, theme);
                  },
                ),
              );
            }),
          ),
        ],
      ),
    );
  }

  /// 状态筛选水平横向滚动列表
  Widget _buildFilterBar(ThemeData theme) {
    return Obx(() {
      final selectedStatus = _orderController.currentStatusFilter.value;

      final filters = <Map<String, dynamic>>[
        {'label': '全部', 'status': null},
        {'label': '待确认', 'status': OrderStatus.waitSellerConfirm},
        {'label': '待面交', 'status': OrderStatus.waitMeet},
        {'label': '已完成', 'status': OrderStatus.completed},
        {'label': '已取消', 'status': OrderStatus.cancelled},
      ];

      return Container(
        height: 52,
        decoration: BoxDecoration(
          color: theme.colorScheme.surface,
          border: Border(
            bottom: BorderSide(color: Colors.grey.shade200, width: 1),
          ),
        ),
        child: ListView.separated(
          scrollDirection: Axis.horizontal,
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
          itemCount: filters.length,
          separatorBuilder: (_, _) => const SizedBox(width: 8),
          itemBuilder: (context, index) {
            final filter = filters[index];
            final OrderStatus? filterStatus = filter['status'] as OrderStatus?;
            final isSelected = selectedStatus == filterStatus;

            return ChoiceChip(
              label: Text(
                filter['label'] as String,
                style: TextStyle(
                  fontSize: 13,
                  fontWeight: isSelected ? FontWeight.bold : FontWeight.normal,
                  color: isSelected
                      ? theme.colorScheme.primary
                      : Colors.grey[700],
                ),
              ),
              selected: isSelected,
              selectedColor: theme.colorScheme.primary.withAlpha(28),
              backgroundColor: Colors.grey.shade100,
              side: BorderSide(
                color: isSelected
                    ? theme.colorScheme.primary
                    : Colors.transparent,
                width: 1,
              ),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(16),
              ),
              onSelected: (_) {
                _orderController.filterByStatus(filterStatus);
              },
            );
          },
        ),
      );
    });
  }

  /// 订单卡片组件
  Widget _buildOrderCard(BuildContext context, OrderVO order, ThemeData theme) {
    return Card(
      elevation: 1.2,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: () {
          Get.toNamed(AppRoutes.orderDetail, arguments: order.id);
        },
        child: Padding(
          padding: const EdgeInsets.all(14.0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // 顶部栏：订单号 + 状态徽标
              Row(
                children: [
                  Icon(Icons.tag_outlined, size: 16, color: Colors.grey[500]),
                  const SizedBox(width: 4),
                  Expanded(
                    child: Text(
                      order.orderNo,
                      style: TextStyle(
                        fontSize: 12,
                        color: Colors.grey[600],
                        fontFamily: 'monospace',
                      ),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                  _buildStatusBadge(order.orderStatus, order.statusText),
                ],
              ),
              const Divider(height: 18),

              // 中部栏：商品快照信息
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  // 商品快照图片
                  ClipRRect(
                    borderRadius: BorderRadius.circular(8),
                    child: SizedBox(
                      width: 76,
                      height: 76,
                      child: GoodsThumbnail(
                        imageUrl: order.goodsImageSnapshot,
                        width: 76,
                        height: 76,
                        borderRadius: 0,
                        placeholderIcon: Icons.receipt_long_outlined,
                      ),
                    ),
                  ),
                  const SizedBox(width: 12),

                  // 标题与价格快照
                  Expanded(
                    child: SizedBox(
                      height: 76,
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        mainAxisAlignment: MainAxisAlignment.spaceBetween,
                        children: [
                          Text(
                            order.goodsTitleSnapshot,
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(
                              fontSize: 14,
                              fontWeight: FontWeight.w600,
                              height: 1.25,
                            ),
                          ),
                          Row(
                            crossAxisAlignment: CrossAxisAlignment.baseline,
                            textBaseline: TextBaseline.alphabetic,
                            children: [
                              Text(
                                '¥',
                                style: TextStyle(
                                  fontSize: 12,
                                  fontWeight: FontWeight.bold,
                                  color: theme.colorScheme.primary,
                                ),
                              ),
                              Text(
                                order.goodsPriceSnapshot.toStringAsFixed(2),
                                style: TextStyle(
                                  fontSize: 18,
                                  fontWeight: FontWeight.bold,
                                  color: theme.colorScheme.primary,
                                ),
                              ),
                              const Spacer(),
                              Text(
                                '查看详情',
                                style: TextStyle(
                                  fontSize: 12,
                                  color: theme.colorScheme.primary,
                                ),
                              ),
                              Icon(
                                Icons.chevron_right,
                                size: 16,
                                color: theme.colorScheme.primary,
                              ),
                            ],
                          ),
                        ],
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 10),

              // 底部栏：时间与当事人信息
              Row(
                children: [
                  Icon(Icons.access_time, size: 14, color: Colors.grey[500]),
                  const SizedBox(width: 4),
                  Text(
                    order.createdTime ?? '',
                    style: TextStyle(fontSize: 12, color: Colors.grey[500]),
                  ),
                  const Spacer(),
                  // 交易角色互视提示
                  if (_orderController.currentRole.value == 'BUYER' &&
                      order.seller != null) ...[
                    Text(
                      '卖家: ${order.seller!.nickname ?? order.seller!.username}',
                      style: TextStyle(fontSize: 12, color: Colors.grey[600]),
                    ),
                  ] else if (_orderController.currentRole.value == 'SELLER' &&
                      order.buyer != null) ...[
                    Text(
                      '买家: ${order.buyer!.nickname ?? order.buyer!.username}',
                      style: TextStyle(fontSize: 12, color: Colors.grey[600]),
                    ),
                  ],
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// 状态徽标渲染
  Widget _buildStatusBadge(OrderStatus status, String description) {
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
        // 未知状态：中性配色 + 服务端原文，不借用任何已知状态的视觉语义
        textColor = Colors.grey.shade800;
        bgColor = Colors.grey.shade100;
        break;
    }

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: bgColor,
        borderRadius: BorderRadius.circular(6),
        border: Border.all(color: textColor.withAlpha(60), width: 0.8),
      ),
      child: Text(
        description,
        style: TextStyle(
          color: textColor,
          fontSize: 11,
          fontWeight: FontWeight.bold,
        ),
      ),
    );
  }

  /// 空状态提示
  Widget _buildEmptyState(ThemeData theme) {
    return Center(
      child: SingleChildScrollView(
        child: Padding(
          padding: const EdgeInsets.all(24.0),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(
                Icons.receipt_long_outlined,
                size: 72,
                color: Colors.grey.shade300,
              ),
              const SizedBox(height: 16),
              Text(
                '暂无相关订单',
                style: theme.textTheme.titleMedium?.copyWith(
                  fontWeight: FontWeight.bold,
                  color: Colors.grey.shade700,
                ),
              ),
              const SizedBox(height: 8),
              Text(
                '可尝试切换顶部购买/出售视角或修改状态筛选条件',
                style: TextStyle(fontSize: 13, color: Colors.grey.shade500),
                textAlign: TextAlign.center,
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// 错误重试状态
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
              '加载订单失败',
              style: theme.textTheme.titleMedium?.copyWith(
                fontWeight: FontWeight.bold,
                color: Colors.grey.shade800,
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
              onPressed: () =>
                  _orderController.fetchMyOrders(refresh: true),
              icon: const Icon(Icons.refresh, size: 18),
              label: const Text('点击重试'),
            ),
          ],
        ),
      ),
    );
  }

  /// 加载更多 Footer
  Widget _buildLoadingMoreFooter() {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 14.0),
      child: Center(
        child: _orderController.isMoreLoading.value
            ? const SizedBox(
                width: 22,
                height: 22,
                child: CircularProgressIndicator(strokeWidth: 2),
              )
            : const Text(
                '没有更多订单了',
                style: TextStyle(fontSize: 12, color: Colors.grey),
              ),
      ),
    );
  }
}
