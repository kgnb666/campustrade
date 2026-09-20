import 'package:flutter/material.dart';
import 'package:get/get.dart';
import '../../controllers/goods_controller.dart';
import '../../models/goods_model.dart';
import '../../routes/app_routes.dart';

/// 我的发布商品管理页面
class MyGoodsPage extends StatefulWidget {
  const MyGoodsPage({super.key});

  @override
  State<MyGoodsPage> createState() => _MyGoodsPageState();
}

class _MyGoodsPageState extends State<MyGoodsPage> {
  final GoodsController _goodsController = Get.put(GoodsController());

  @override
  void initState() {
    super.initState();
    _goodsController.loadMyGoods();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Scaffold(
      appBar: AppBar(
        title: const Text('我的发布'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: () => _goodsController.loadMyGoods(),
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () async {
          final res = await Get.toNamed(AppRoutes.goodsCreate);
          if (res == true) _goodsController.loadMyGoods();
        },
        icon: const Icon(Icons.add),
        label: const Text('新增发布'),
      ),
      body: Obx(() {
        if (_goodsController.isMyGoodsLoading.value) {
          return const Center(child: CircularProgressIndicator());
        }

        if (_goodsController.myGoodsList.isEmpty) {
          return Center(
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(Icons.post_add_outlined, size: 72, color: Colors.grey.shade400),
                const SizedBox(height: 16),
                Text(
                  '您尚未发布过任何闲置商品',
                  style: theme.textTheme.titleMedium?.copyWith(color: Colors.grey.shade600),
                ),
                const SizedBox(height: 16),
                ElevatedButton(
                  onPressed: () => Get.toNamed(AppRoutes.goodsCreate),
                  child: const Text('立即发布一件'),
                ),
              ],
            ),
          );
        }

        return RefreshIndicator(
          onRefresh: () => _goodsController.loadMyGoods(),
          child: ListView.separated(
            padding: const EdgeInsets.all(12),
            itemCount: _goodsController.myGoodsList.length,
            separatorBuilder: (_, _) => const SizedBox(height: 10),
            itemBuilder: (context, index) {
              final goods = _goodsController.myGoodsList[index];
              return _buildMyGoodsItem(context, goods, theme);
            },
          ),
        );
      }),
    );
  }

  Widget _buildMyGoodsItem(BuildContext context, GoodsItemModel goods, ThemeData theme) {
    final isOnSale = goods.status == 'ON_SALE';

    return Card(
      elevation: 1.5,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          children: [
            InkWell(
              onTap: () => Get.toNamed(AppRoutes.goodsDetail, arguments: goods.id),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  // 缩略图
                  ClipRRect(
                    borderRadius: BorderRadius.circular(8),
                    child: SizedBox(
                      width: 80,
                      height: 80,
                      child: goods.coverImage != null && goods.coverImage!.isNotEmpty
                          ? Image.network(
                              goods.coverImage!,
                              fit: BoxFit.cover,
                              errorBuilder: (_, _, _) => _buildPlaceholder(),
                            )
                          : _buildPlaceholder(),
                    ),
                  ),
                  const SizedBox(width: 12),

                  // 标题、价格与状态
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            Expanded(
                              child: Text(
                                goods.title,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 14),
                              ),
                            ),
                            _buildStatusBadge(goods.status),
                          ],
                        ),
                        const SizedBox(height: 6),
                        Text(
                          '¥${goods.price.toStringAsFixed(2)}',
                          style: TextStyle(
                            color: Colors.deepOrange.shade700,
                            fontWeight: FontWeight.bold,
                            fontSize: 16,
                          ),
                        ),
                        const SizedBox(height: 4),
                        Row(
                          children: [
                            Text('成色: ${goods.conditionLevel}', style: const TextStyle(fontSize: 11, color: Colors.grey)),
                            const SizedBox(width: 12),
                            Text('浏览: ${goods.viewCount}', style: const TextStyle(fontSize: 11, color: Colors.grey)),
                          ],
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
            const Divider(height: 20),

            // 动作按钮栏
            Row(
              mainAxisAlignment: MainAxisAlignment.end,
              children: [
                OutlinedButton(
                  onPressed: () async {
                    // 编辑商品：把商品 ID 传给发布页，由发布页切换到编辑模式
                    final updated = await Get.toNamed(AppRoutes.goodsCreate, arguments: goods.id);
                    if (updated == true) _goodsController.loadMyGoods();
                  },
                  style: OutlinedButton.styleFrom(
                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
                    minimumSize: const Size(60, 32),
                  ),
                  child: const Text('编辑'),
                ),
                const SizedBox(width: 8),
                OutlinedButton(
                  onPressed: () => _goodsController.toggleGoodsStatus(goods.id, goods.status),
                  style: OutlinedButton.styleFrom(
                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
                    minimumSize: const Size(60, 32),
                  ),
                  child: Text(isOnSale ? '下架' : '重新上架'),
                ),
                const SizedBox(width: 8),
                TextButton(
                  onPressed: () => _showDeleteConfirmDialog(context, goods),
                  style: TextButton.styleFrom(
                    foregroundColor: Colors.red,
                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
                    minimumSize: const Size(60, 32),
                  ),
                  child: const Text('删除'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildStatusBadge(String status) {
    Color bg;
    Color fg;
    String label;

    switch (status) {
      case 'ON_SALE':
        bg = Colors.green.shade50;
        fg = Colors.green.shade700;
        label = '在售中';
        break;
      case 'OFF_SHELF':
        bg = Colors.grey.shade100;
        fg = Colors.grey.shade700;
        label = '已下架';
        break;
      case 'SOLD':
        bg = Colors.blue.shade50;
        fg = Colors.blue.shade700;
        label = '已售出';
        break;
      default:
        bg = Colors.orange.shade50;
        fg = Colors.orange.shade700;
        label = status;
    }

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(4)),
      child: Text(label, style: TextStyle(color: fg, fontSize: 11, fontWeight: FontWeight.bold)),
    );
  }

  void _showDeleteConfirmDialog(BuildContext context, GoodsItemModel goods) {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('确认删除商品？'),
        content: Text('确认将商品 "${goods.title}" 下架并删除吗？删除后其他人将无法查看。'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
          ElevatedButton(
            style: ElevatedButton.styleFrom(backgroundColor: Colors.red, foregroundColor: Colors.white),
            onPressed: () {
              Navigator.pop(ctx);
              _goodsController.deleteGoods(goods.id);
            },
            child: const Text('确认删除'),
          ),
        ],
      ),
    );
  }

  Widget _buildPlaceholder() {
    return Container(
      color: Colors.grey.shade100,
      child: Center(
        child: Icon(Icons.image_outlined, size: 32, color: Colors.grey.shade300),
      ),
    );
  }
}
