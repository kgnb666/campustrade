import 'package:flutter/material.dart';
import 'package:get/get.dart';
import '../models/ai_model.dart';
import '../models/category_model.dart';
import '../services/ai_service.dart';
import '../utils/api_error.dart';

/// DeepSeek AI 商品发布助手底部弹窗
class AiGoodsAssistantSheet {
  static final AiService _aiService = AiService();
  static bool _isSheetOpen = false;

  /// 1. AI 帮写描述助手
  static Future<void> showDescriptionAssistant({
    required BuildContext context,
    required String title,
    String? roughDesc,
    String? conditionLevel,
    required void Function(String adoptedTitle, String adoptedDesc) onAdopt,
  }) async {
    if (_isSheetOpen) return;
    if (title.trim().isEmpty) {
      Get.snackbar('提示', '请先填写商品标题，AI 才能为您构思描述');
      return;
    }

    _isSheetOpen = true;
    try {
      await showModalBottomSheet(
        context: context,
        isScrollControlled: true,
        backgroundColor: Colors.transparent,
        builder: (ctx) => _AiAssistantContainer(
          title: 'DeepSeek AI 帮写描述',
          icon: Icons.auto_awesome,
          child: _AiDescriptionContent(
            title: title,
            roughDesc: roughDesc,
            conditionLevel: conditionLevel,
            onAdopt: (newTitle, newDesc) {
              Navigator.pop(ctx);
              onAdopt(newTitle, newDesc);
              Get.snackbar(
                '已采纳',
                'AI 生成的描述已填入发布表单',
                snackPosition: SnackPosition.BOTTOM,
                backgroundColor: Colors.green.shade600,
                colorText: Colors.white,
              );
            },
          ),
        ),
      );
    } finally {
      _isSheetOpen = false;
    }
  }

  /// 2. AI 智能分类助手
  static Future<void> showCategoryAssistant({
    required BuildContext context,
    required String title,
    String? desc,
    required List<CategoryModel> availableCategories,
    required void Function(CategoryModel adoptedCategory) onAdopt,
  }) async {
    if (_isSheetOpen) return;
    if (title.trim().isEmpty) {
      Get.snackbar('提示', '请先输入商品标题，AI 才能精准分析所属品类');
      return;
    }

    _isSheetOpen = true;
    try {
      await showModalBottomSheet(
        context: context,
        isScrollControlled: true,
        backgroundColor: Colors.transparent,
        builder: (ctx) => _AiAssistantContainer(
          title: 'DeepSeek AI 智能分类',
          icon: Icons.category,
          child: _AiCategoryContent(
            title: title,
            desc: desc,
            availableCategories: availableCategories,
            onAdopt: (cat) {
              Navigator.pop(ctx);
              onAdopt(cat);
              Get.snackbar(
                '已采纳',
                '已自动选中推荐分类【${cat.name}】',
                snackPosition: SnackPosition.BOTTOM,
                backgroundColor: Colors.green.shade600,
                colorText: Colors.white,
              );
            },
          ),
        ),
      );
    } finally {
      _isSheetOpen = false;
    }
  }

  /// 3. AI 智能估价助手
  static Future<void> showPriceAssistant({
    required BuildContext context,
    required String title,
    String? desc,
    double? originalPrice,
    String? conditionLevel,
    required void Function(double adoptedPrice) onAdopt,
  }) async {
    if (_isSheetOpen) return;
    if (title.trim().isEmpty) {
      Get.snackbar('提示', '请先输入商品标题，AI 才能进行市场估价');
      return;
    }

    _isSheetOpen = true;
    try {
      await showModalBottomSheet(
        context: context,
        isScrollControlled: true,
        backgroundColor: Colors.transparent,
        builder: (ctx) => _AiAssistantContainer(
          title: 'DeepSeek AI 价格建议',
          icon: Icons.price_check,
          child: _AiPriceContent(
            title: title,
            desc: desc,
            originalPrice: originalPrice,
            conditionLevel: conditionLevel,
            onAdopt: (price) {
              Navigator.pop(ctx);
              onAdopt(price);
              Get.snackbar(
                '已采纳',
                '已填入 AI 建议售价 ¥$price',
                snackPosition: SnackPosition.BOTTOM,
                backgroundColor: Colors.green.shade600,
                colorText: Colors.white,
              );
            },
          ),
        ),
      );
    } finally {
      _isSheetOpen = false;
    }
  }
}

/// 助手弹窗外壳
class _AiAssistantContainer extends StatelessWidget {
  final String title;
  final IconData icon;
  final Widget child;

  const _AiAssistantContainer({
    required this.title,
    required this.icon,
    required this.child,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.only(top: 12, left: 16, right: 16, bottom: 24),
      decoration: const BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      child: SafeArea(
        top: false,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Center(
              child: Container(
                width: 40,
                height: 4,
                margin: const EdgeInsets.only(bottom: 12),
                decoration: BoxDecoration(
                  color: Colors.grey.shade300,
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
            ),
            Row(
              children: [
                Container(
                  padding: const EdgeInsets.all(6),
                  decoration: BoxDecoration(
                    color: Colors.indigo.shade50,
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Icon(icon, color: Colors.indigo, size: 20),
                ),
                const SizedBox(width: 8),
                Text(
                  title,
                  style: const TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const Spacer(),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                  decoration: BoxDecoration(
                    color: Colors.indigo.shade50,
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: const Text(
                    'DeepSeek V3',
                    style: TextStyle(
                      fontSize: 11,
                      color: Colors.indigo,
                      fontWeight: FontWeight.w500,
                    ),
                  ),
                ),
                IconButton(
                  icon: const Icon(Icons.close, size: 20),
                  onPressed: () => Navigator.pop(context),
                ),
              ],
            ),
            const Divider(height: 16),
            child,
          ],
        ),
      ),
    );
  }
}

/// 描述生成交互
class _AiDescriptionContent extends StatefulWidget {
  final String title;
  final String? roughDesc;
  final String? conditionLevel;
  final void Function(String newTitle, String newDesc) onAdopt;

  const _AiDescriptionContent({
    required this.title,
    this.roughDesc,
    this.conditionLevel,
    required this.onAdopt,
  });

  @override
  State<_AiDescriptionContent> createState() => _AiDescriptionContentState();
}

class _AiDescriptionContentState extends State<_AiDescriptionContent> {
  bool _loading = true;
  bool _fetching = false;
  String? _error;
  AiDescriptionModel? _result;

  @override
  void initState() {
    super.initState();
    _fetch();
  }

  Future<void> _fetch() async {
    if (_fetching) return;
    _fetching = true;
    setState(() {
      _loading = true;
      _error = null;
    });

    try {
      final res = await AiGoodsAssistantSheet._aiService.generateDescription(
        title: widget.title,
        roughDescription: widget.roughDesc,
        conditionLevel: widget.conditionLevel,
      );
      if (mounted) {
        setState(() {
          _result = res;
          _loading = false;
        });
      }
    } catch (e) {
      if (mounted) {
        final String msg = describeApiError(
          e,
          fallback: 'AI 服务暂不可用，请稍后重试',
          timeoutMessage: 'AI 请求超时，请检查网络后重试',
        );
        setState(() {
          _error = msg;
          _loading = false;
        });
      }
    } finally {
      _fetching = false;
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return const Padding(
        padding: EdgeInsets.symmetric(vertical: 40),
        child: Column(
          children: [
            CircularProgressIndicator(),
            SizedBox(height: 16),
            Text('AI 正在智能构思文案与提炼卖点...', style: TextStyle(color: Colors.grey)),
          ],
        ),
      );
    }

    if (_error != null || _result == null) {
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 24),
        child: Column(
          children: [
            Icon(Icons.error_outline, color: Colors.red.shade400, size: 40),
            const SizedBox(height: 8),
            Text('生成失败: $_error', style: const TextStyle(color: Colors.red)),
            const SizedBox(height: 12),
            OutlinedButton.icon(
              onPressed: _fetching ? null : _fetch,
              icon: const Icon(Icons.refresh),
              label: const Text('重试'),
            ),
          ],
        ),
      );
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (_result!.degraded) ...[
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
            margin: const EdgeInsets.only(bottom: 8),
            decoration: BoxDecoration(
              color: Colors.amber.shade50,
              borderRadius: BorderRadius.circular(4),
              border: Border.all(color: Colors.amber.shade300),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(Icons.info_outline, size: 14, color: Colors.amber.shade900),
                const SizedBox(width: 4),
                Text(
                  '规则兜底生成：请核对内容',
                  style: TextStyle(fontSize: 11, color: Colors.amber.shade900, fontWeight: FontWeight.w500),
                ),
              ],
            ),
          ),
        ],
        if (_result!.highlights.isNotEmpty) ...[
          const Text('提炼卖点标签：', style: TextStyle(fontSize: 12, color: Colors.grey)),
          const SizedBox(height: 6),
          Wrap(
            spacing: 6,
            children: _result!.highlights
                .map((h) => Chip(
                      label: Text(h, style: const TextStyle(fontSize: 11)),
                      padding: EdgeInsets.zero,
                      materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                      backgroundColor: Colors.indigo.shade50,
                    ))
                .toList(),
          ),
          const SizedBox(height: 12),
        ],
        const Text('生成商品描述：', style: TextStyle(fontSize: 12, color: Colors.grey)),
        const SizedBox(height: 6),
        Container(
          width: double.infinity,
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            color: Colors.grey.shade50,
            borderRadius: BorderRadius.circular(8),
            border: Border.all(color: Colors.grey.shade200),
          ),
          child: Text(
            _result!.description,
            style: const TextStyle(fontSize: 14, height: 1.5),
          ),
        ),
        if (_result!.costMs != null) ...[
          const SizedBox(height: 8),
          Text(
            '耗时: ${_result!.costMs}ms',
            style: TextStyle(fontSize: 11, color: Colors.grey.shade500),
          ),
        ],
        const SizedBox(height: 16),
        Row(
          children: [
            Expanded(
              child: OutlinedButton(
                onPressed: () => Navigator.pop(context),
                child: const Text('放弃'),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: ElevatedButton.icon(
                onPressed: () => widget.onAdopt(
                  _result!.title.isNotEmpty ? _result!.title : widget.title,
                  _result!.description,
                ),
                icon: const Icon(Icons.check),
                label: const Text('采纳并填入'),
              ),
            ),
          ],
        ),
      ],
    );
  }
}

/// 分类推荐交互
class _AiCategoryContent extends StatefulWidget {
  final String title;
  final String? desc;
  final List<CategoryModel> availableCategories;
  final void Function(CategoryModel adoptedCategory) onAdopt;

  const _AiCategoryContent({
    required this.title,
    this.desc,
    required this.availableCategories,
    required this.onAdopt,
  });

  @override
  State<_AiCategoryContent> createState() => _AiCategoryContentState();
}

class _AiCategoryContentState extends State<_AiCategoryContent> {
  bool _loading = true;
  bool _fetching = false;
  String? _error;
  AiCategoryModel? _result;
  CategoryModel? _matchedCategory;

  @override
  void initState() {
    super.initState();
    _fetch();
  }

  Future<void> _fetch() async {
    if (_fetching) return;
    _fetching = true;
    setState(() {
      _loading = true;
      _error = null;
    });

    try {
      final res = await AiGoodsAssistantSheet._aiService.recommendCategory(
        title: widget.title,
        description: widget.desc,
      );
      if (mounted) {
        CategoryModel? matched;
        if (res != null) {
          if (res.categoryId != null) {
            matched = widget.availableCategories.firstWhereOrNull(
              (c) => c.id == res.categoryId,
            );
          }
          matched ??= widget.availableCategories.firstWhereOrNull(
            (c) => c.name.contains(res.categoryName) || res.categoryName.contains(c.name),
          );
        }

        setState(() {
          _result = res;
          _matchedCategory = matched;
          _loading = false;
        });
      }
    } catch (e) {
      if (mounted) {
        final String msg = describeApiError(
          e,
          fallback: 'AI 服务暂不可用，请稍后重试',
          timeoutMessage: 'AI 请求超时，请检查网络后重试',
        );
        setState(() {
          _error = msg;
          _loading = false;
        });
      }
    } finally {
      _fetching = false;
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return const Padding(
        padding: EdgeInsets.symmetric(vertical: 40),
        child: Column(
          children: [
            CircularProgressIndicator(),
            SizedBox(height: 16),
            Text('AI 正在分析商品特征并匹配分类...', style: TextStyle(color: Colors.grey)),
          ],
        ),
      );
    }

    if (_error != null || _result == null) {
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 24),
        child: Column(
          children: [
            Icon(Icons.error_outline, color: Colors.red.shade400, size: 40),
            const SizedBox(height: 8),
            Text('分析失败: $_error', style: const TextStyle(color: Colors.red)),
            const SizedBox(height: 12),
            OutlinedButton.icon(
              onPressed: _fetching ? null : _fetch,
              icon: const Icon(Icons.refresh),
              label: const Text('重试'),
            ),
          ],
        ),
      );
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (_result!.degraded) ...[
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
            margin: const EdgeInsets.only(bottom: 8),
            decoration: BoxDecoration(
              color: Colors.amber.shade50,
              borderRadius: BorderRadius.circular(4),
              border: Border.all(color: Colors.amber.shade300),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(Icons.info_outline, size: 14, color: Colors.amber.shade900),
                const SizedBox(width: 4),
                Text(
                  '规则兜底生成：请核对推荐分类',
                  style: TextStyle(fontSize: 11, color: Colors.amber.shade900, fontWeight: FontWeight.w500),
                ),
              ],
            ),
          ),
        ],
        Row(
          children: [
            const Text('推荐分类：', style: TextStyle(fontSize: 14, color: Colors.grey)),
            Text(
              _result!.categoryName,
              style: const TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.bold,
                color: Colors.indigo,
              ),
            ),
          ],
        ),
        const SizedBox(height: 10),
        Container(
          width: double.infinity,
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            color: Colors.grey.shade50,
            borderRadius: BorderRadius.circular(8),
            border: Border.all(color: Colors.grey.shade200),
          ),
          child: Text(
            '推荐理由：${_result!.reason}',
            style: const TextStyle(fontSize: 13, height: 1.4),
          ),
        ),
        if (_matchedCategory == null) ...[
          const SizedBox(height: 8),
          Text(
            '提示: 平台未找到完全匹配的分类项，可手动选择接近分类',
            style: TextStyle(fontSize: 11, color: Colors.amber.shade800),
          ),
        ],
        const SizedBox(height: 16),
        Row(
          children: [
            Expanded(
              child: OutlinedButton(
                onPressed: () => Navigator.pop(context),
                child: const Text('放弃'),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: ElevatedButton.icon(
                onPressed: _matchedCategory != null
                    ? () => widget.onAdopt(_matchedCategory!)
                    : null,
                icon: const Icon(Icons.check),
                label: const Text('采纳分类'),
              ),
            ),
          ],
        ),
      ],
    );
  }
}

/// 估价助手交互
class _AiPriceContent extends StatefulWidget {
  final String title;
  final String? desc;
  final double? originalPrice;
  final String? conditionLevel;
  final void Function(double price) onAdopt;

  const _AiPriceContent({
    required this.title,
    this.desc,
    this.originalPrice,
    this.conditionLevel,
    required this.onAdopt,
  });

  @override
  State<_AiPriceContent> createState() => _AiPriceContentState();
}

class _AiPriceContentState extends State<_AiPriceContent> {
  bool _loading = true;
  bool _fetching = false;
  String? _error;
  AiPriceModel? _result;

  @override
  void initState() {
    super.initState();
    _fetch();
  }

  Future<void> _fetch() async {
    if (_fetching) return;
    _fetching = true;
    setState(() {
      _loading = true;
      _error = null;
    });

    try {
      final res = await AiGoodsAssistantSheet._aiService.suggestPrice(
        title: widget.title,
        description: widget.desc,
        originalPrice: widget.originalPrice,
        conditionLevel: widget.conditionLevel,
      );
      if (mounted) {
        setState(() {
          _result = res;
          _loading = false;
        });
      }
    } catch (e) {
      if (mounted) {
        final String msg = describeApiError(
          e,
          fallback: 'AI 服务暂不可用，请稍后重试',
          timeoutMessage: 'AI 请求超时，请检查网络后重试',
        );
        setState(() {
          _error = msg;
          _loading = false;
        });
      }
    } finally {
      _fetching = false;
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return const Padding(
        padding: EdgeInsets.symmetric(vertical: 40),
        child: Column(
          children: [
            CircularProgressIndicator(),
            SizedBox(height: 16),
            Text('AI 正在评估校园二手行情与合理价位...', style: TextStyle(color: Colors.grey)),
          ],
        ),
      );
    }

    if (_error != null || _result == null) {
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 24),
        child: Column(
          children: [
            Icon(Icons.error_outline, color: Colors.red.shade400, size: 40),
            const SizedBox(height: 8),
            Text('评估失败: $_error', style: const TextStyle(color: Colors.red)),
            const SizedBox(height: 12),
            OutlinedButton.icon(
              onPressed: _fetching ? null : _fetch,
              icon: const Icon(Icons.refresh),
              label: const Text('重试'),
            ),
          ],
        ),
      );
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (_result!.degraded) ...[
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
            margin: const EdgeInsets.only(bottom: 8),
            decoration: BoxDecoration(
              color: Colors.amber.shade50,
              borderRadius: BorderRadius.circular(4),
              border: Border.all(color: Colors.amber.shade300),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(Icons.info_outline, size: 14, color: Colors.amber.shade900),
                const SizedBox(width: 4),
                Text(
                  '规则兜底估价：请核对建议售价',
                  style: TextStyle(fontSize: 11, color: Colors.amber.shade900, fontWeight: FontWeight.w500),
                ),
              ],
            ),
          ),
        ],
        Row(
          children: [
            const Text('建议售价：', style: TextStyle(fontSize: 14, color: Colors.grey)),
            Text(
              '¥${_result!.suggestedPrice.toStringAsFixed(2)}',
              style: const TextStyle(
                fontSize: 22,
                fontWeight: FontWeight.bold,
                color: Colors.deepOrange,
              ),
            ),
            const SizedBox(width: 12),
            Text(
              '区间: ¥${_result!.minPrice.toStringAsFixed(0)} - ¥${_result!.maxPrice.toStringAsFixed(0)}',
              style: TextStyle(fontSize: 12, color: Colors.grey.shade600),
            ),
          ],
        ),
        const SizedBox(height: 10),
        Container(
          width: double.infinity,
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            color: Colors.grey.shade50,
            borderRadius: BorderRadius.circular(8),
            border: Border.all(color: Colors.grey.shade200),
          ),
          child: Text(
            '估价依据：${_result!.reason}',
            style: const TextStyle(fontSize: 13, height: 1.4),
          ),
        ),
        const SizedBox(height: 16),
        Row(
          children: [
            Expanded(
              child: OutlinedButton(
                onPressed: () => Navigator.pop(context),
                child: const Text('放弃'),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: ElevatedButton.icon(
                onPressed: () => widget.onAdopt(_result!.suggestedPrice),
                icon: const Icon(Icons.check),
                label: const Text('采纳建议价'),
              ),
            ),
          ],
        ),
      ],
    );
  }
}
