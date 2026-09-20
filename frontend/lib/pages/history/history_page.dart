import 'package:flutter/material.dart';
import 'package:get/get.dart';
import '../../controllers/history_controller.dart';
import '../../models/history_model.dart';
import '../../routes/app_routes.dart';
import '../../models/status_enums.dart';
import '../../utils/page_controller_scope.dart';
import '../../widgets/goods_thumbnail.dart';

/// 浏览足迹页面
class HistoryPage extends StatefulWidget {
  const HistoryPage({super.key});

  @override
  State<HistoryPage> createState() => _HistoryPageState();
}

class _HistoryPageState extends State<HistoryPage> {
  /// 本页面自己的足迹控制器：随本路由释放（理由同收藏页）
  late final PageControllerRef<HistoryController> _controllerRef;
  HistoryController get _controller => _controllerRef.controller;

  final ScrollController _scrollController = ScrollController();

  @override
  void initState() {
    super.initState();
    _controllerRef = PageControllerScope.acquire<HistoryController>(
      () => HistoryController(),
    );
    _scrollController.addListener(_onScroll);
  }

  void _onScroll() {
    if (_scrollController.position.pixels >=
        _scrollController.position.maxScrollExtent - 200) {
      _controller.loadMore();
    }
  }

  @override
  void dispose() {
    _scrollController.dispose();
    _controllerRef.release();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('浏览足迹'),
      ),
      body: Obx(() {
        if (_controller.isLoading.value && _controller.historyList.isEmpty) {
          return const Center(child: CircularProgressIndicator());
        }

        // 错误态优先于空态：断网/超时不能伪装成"最近没有浏览过"
        if (_controller.hasError && _controller.historyList.isEmpty) {
          return _buildErrorState();
        }

        if (_controller.historyList.isEmpty) {
          return Center(
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(Icons.history, size: 72, color: Colors.grey.shade400),
                const SizedBox(height: 16),
                const Text(
                  '最近还没有浏览过闲置商品',
                  style: TextStyle(fontSize: 16, color: Colors.grey),
                ),
                const SizedBox(height: 16),
                ElevatedButton(
                  onPressed: () => Get.back(),
                  child: const Text('去首页逛逛'),
                ),
              ],
            ),
          );
        }

        return RefreshIndicator(
          onRefresh: () => _controller.loadHistory(refresh: true),
          child: ListView.separated(
            controller: _scrollController,
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
            itemCount: _controller.historyList.length + (_controller.hasMore.value ? 1 : 0),
            separatorBuilder: (context, index) => const SizedBox(height: 10),
            itemBuilder: (context, index) {
              if (index >= _controller.historyList.length) {
                return const Padding(
                  padding: EdgeInsets.symmetric(vertical: 16),
                  child: Center(
                    child: SizedBox(
                      width: 24,
                      height: 24,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    ),
                  ),
                );
              }

              final item = _controller.historyList[index];
              return _buildHistoryCard(context, item);
            },
          ),
        );
      }),
    );
  }

  /// 加载失败错误态（与"最近还没有浏览过闲置商品"的空态严格区分）
  Widget _buildErrorState() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.cloud_off_outlined, size: 72, color: Colors.grey.shade400),
            const SizedBox(height: 16),
            const Text(
              '浏览足迹加载失败',
              style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: Colors.grey),
            ),
            const SizedBox(height: 8),
            Text(
              _controller.errorMessage.value,
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 13, color: Colors.grey),
            ),
            const SizedBox(height: 20),
            ElevatedButton.icon(
              onPressed: () => _controller.loadHistory(refresh: true),
              icon: const Icon(Icons.refresh),
              label: const Text('点击重试'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildHistoryCard(BuildContext context, HistoryItemModel item) {
    final theme = Theme.of(context);
    // 只有 OFF_SHELF 显示「已下架」；已售出等其它不可购买状态显示各自文案
    final unavailableStatus = GoodsStatus.fromCode(item.status);
    final showUnavailableBadge = unavailableStatus?.isUnavailable ?? true;

    return Card(
      clipBehavior: Clip.antiAlias,
      elevation: 1,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
      child: InkWell(
        onTap: () async {
          await Get.toNamed(AppRoutes.goodsDetail, arguments: item.goodsId);
          if (!mounted) return;
          _controller.loadHistory(refresh: true);
        },
        child: Padding(
          padding: const EdgeInsets.all(10),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // 封面图
              ClipRRect(
                borderRadius: BorderRadius.circular(8),
                child: SizedBox(
                  width: 85,
                  height: 85,
                  child: GoodsThumbnail(
                    imageUrl: item.firstImageUrl,
                    width: 85,
                    height: 85,
                    borderRadius: 0,
                  ),
                ),
              ),
              const SizedBox(width: 12),

              // 详情
              Expanded(
                child: SizedBox(
                  height: 85,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            item.title,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(
                              fontWeight: FontWeight.bold,
                              fontSize: 15,
                            ),
                          ),
                          const SizedBox(height: 4),
                          Row(
                            children: [
                              Container(
                                padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
                                decoration: BoxDecoration(
                                  color: theme.colorScheme.primaryContainer.withValues(alpha: 0.6),
                                  borderRadius: BorderRadius.circular(4),
                                ),
                                child: Text(
                                  item.conditionLevel,
                                  style: TextStyle(
                                    fontSize: 10,
                                    color: theme.colorScheme.onPrimaryContainer,
                                    fontWeight: FontWeight.w500,
                                  ),
                                ),
                              ),
                              if (showUnavailableBadge) ...[
                                const SizedBox(width: 6),
                                Container(
                                  padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
                                  decoration: BoxDecoration(
                                    color: Colors.grey.shade200,
                                    borderRadius: BorderRadius.circular(4),
                                  ),
                                  child: Text(
                                    GoodsStatus.labelOf(item.status),
                                    style: const TextStyle(fontSize: 10, color: Colors.grey),
                                  ),
                                ),
                              ],
                            ],
                          ),
                        ],
                      ),
                      Row(
                        mainAxisAlignment: MainAxisAlignment.spaceBetween,
                        children: [
                          Row(
                            crossAxisAlignment: CrossAxisAlignment.baseline,
                            textBaseline: TextBaseline.alphabetic,
                            children: [
                              Text(
                                '¥',
                                style: TextStyle(
                                  color: Colors.deepOrange.shade700,
                                  fontWeight: FontWeight.bold,
                                  fontSize: 13,
                                ),
                              ),
                              Text(
                                item.price.toStringAsFixed(2),
                                style: TextStyle(
                                  color: Colors.deepOrange.shade700,
                                  fontWeight: FontWeight.bold,
                                  fontSize: 18,
                                ),
                              ),
                            ],
                          ),
                          if (item.browseTime != null)
                            Text(
                              item.browseTime!.length >= 16
                                  ? item.browseTime!.substring(5, 16)
                                  : item.browseTime!,
                              style: TextStyle(
                                fontSize: 11,
                                color: Colors.grey.shade500,
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
        ),
      ),
    );
  }
}
