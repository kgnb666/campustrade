import 'package:flutter/material.dart';
import 'package:get/get.dart';
import '../../controllers/auth_controller.dart';
import '../../controllers/order_controller.dart';
import '../../controllers/review_controller.dart';
import '../../models/goods_model.dart';
import '../../routes/app_routes.dart';
import '../../services/favorite_service.dart';
import '../../services/goods_service.dart';
import '../../models/status_enums.dart';
import '../../utils/api_error.dart';
import '../../utils/app_logger.dart';
import '../../utils/name_utils.dart';
import '../../utils/page_controller_scope.dart';
import '../../utils/ui_feedback.dart';
import '../../widgets/goods_thumbnail.dart';

/// 商品详情页 (图片轮播、价格、描述、卖家认证与信用分展示)
class GoodsDetailPage extends StatefulWidget {
  const GoodsDetailPage({super.key});

  @override
  State<GoodsDetailPage> createState() => _GoodsDetailPageState();
}

class _GoodsDetailPageState extends State<GoodsDetailPage> {
  final GoodsService _goodsService = GoodsService();
  final FavoriteService _favoriteService = FavoriteService();
  final AuthController _authController = Get.find<AuthController>();

  /// 本页面自己的评价控制器：由路由 binding 注册、随本路由释放
  /// （此前是全局单例，会与订单详情页的评价状态互相污染）。
  late final PageControllerRef<ReviewController> _reviewControllerRef;
  ReviewController get _reviewController => _reviewControllerRef.controller;

  GoodsDetailModel? _goods;
  bool _isLoading = true;
  bool _isFavorite = false;
  int _favoriteCount = 0;
  int _currentImageIndex = 0;

  /// 加载失败原因（空字符串表示正常）。
  /// 与 [_goods] == null 的"商品确实不存在"严格区分：断网/超时不再显示"商品不存在"。
  String _loadError = '';
  final PageController _pageController = PageController();

  @override
  void initState() {
    super.initState();
    _reviewControllerRef = PageControllerScope.acquire<ReviewController>(
      () => ReviewController(),
    );
    _loadDetail();
  }

  Future<void> _loadDetail() async {
    // 本方法既在 initState 调用，也在 await（发布页返回、上下架）之后调用，
    // 因此入口先确认 State 仍挂载，避免往已销毁的页面上写状态
    if (!mounted) return;

    final goodsId = Get.arguments;
    if (goodsId == null) {
      Get.back();
      return;
    }

    // 商品 ID 全程按字符串传递：19 位雪花 ID 在 Web 上转 int 会丢尾数（实测 ...241 会变成 ...200）
    final id = goodsId.toString();
    setState(() {
      _isLoading = true;
      _loadError = '';
    });
    try {
      final detail = await _goodsService.getGoodsDetail(id);
      if (!mounted) return;
      setState(() {
        _goods = detail;
        _isFavorite = detail?.isFavorite ?? false;
        _favoriteCount = detail?.favoriteCount ?? 0;
        _isLoading = false;
      });
      if (detail != null) {
        _reviewController.fetchGoodsReviews(detail.id);
      }
    } catch (e, stack) {
      // 请求失败 => 错误态 + 重试；不再与"商品不存在"混成同一个画面
      AppLogger.error('[GoodsDetailPage] _loadDetail id=$id error',
          error: e, stackTrace: stack);
      if (!mounted) return;
      setState(() {
        _loadError = describeApiError(e, fallback: '商品详情加载失败');
        _isLoading = false;
      });
    }
  }

  bool _isTogglingFavorite = false;

  Future<void> _toggleFavorite() async {
    if (_goods == null || _isTogglingFavorite) return;
    final user = _authController.currentUser.value;
    if (user == null) {
      Get.toNamed(AppRoutes.login);
      return;
    }

    _isTogglingFavorite = true;
    final newStatus = !_isFavorite;
    setState(() {
      _isFavorite = newStatus;
      _favoriteCount += newStatus ? 1 : -1;
      if (_favoriteCount < 0) _favoriteCount = 0;
    });

    try {
      // 收藏接口失败会抛 ApiException（不再返回 false），据此回滚乐观更新并给出具体原因
      if (newStatus) {
        await _favoriteService.addFavorite(_goods!.id);
      } else {
        await _favoriteService.removeFavorite(_goods!.id);
      }

      if (!mounted) return;
      safeSnackbar(
        '提示',
        newStatus ? '已添加至我的收藏' : '已取消收藏',
        snackPosition: SnackPosition.BOTTOM,
        duration: const Duration(seconds: 1),
      );
    } catch (e, stack) {
      AppLogger.error('[GoodsDetailPage] _toggleFavorite error', error: e, stackTrace: stack);
      if (mounted) {
        setState(() {
          _isFavorite = !newStatus;
          _favoriteCount += newStatus ? -1 : 1;
          if (_favoriteCount < 0) _favoriteCount = 0;
        });
        safeSnackbar(
          '收藏失败',
          describeApiError(e, fallback: '操作失败，请重试'),
          snackPosition: SnackPosition.BOTTOM,
        );
      }
    } finally {
      _isTogglingFavorite = false;
    }
  }

  @override
  void dispose() {
    _pageController.dispose();
    _reviewControllerRef.release();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    if (_isLoading) {
      return Scaffold(
        appBar: AppBar(title: const Text('商品详情')),
        body: const Center(child: CircularProgressIndicator()),
      );
    }

    // 加载失败（断网/超时/服务端错误）：错误态 + 重试，绝不显示"商品不存在"
    if (_loadError.isNotEmpty) {
      return Scaffold(
        appBar: AppBar(title: const Text('商品详情')),
        body: Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(Icons.cloud_off_outlined, size: 64, color: Colors.grey.shade400),
                const SizedBox(height: 12),
                const Text('商品详情加载失败',
                    style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
                const SizedBox(height: 8),
                Text(
                  _loadError,
                  textAlign: TextAlign.center,
                  style: TextStyle(fontSize: 13, color: Colors.grey.shade600),
                ),
                const SizedBox(height: 20),
                ElevatedButton.icon(
                  onPressed: _loadDetail,
                  icon: const Icon(Icons.refresh, size: 18),
                  label: const Text('点击重试'),
                ),
                const SizedBox(height: 8),
                TextButton(
                  onPressed: () => Get.back(),
                  child: const Text('返回上一页'),
                ),
              ],
            ),
          ),
        ),
      );
    }

    if (_goods == null) {
      return Scaffold(
        appBar: AppBar(title: const Text('商品详情')),
        body: Center(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const Icon(Icons.error_outline, size: 64, color: Colors.grey),
              const SizedBox(height: 12),
              const Text('商品不存在或已被下架'),
              const SizedBox(height: 16),
              ElevatedButton(
                onPressed: () => Get.back(),
                child: const Text('返回'),
              ),
            ],
          ),
        ),
      );
    }

    final goods = _goods!;
    final isSeller = _authController.currentUser.value?.id == goods.sellerId;

    return Scaffold(
      appBar: AppBar(
        title: const Text('商品详情'),
        actions: [
          IconButton(
            icon: Icon(
              _isFavorite ? Icons.favorite : Icons.favorite_border,
              color: _isFavorite ? Colors.red : null,
            ),
            tooltip: _isFavorite ? '取消收藏' : '收藏商品',
            onPressed: _toggleFavorite,
          ),
          IconButton(
            icon: const Icon(Icons.share_outlined),
            onPressed: () {
              safeSnackbar('分享', '商品链接已复制到剪贴板', snackPosition: SnackPosition.BOTTOM);
            },
          ),
        ],
      ),
      bottomNavigationBar: _buildBottomBar(context, goods, isSeller),
      body: SingleChildScrollView(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // 顶部图片轮播
            _buildImageCarousel(goods),

            Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  // 价格与成色
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.baseline,
                    textBaseline: TextBaseline.alphabetic,
                    children: [
                      Text(
                        '¥',
                        style: TextStyle(
                          color: Colors.deepOrange.shade700,
                          fontWeight: FontWeight.bold,
                          fontSize: 18,
                        ),
                      ),
                      Text(
                        goods.price.toStringAsFixed(2),
                        style: TextStyle(
                          color: Colors.deepOrange.shade700,
                          fontWeight: FontWeight.bold,
                          fontSize: 28,
                        ),
                      ),
                      if (goods.originalPrice != null) ...[
                        const SizedBox(width: 8),
                        Text(
                          '原价 ¥${goods.originalPrice!.toStringAsFixed(2)}',
                          style: TextStyle(
                            decoration: TextDecoration.lineThrough,
                            color: Colors.grey.shade500,
                            fontSize: 13,
                          ),
                        ),
                      ],
                      const Spacer(),
                      Chip(
                        label: Text(goods.conditionLevel),
                        backgroundColor: theme.colorScheme.primaryContainer,
                        labelStyle: TextStyle(
                          color: theme.colorScheme.onPrimaryContainer,
                          fontSize: 12,
                          fontWeight: FontWeight.bold,
                        ),
                        padding: EdgeInsets.zero,
                        materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                      ),
                    ],
                  ),
                  const SizedBox(height: 12),

                  // 标题
                  Text(
                    goods.title,
                    style: theme.textTheme.titleLarge?.copyWith(
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  const SizedBox(height: 8),

                  // 浏览量、收藏量与地点元信息
                  Row(
                    children: [
                      Icon(Icons.visibility_outlined, size: 14, color: Colors.grey.shade500),
                      const SizedBox(width: 4),
                      Text(
                        '${goods.viewCount} 次浏览',
                        style: TextStyle(color: Colors.grey.shade600, fontSize: 12),
                      ),
                      const SizedBox(width: 12),
                      Icon(
                        _isFavorite ? Icons.favorite : Icons.favorite_border,
                        size: 14,
                        color: _isFavorite ? Colors.red : Colors.grey.shade500,
                      ),
                      const SizedBox(width: 4),
                      Text(
                        '$_favoriteCount 人收藏',
                        style: TextStyle(
                          color: _isFavorite ? Colors.red.shade700 : Colors.grey.shade600,
                          fontSize: 12,
                        ),
                      ),
                      const SizedBox(width: 16),
                      if (goods.location != null && goods.location!.isNotEmpty) ...[
                        Icon(Icons.place_outlined, size: 14, color: Colors.grey.shade500),
                        const SizedBox(width: 4),
                        Text(
                          goods.location!,
                          style: TextStyle(color: Colors.grey.shade600, fontSize: 12),
                        ),
                      ],
                    ],
                  ),
                  const Divider(height: 32),

                  // 商品详情描述
                  Text(
                    '商品说明',
                    style: theme.textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    goods.description.isNotEmpty ? goods.description : '卖家未添加详细说明',
                    style: theme.textTheme.bodyMedium?.copyWith(
                      height: 1.5,
                      color: Colors.grey.shade800,
                    ),
                  ),
                  const Divider(height: 32),

                  // 卖家校园与信用信息卡片
                  _buildSellerCard(goods, theme),
                  const SizedBox(height: 16),

                  // Stage 5-E: 商品历史评价展示区块
                  _buildGoodsReviewsSection(goods, theme),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// 图片轮播
  Widget _buildImageCarousel(GoodsDetailModel goods) {
    if (goods.images.isEmpty) {
      return Container(
        height: 280,
        color: Colors.grey.shade100,
        child: Center(
          child: Icon(Icons.image_outlined, size: 64, color: Colors.grey.shade300),
        ),
      );
    }

    return Stack(
      alignment: Alignment.bottomCenter,
      children: [
        SizedBox(
          height: 280,
          child: PageView.builder(
            controller: _pageController,
            itemCount: goods.images.length,
            onPageChanged: (index) {
              setState(() => _currentImageIndex = index);
            },
            itemBuilder: (context, index) {
              // 大图按可视区域宽度解码（避免把相机原图整张塞进图片缓存），并带加载占位
              return GoodsThumbnail(
                imageUrl: goods.images[index],
                height: 280,
                borderRadius: 0,
              );
            },
          ),
        ),
        // 指示标 (1/N)
        Positioned(
          bottom: 12,
          right: 16,
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
            decoration: BoxDecoration(
              color: Colors.black.withValues(alpha: 0.6),
              borderRadius: BorderRadius.circular(16),
            ),
            child: Text(
              '${_currentImageIndex + 1} / ${goods.images.length}',
              style: const TextStyle(color: Colors.white, fontSize: 12),
            ),
          ),
        ),
      ],
    );
  }

  /// 卖家信息与信用档案卡片
  Widget _buildSellerCard(GoodsDetailModel goods, ThemeData theme) {
    return Card(
      elevation: 0,
      color: theme.colorScheme.surfaceContainerHighest.withValues(alpha: 0.3),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          children: [
            Row(
              children: [
                CircleAvatar(
                  radius: 26,
                  backgroundImage: goods.sellerAvatar != null && goods.sellerAvatar!.isNotEmpty
                      ? NetworkImage(goods.sellerAvatar!)
                      : null,
                  child: goods.sellerAvatar == null ? const Icon(Icons.person) : null,
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        goods.sellerNickname.isNotEmpty ? goods.sellerNickname : goods.sellerUsername,
                        style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 16),
                      ),
                      const SizedBox(height: 4),
                      // 高校学生认证标签
                      if (goods.sellerVerified)
                        Row(
                          children: [
                            const Icon(Icons.verified, size: 14, color: Colors.green),
                            const SizedBox(width: 4),
                            Text(
                              '${goods.sellerSchoolName ?? "高校"} · 认证在校生',
                              style: const TextStyle(color: Colors.green, fontSize: 12),
                            ),
                          ],
                        )
                      else
                        const Text(
                          '未认证学生',
                          style: TextStyle(color: Colors.orange, fontSize: 12),
                        ),
                    ],
                  ),
                ),
              ],
            ),
            const Divider(height: 24),
            // 信用分与交易统计
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceAround,
              children: [
                _buildStatItem('信用积分', '${goods.sellerCreditScore}', Colors.deepOrange),
                _buildStatItem('已完成交易', '${goods.sellerTradeCount} 次', Colors.blue),
                _buildStatItem('好评次数', '${goods.sellerGoodReviewCount} 次', Colors.teal),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildStatItem(String label, String value, Color color) {
    return Column(
      children: [
        Text(value, style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16, color: color)),
        const SizedBox(height: 2),
        Text(label, style: const TextStyle(fontSize: 11, color: Colors.grey)),
      ],
    );
  }

  /// 底部动作条
  Widget _buildBottomBar(BuildContext context, GoodsDetailModel goods, bool isSeller) {
    // 「是否可下单」完全由状态枚举决定：已售出 / 已下架 / 交易中 / 未知状态都不是可买
    final isBuyable = GoodsStatus.fromCode(goods.status)?.isBuyable ?? false;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      decoration: BoxDecoration(
        color: Theme.of(context).scaffoldBackgroundColor,
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.05),
            blurRadius: 10,
            offset: const Offset(0, -2),
          ),
        ],
      ),
      child: SafeArea(
        child: isSeller
            ? Row(
                children: [
                  Expanded(
                    child: OutlinedButton(
                      onPressed: () async {
                        final targetStatus = isBuyable
                            ? GoodsStatus.offShelf.code
                            : GoodsStatus.onSale.code;
                        try {
                          await _goodsService.updateGoodsStatus(goods.id, targetStatus);
                          if (!mounted) return;
                          await _loadDetail();
                        } catch (e, stack) {
                          AppLogger.error('[GoodsDetailPage] 上下架失败', error: e, stackTrace: stack);
                          if (!mounted) return;
                          safeSnackbar(
                            '操作失败',
                            describeApiError(e, fallback: '商品状态修改失败'),
                            snackPosition: SnackPosition.BOTTOM,
                          );
                        }
                      },
                      child: Text(isBuyable ? '下架商品' : '重新上架'),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: ElevatedButton(
                      onPressed: () async {
                        // 卖家本人可直接进入编辑：把商品 ID 传给发布页（其据参数切换为编辑模式）
                        final updated =
                            await Get.toNamed(AppRoutes.goodsCreate, arguments: goods.id);
                        if (!mounted) return;
                        if (updated == true) _loadDetail();
                      },
                      child: const Text('编辑商品'),
                    ),
                  ),
                ],
              )
            : Row(
                children: [
                  InkWell(
                    onTap: _toggleFavorite,
                    borderRadius: BorderRadius.circular(8),
                    child: Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Icon(
                            _isFavorite ? Icons.favorite : Icons.favorite_border,
                            color: _isFavorite ? Colors.red : Colors.grey.shade700,
                            size: 22,
                          ),
                          const SizedBox(height: 2),
                          Text(
                            _isFavorite ? '已收藏' : '收藏',
                            style: TextStyle(
                              fontSize: 11,
                              color: _isFavorite ? Colors.red : Colors.grey.shade700,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    flex: 4,
                    child: OutlinedButton.icon(
                      icon: const Icon(Icons.chat_bubble_outline, size: 18),
                      label: const Text('联系卖家'),
                      style: OutlinedButton.styleFrom(
                        padding: const EdgeInsets.symmetric(vertical: 12),
                      ),
                      onPressed: () {
                        showDialog(
                          context: context,
                          builder: (ctx) => AlertDialog(
                            title: const Text('联系卖家'),
                            content: Text(
                                '已向卖家 ${goods.sellerNickname} 发起会话提醒。\n即时聊天 (IM) 系统将在后续版本上线！'),
                            actions: [
                              TextButton(
                                onPressed: () => Navigator.pop(ctx),
                                child: const Text('确定'),
                              ),
                            ],
                          ),
                        );
                      },
                    ),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    flex: 5,
                    child: ElevatedButton.icon(
                      icon: const Icon(Icons.shopping_bag_outlined, size: 18),
                      label: Text(isBuyable ? '立即下单' : '暂不可买'),
                      style: ElevatedButton.styleFrom(
                        padding: const EdgeInsets.symmetric(vertical: 12),
                        backgroundColor: isBuyable
                            ? Theme.of(context).colorScheme.primary
                            : Colors.grey.shade400,
                        foregroundColor: Colors.white,
                      ),
                      onPressed: isBuyable
                          ? () => _showCreateOrderSheet(context, goods)
                          : null,
                    ),
                  ),
                ],
              ),
      ),
    );
  }

  /// 弹出立即下单确认抽屉
  ///
  /// 抽屉内的两个输入控制器由抽屉自身的 State 持有并 dispose
  /// （原先在抽屉外部创建、从不释放，反复打开会持续泄漏）。
  void _showCreateOrderSheet(BuildContext context, GoodsDetailModel goods) {
    final user = _authController.currentUser.value;
    if (user == null) {
      Get.toNamed(AppRoutes.login);
      return;
    }

    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (sheetContext) => _CreateOrderSheet(goods: goods),
    );
  }

  /// 商品历史评价展示区块 (含评分星级、标签、内容、匿名脱敏及空状态)
  Widget _buildGoodsReviewsSection(GoodsDetailModel goods, ThemeData theme) {
    return Obx(() {
      final reviews = _reviewController.goodsReviewsMap[goods.id];
      final isLoading =
          _reviewController.loadingGoodsReviews.value && reviews == null;

      return Card(
        elevation: 0,
        color: theme.colorScheme.surfaceContainerHighest.withValues(alpha: 0.3),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text(
                    '商品评价 (${reviews?.length ?? 0})',
                    style: theme.textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  if (reviews != null && reviews.isNotEmpty)
                    Text(
                      '真实交易买家反馈',
                      style: TextStyle(fontSize: 12, color: Colors.grey.shade600),
                    ),
                ],
              ),
              const Divider(height: 20),
              if (isLoading)
                const Center(
                  child: Padding(
                    padding: EdgeInsets.symmetric(vertical: 16),
                    child: CircularProgressIndicator(),
                  ),
                )
              else if (reviews == null || reviews.isEmpty)
                Container(
                  width: double.infinity,
                  padding: const EdgeInsets.symmetric(vertical: 20),
                  child: Column(
                    children: [
                      Icon(Icons.comment_outlined,
                          size: 36, color: Colors.grey.shade400),
                      const SizedBox(height: 8),
                      Text(
                        '该商品暂无评价，交易完成后可发表评价',
                        style: TextStyle(fontSize: 13, color: Colors.grey.shade600),
                      ),
                    ],
                  ),
                )
              else
                ListView.separated(
                  shrinkWrap: true,
                  physics: const NeverScrollableScrollPhysics(),
                  itemCount: reviews.length,
                  separatorBuilder: (ctx, idx) => const Divider(height: 16),
                  itemBuilder: (ctx, index) {
                    final review = reviews[index];
                    return Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            CircleAvatar(
                              radius: 14,
                              backgroundColor:
                                  theme.colorScheme.primaryContainer,
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
                            Text(
                              review.displayNickname,
                              style: const TextStyle(
                                fontSize: 13,
                                fontWeight: FontWeight.bold,
                              ),
                            ),
                            if (review.isAnonymous) ...[
                              const SizedBox(width: 6),
                              Container(
                                padding: const EdgeInsets.symmetric(
                                    horizontal: 5, vertical: 1),
                                decoration: BoxDecoration(
                                  color: Colors.grey.shade200,
                                  borderRadius: BorderRadius.circular(4),
                                ),
                                child: const Text(
                                  '匿名',
                                  style: TextStyle(
                                      fontSize: 10, color: Colors.black54),
                                ),
                              ),
                            ],
                            const Spacer(),
                            // 星级展示
                            Row(
                              mainAxisSize: MainAxisSize.min,
                              children: List.generate(5, (starIdx) {
                                final filled = starIdx < review.score;
                                return Icon(
                                  filled
                                      ? Icons.star_rounded
                                      : Icons.star_outline_rounded,
                                  size: 14,
                                  color: filled
                                      ? Colors.amber.shade600
                                      : Colors.grey.shade300,
                                );
                              }),
                            ),
                          ],
                        ),
                        if (review.tags.isNotEmpty) ...[
                          const SizedBox(height: 6),
                          Wrap(
                            spacing: 6,
                            runSpacing: 4,
                            children: review.tags.map((tag) {
                              return Container(
                                padding: const EdgeInsets.symmetric(
                                    horizontal: 6, vertical: 2),
                                decoration: BoxDecoration(
                                  color: Colors.white,
                                  borderRadius: BorderRadius.circular(4),
                                  border:
                                      Border.all(color: Colors.grey.shade300),
                                ),
                                child: Text(
                                  tag,
                                  style: TextStyle(
                                      fontSize: 11,
                                      color: Colors.grey.shade800),
                                ),
                              );
                            }).toList(),
                          ),
                        ],
                        if (review.content != null &&
                            review.content!.isNotEmpty) ...[
                          const SizedBox(height: 6),
                          Text(
                            review.content!,
                            style: const TextStyle(fontSize: 13, height: 1.4),
                          ),
                        ],
                        if (review.createdTime != null) ...[
                          const SizedBox(height: 4),
                          Align(
                            alignment: Alignment.centerRight,
                            child: Text(
                              review.createdTime!,
                              style: TextStyle(
                                  fontSize: 11, color: Colors.grey.shade500),
                            ),
                          ),
                        ],
                      ],
                    );
                  },
                ),
            ],
          ),
        ),
      );
    });
  }
}

/// 立即下单确认抽屉（独立 StatefulWidget）
///
/// 面交地点 / 买家留言两个输入框的控制器由本 State 持有并释放，
/// 与抽屉的打开-关闭生命周期严格对齐；提交逻辑与改造前完全一致。
class _CreateOrderSheet extends StatefulWidget {
  const _CreateOrderSheet({required this.goods});

  final GoodsDetailModel goods;

  @override
  State<_CreateOrderSheet> createState() => _CreateOrderSheetState();
}

class _CreateOrderSheetState extends State<_CreateOrderSheet> {
  late final TextEditingController _locationController;
  late final TextEditingController _messageController;
  bool _isSubmitting = false;

  /// 下单专用订单控制器（tag 区分于"我的订单"列表页实例）。
  ///
  /// 用独立实例的原因：共用同一个控制器时，这里创建订单失败写入的 errorMessage
  /// 会跟着实例回到"我的订单"页面，让空列表显示成"创建订单失败"错误态。
  late final PageControllerRef<OrderController> _orderControllerRef;
  OrderController get _orderController => _orderControllerRef.controller;

  @override
  void initState() {
    super.initState();
    _locationController =
        TextEditingController(text: widget.goods.location ?? '');
    _messageController = TextEditingController();
    _orderControllerRef = PageControllerScope.acquire<OrderController>(
      () => OrderController(),
      tag: OrderController.tagCreate,
    );
  }

  @override
  void dispose() {
    _locationController.dispose();
    _messageController.dispose();
    _orderControllerRef.release();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_isSubmitting) return;
    final meetLoc = _locationController.text.trim();
    final msg = _messageController.text.trim();

    setState(() => _isSubmitting = true);
    try {
      final newOrder = await _orderController.createOrder(
        goodsId: widget.goods.id,
        meetLocation: meetLoc.isNotEmpty ? meetLoc : null,
        buyerMessage: msg.isNotEmpty ? msg : null,
      );

      // 下单是长耗时操作：用户可能已经关闭抽屉并离开商品详情页，逐处判断挂载
      if (!mounted) return;

      if (newOrder != null) {
        final String orderNo = newOrder.orderNo;
        Navigator.pop(context);
        Get.toNamed(AppRoutes.orderDetail, arguments: newOrder.id);
        safeSnackbar(
          '下单成功',
          '订单 $orderNo 已生成，等待卖家接单确认',
          snackPosition: SnackPosition.BOTTOM,
        );
      } else {
        safeSnackbar(
          '下单失败',
          _orderController.errorMessage.value.isNotEmpty
              ? _orderController.errorMessage.value
              : '创建订单失败，请稍后重试',
          snackPosition: SnackPosition.BOTTOM,
        );
      }
    } finally {
      if (mounted) setState(() => _isSubmitting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final goods = widget.goods;
    final theme = Theme.of(context);

    return Padding(
      padding: EdgeInsets.only(
        left: 20,
        right: 20,
        top: 20,
        bottom: MediaQuery.of(context).viewInsets.bottom + 20,
      ),
      child: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                const Icon(Icons.shopping_bag_outlined, color: Colors.blueAccent),
                const SizedBox(width: 8),
                const Text(
                  '确认购买意向与下单',
                  style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
                ),
                const Spacer(),
                IconButton(
                  icon: const Icon(Icons.close),
                  onPressed: () => Navigator.pop(context),
                ),
              ],
            ),
            const Divider(height: 20),

            // 商品快照简要展示
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: Colors.grey.shade50,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: Colors.grey.shade200),
              ),
              child: Row(
                children: [
                  ClipRRect(
                    borderRadius: BorderRadius.circular(8),
                    child: SizedBox(
                      width: 60,
                      height: 60,
                      child: GoodsThumbnail(
                        imageUrl: goods.images.isNotEmpty ? goods.images.first : null,
                        width: 60,
                        height: 60,
                        borderRadius: 0,
                      ),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          goods.title,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 14),
                        ),
                        const SizedBox(height: 4),
                        Text(
                          '¥${goods.price.toStringAsFixed(2)}',
                          style: TextStyle(
                            color: theme.colorScheme.primary,
                            fontSize: 16,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 16),

            // 面交地点输入
            TextField(
              controller: _locationController,
              decoration: const InputDecoration(
                labelText: '约定面交地点',
                hintText: '如：二食堂门口、图书馆大厅',
                prefixIcon: Icon(Icons.place_outlined),
                border: OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 14),

            // 买家留言输入
            TextField(
              controller: _messageController,
              maxLines: 2,
              decoration: const InputDecoration(
                labelText: '买家留言 (选填)',
                hintText: '如希望面交时间、当面验货注意事项等',
                prefixIcon: Icon(Icons.comment_outlined),
                border: OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 20),

            // 确认下单按钮
            ElevatedButton(
              style: ElevatedButton.styleFrom(
                padding: const EdgeInsets.symmetric(vertical: 14),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(10),
                ),
              ),
              onPressed: _isSubmitting ? null : _submit,
              child: const Text('确认下单', style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
            ),
          ],
        ),
      ),
    );
  }
}
