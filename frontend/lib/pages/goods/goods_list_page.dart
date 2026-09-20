import 'package:flutter/material.dart';
import 'package:get/get.dart';
import '../../controllers/goods_controller.dart';
import '../../models/goods_model.dart';
import '../../routes/app_routes.dart';
import '../../widgets/goods_thumbnail.dart';

/// 商品列表主页 (支持搜索、分类筛选、分页流式加载)
class GoodsListPage extends StatefulWidget {
  const GoodsListPage({super.key});

  @override
  State<GoodsListPage> createState() => _GoodsListPageState();
}

class _GoodsListPageState extends State<GoodsListPage> {
  late final GoodsController _controller;
  final TextEditingController _searchEditCtrl = TextEditingController();
  final ScrollController _scrollController = ScrollController();

  @override
  void initState() {
    super.initState();
    _controller = Get.put(GoodsController());
    _scrollController.addListener(_onScroll);
  }

  void _onScroll() {
    if (_scrollController.position.pixels >=
        _scrollController.position.maxScrollExtent - 200) {
      _controller.loadMore();
    }
  }

  /// 打开发布页；发布成功后刷新列表（发布页以 `Get.back(result: true)` 标记成功）。
  ///
  /// 之前这里是"即发即忘"的 `Get.toNamed`，用户发布完回到集市看不到自己的新商品，
  /// 必须手动下拉刷新——这是体验上最容易被察觉的"数据不一致"。
  Future<void> _openCreateGoods() async {
    final result = await Get.toNamed(AppRoutes.goodsCreate);
    if (!mounted) return;
    if (result == true) {
      _controller.loadGoods(refresh: true);
    }
  }

  @override
  void dispose() {
    _searchEditCtrl.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Scaffold(
      appBar: AppBar(
        title: const Text('校园集市'),
        centerTitle: true,
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(60),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
            child: TextField(
              controller: _searchEditCtrl,
              textInputAction: TextInputAction.search,
              onSubmitted: (val) => _controller.onSearch(val),
              decoration: InputDecoration(
                hintText: '搜索商品、教材、数码电子...',
                prefixIcon: const Icon(Icons.search, size: 20),
                suffixIcon: _searchEditCtrl.text.isNotEmpty
                    ? IconButton(
                        icon: const Icon(Icons.clear, size: 18),
                        onPressed: () {
                          _searchEditCtrl.clear();
                          _controller.onSearch('');
                        },
                      )
                    : null,
                contentPadding: const EdgeInsets.symmetric(vertical: 0, horizontal: 16),
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(24),
                  borderSide: BorderSide.none,
                ),
                filled: true,
                fillColor: theme.colorScheme.surfaceContainerHighest.withValues(alpha: 0.5),
              ),
            ),
          ),
        ),
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: _openCreateGoods,
        icon: const Icon(Icons.add),
        label: const Text('发布闲置'),
        backgroundColor: theme.colorScheme.primary,
        foregroundColor: theme.colorScheme.onPrimary,
      ),
      body: Column(
        children: [
          // 热门搜索词标签栏
          Obx(() {
            if (_controller.hotKeywords.isEmpty) return const SizedBox.shrink();
            return Container(
              height: 36,
              margin: const EdgeInsets.only(top: 4, bottom: 4),
              child: ListView.separated(
                scrollDirection: Axis.horizontal,
                padding: const EdgeInsets.symmetric(horizontal: 16),
                itemCount: _controller.hotKeywords.length + 1,
                separatorBuilder: (context, index) => const SizedBox(width: 6),
                itemBuilder: (context, index) {
                  if (index == 0) {
                    return Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(Icons.local_fire_department, size: 16, color: Colors.deepOrange.shade600),
                        const SizedBox(width: 2),
                        Text(
                          '热搜',
                          style: TextStyle(
                            fontSize: 12,
                            fontWeight: FontWeight.bold,
                            color: Colors.deepOrange.shade600,
                          ),
                        ),
                        const SizedBox(width: 6),
                      ],
                    );
                  }
                  final kw = _controller.hotKeywords[index - 1];
                  return ActionChip(
                    label: Text(kw, style: const TextStyle(fontSize: 11)),
                    padding: EdgeInsets.zero,
                    materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                    onPressed: () {
                      _searchEditCtrl.text = kw;
                      _controller.onSearch(kw);
                    },
                  );
                },
              ),
            );
          }),

          // 分类横向滚动条
          _buildCategoryFilterBar(theme),

          // 商品网格列表
          Expanded(
            child: Obx(() {
              // 只有"首次加载 / 空列表刷新"才整屏 loading：
              // 已有数据时刷新（下拉刷新、发布后回刷、切分类）保留当前列表，
              // 避免整屏闪烁成菊花再跳回（对齐收藏/足迹页的判定条件）。
              if (_controller.isLoading.value && _controller.goodsList.isEmpty) {
                return const Center(child: CircularProgressIndicator());
              }

              // 错误态优先于空态：断网/超时/401 绝不能显示成"暂无在售商品"
              if (_controller.hasError && _controller.goodsList.isEmpty) {
                return _buildErrorState(theme);
              }

              if (_controller.goodsList.isEmpty) {
                return _buildEmptyState(theme);
              }

              return RefreshIndicator(
                onRefresh: () => _controller.loadGoods(refresh: true),
                child: GridView.builder(
                  controller: _scrollController,
                  padding: const EdgeInsets.all(12),
                  gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                    crossAxisCount: 2,
                    mainAxisSpacing: 12,
                    crossAxisSpacing: 12,
                    childAspectRatio: 0.72,
                  ),
                  itemCount: _controller.goodsList.length +
                      (_controller.hasMore.value ? 1 : 0),
                  itemBuilder: (context, index) {
                    if (index == _controller.goodsList.length) {
                      return const Center(
                        child: Padding(
                          padding: EdgeInsets.all(8.0),
                          child: CircularProgressIndicator(strokeWidth: 2),
                        ),
                      );
                    }
                    final goods = _controller.goodsList[index];
                    return _buildGoodsCard(context, goods, theme);
                  },
                ),
              );
            }),
          ),
        ],
      ),
    );
  }

  /// 分类横向滚动选择栏
  Widget _buildCategoryFilterBar(ThemeData theme) {
    return Obx(() {
      return Container(
        height: 48,
        padding: const EdgeInsets.symmetric(vertical: 6),
        child: ListView(
          scrollDirection: Axis.horizontal,
          padding: const EdgeInsets.symmetric(horizontal: 12),
          children: [
            Padding(
              padding: const EdgeInsets.only(right: 8),
              child: ChoiceChip(
                label: const Text('全部'),
                selected: _controller.selectedCategoryId.value == null,
                onSelected: (_) => _controller.onSelectCategory(null),
              ),
            ),
            ..._controller.categories.map((cat) {
              final isSelected = _controller.selectedCategoryId.value == cat.id;
              return Padding(
                padding: const EdgeInsets.only(right: 8),
                child: ChoiceChip(
                  label: Text(cat.name),
                  selected: isSelected,
                  onSelected: (_) => _controller.onSelectCategory(cat.id),
                ),
              );
            }),
            // 分类加载失败的可见降级：不给用户一个"永远只有全部"的静默筛选栏
            if (_controller.categories.isEmpty &&
                _controller.categoryErrorMessage.value.isNotEmpty)
              ActionChip(
                avatar: const Icon(Icons.refresh, size: 16),
                label: const Text('分类加载失败，重试',
                    style: TextStyle(fontSize: 11)),
                onPressed: () => _controller.loadCategories(),
              ),
          ],
        ),
      );
    });
  }

  /// 加载失败错误态（断网 / 超时 / 服务端错误），与"暂无商品"空态严格区分
  Widget _buildErrorState(ThemeData theme) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.cloud_off_outlined, size: 72, color: Colors.grey.shade400),
            const SizedBox(height: 16),
            Text(
              '商品列表加载失败',
              style: theme.textTheme.titleMedium?.copyWith(
                fontWeight: FontWeight.bold,
                color: Colors.grey.shade700,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              _controller.errorMessage.value,
              textAlign: TextAlign.center,
              style: theme.textTheme.bodySmall?.copyWith(color: Colors.grey.shade600),
            ),
            const SizedBox(height: 20),
            ElevatedButton.icon(
              onPressed: () => _controller.loadGoods(refresh: true),
              icon: const Icon(Icons.refresh),
              label: const Text('点击重试'),
            ),
          ],
        ),
      ),
    );
  }

  /// 空数据缺省组件
  Widget _buildEmptyState(ThemeData theme) {
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(Icons.inventory_2_outlined, size: 72, color: Colors.grey.shade400),
          const SizedBox(height: 16),
          Text(
            '暂无在售二手商品',
            style: theme.textTheme.titleMedium?.copyWith(color: Colors.grey.shade600),
          ),
          const SizedBox(height: 8),
          Text(
            '换个关键词搜索，或者成为第一个发布者吧！',
            style: theme.textTheme.bodySmall?.copyWith(color: Colors.grey.shade500),
          ),
          const SizedBox(height: 20),
          ElevatedButton.icon(
            onPressed: () => _controller.loadGoods(refresh: true),
            icon: const Icon(Icons.refresh),
            label: const Text('刷新列表'),
          ),
        ],
      ),
    );
  }

  /// 卡片式商品展示组件
  Widget _buildGoodsCard(BuildContext context, GoodsItemModel goods, ThemeData theme) {
    return Card(
      elevation: 2,
      clipBehavior: Clip.antiAlias,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      child: InkWell(
        onTap: () => Get.toNamed(AppRoutes.goodsDetail, arguments: goods.id),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // 封面图片与成色标签
            Expanded(
              child: Stack(
                fit: StackFit.expand,
                children: [
                  GoodsThumbnail(
                    imageUrl: goods.coverImage,
                    borderRadius: 0,
                  ),
                  // 成色标签 (右上角)
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

            // 标题与价格信息
            Padding(
              padding: const EdgeInsets.all(8),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  // 标题
                  Text(
                    goods.title,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 13),
                  ),
                  const SizedBox(height: 6),

                  // 价格与原价
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.baseline,
                    textBaseline: TextBaseline.alphabetic,
                    children: [
                      Text(
                        '¥',
                        style: TextStyle(
                          color: Colors.deepOrange.shade700,
                          fontWeight: FontWeight.bold,
                          fontSize: 12,
                        ),
                      ),
                      Text(
                        goods.price.toStringAsFixed(2),
                        style: TextStyle(
                          color: Colors.deepOrange.shade700,
                          fontWeight: FontWeight.bold,
                          fontSize: 16,
                        ),
                      ),
                      if (goods.originalPrice != null) ...[
                        const SizedBox(width: 4),
                        Text(
                          '¥${goods.originalPrice!.toStringAsFixed(0)}',
                          style: TextStyle(
                            decoration: TextDecoration.lineThrough,
                            color: Colors.grey.shade400,
                            fontSize: 11,
                          ),
                        ),
                      ],
                    ],
                  ),
                  const SizedBox(height: 4),

                  // 高校认证标签
                  Row(
                    children: [
                      Icon(Icons.school, size: 12, color: Colors.blue.shade700),
                      const SizedBox(width: 3),
                      Expanded(
                        child: Text(
                          goods.schoolName,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(color: Colors.grey.shade600, fontSize: 11),
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
