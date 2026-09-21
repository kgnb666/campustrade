import 'package:flutter/material.dart';
import 'package:get/get.dart';

import '../controllers/home_controller.dart';
import '../models/goods_model.dart';
import '../routes/app_routes.dart';
import 'goods_thumbnail.dart';

/// 首页「最新商品」区块：最新上架的 6 个在售商品 + 「查看全部 →」。
///
/// 数据来自集市同源的 `GET /api/goods/list`（后端按 created_time 倒序），
/// 但**不复用**集市页的 `GoodsController`：那会把首页的 6 条商品写进它的分页状态机，
/// 用户再进集市就会看到"第一页只有 6 条、还以为没有更多了"的错乱状态。
/// 这里只用 [HomeController] 的 `latestGoods`，与集市页互不影响。
class HomeLatestGoodsSection extends StatelessWidget {
  const HomeLatestGoodsSection({super.key, required this.controller});

  final HomeController controller;

  /// 骨架/空态占位高度
  static const double _placeholderHeight = 168;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Icon(Icons.local_fire_department_outlined, size: 20, color: theme.colorScheme.primary),
            const SizedBox(width: 6),
            Text(
              '最新商品',
              style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold),
            ),
            const SizedBox(width: 8),
            Text(
              '刚上架的闲置好物',
              style: TextStyle(fontSize: 12, color: Colors.grey.shade600),
            ),
          ],
        ),
        const SizedBox(height: 10),
        Obx(() {
          if (controller.latestLoading.value && controller.latestGoods.isEmpty) {
            return _buildSkeleton();
          }
          if (controller.hasLatestError && controller.latestGoods.isEmpty) {
            return _buildErrorState(theme);
          }
          if (controller.latestGoods.isEmpty) {
            return _buildEmptyState(theme);
          }
          return _buildGrid(context, theme);
        }),
        const SizedBox(height: 4),
        Center(
          child: TextButton.icon(
            onPressed: () => Get.toNamed(AppRoutes.goodsList),
            icon: const Icon(Icons.arrow_forward_rounded, size: 16),
            label: const Text('查看全部 →'),
          ),
        ),
      ],
    );
  }

  /// 商品网格（2 列；外层是首页的整页滚动，因此禁用自身滚动）
  Widget _buildGrid(BuildContext context, ThemeData theme) {
    final items = controller.latestGoods;
    return GridView.builder(
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      padding: EdgeInsets.zero,
      gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: 2,
        mainAxisSpacing: 12,
        crossAxisSpacing: 12,
        childAspectRatio: 0.78,
      ),
      itemCount: items.length,
      itemBuilder: (context, index) => _buildGoodsCard(items[index], theme),
    );
  }

  Widget _buildGoodsCard(GoodsItemModel goods, ThemeData theme) {
    return Card(
      elevation: 1,
      margin: EdgeInsets.zero,
      clipBehavior: Clip.antiAlias,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      child: InkWell(
        onTap: () => Get.toNamed(AppRoutes.goodsDetail, arguments: goods.id),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: Stack(
                fit: StackFit.expand,
                children: [
                  GoodsThumbnail(imageUrl: goods.coverImage, borderRadius: 0),
                  if (goods.conditionLevel.isNotEmpty)
                    Positioned(
                      top: 6,
                      right: 6,
                      child: Container(
                        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                        decoration: BoxDecoration(
                          color: Colors.black.withValues(alpha: 0.65),
                          borderRadius: BorderRadius.circular(6),
                        ),
                        child: Text(
                          goods.conditionLevel,
                          style: const TextStyle(color: Colors.white, fontSize: 10),
                        ),
                      ),
                    ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.all(8),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    goods.title,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 13),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    '¥${goods.price.toStringAsFixed(2)}',
                    style: TextStyle(
                      color: Colors.deepOrange.shade700,
                      fontWeight: FontWeight.bold,
                      fontSize: 15,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// 加载中：静态骨架（不用转圈动画，避免页面一直"不稳定"）
  Widget _buildSkeleton() {
    return GridView.builder(
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      padding: EdgeInsets.zero,
      gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: 2,
        mainAxisSpacing: 12,
        crossAxisSpacing: 12,
        childAspectRatio: 0.78,
      ),
      itemCount: 4,
      itemBuilder: (context, index) => Container(
        height: _placeholderHeight,
        decoration: BoxDecoration(
          color: Colors.grey.shade100,
          borderRadius: BorderRadius.circular(12),
        ),
      ),
    );
  }

  Widget _buildErrorState(ThemeData theme) {
    return Card(
      elevation: 0,
      color: Colors.orange.withAlpha(18),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(14),
        side: BorderSide(color: Colors.orange.withAlpha(70)),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 20),
        child: Column(
          children: [
            Icon(Icons.cloud_off_outlined, size: 40, color: Colors.grey.shade400),
            const SizedBox(height: 10),
            const Text(
              '最新商品加载失败',
              style: TextStyle(fontSize: 14, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 6),
            Text(
              controller.latestErrorMessage.value,
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 12, color: Colors.grey.shade600),
            ),
            const SizedBox(height: 10),
            ElevatedButton.icon(
              onPressed: () => controller.retryLatestGoods(),
              icon: const Icon(Icons.refresh, size: 18),
              label: const Text('点击重试'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildEmptyState(ThemeData theme) {
    return Card(
      elevation: 0,
      color: Colors.grey.shade50,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 26, horizontal: 14),
        child: Column(
          children: [
            Icon(Icons.inventory_2_outlined, size: 40, color: Colors.grey.shade400),
            const SizedBox(height: 10),
            Text(
              '暂时还没有在售商品',
              style: TextStyle(fontSize: 14, color: Colors.grey.shade700),
            ),
            const SizedBox(height: 6),
            Text(
              '成为第一个发布闲置的同学吧',
              style: TextStyle(fontSize: 12, color: Colors.grey.shade600),
            ),
          ],
        ),
      ),
    );
  }
}
