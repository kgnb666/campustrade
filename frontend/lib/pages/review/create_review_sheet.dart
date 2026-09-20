import 'package:flutter/material.dart';
import 'package:get/get.dart';
import '../../controllers/review_controller.dart';
import '../../models/order.dart';
import '../../utils/name_utils.dart';

/// 评价发表模态底板组件 (CreateReviewSheet)
/// 支持 1~5 星交互、500字实时计数、白名单标签点选、匿名保护及防重复提交
class CreateReviewSheet extends StatefulWidget {
  final OrderVO order;
  final bool isBuyer;
  final VoidCallback? onSuccess;

  const CreateReviewSheet({
    super.key,
    required this.order,
    required this.isBuyer,
    this.onSuccess,
  });

  /// 静态弹出辅助方法
  static Future<bool?> show(
    BuildContext context, {
    required OrderVO order,
    required bool isBuyer,
    VoidCallback? onSuccess,
  }) {
    return showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (ctx) => CreateReviewSheet(
        order: order,
        isBuyer: isBuyer,
        onSuccess: onSuccess,
      ),
    );
  }

  @override
  State<CreateReviewSheet> createState() => _CreateReviewSheetState();
}

class _CreateReviewSheetState extends State<CreateReviewSheet> {
  late final ReviewController _controller;
  late final TextEditingController _textEditingController;

  @override
  void initState() {
    super.initState();
    _controller = Get.isRegistered<ReviewController>()
        ? Get.find<ReviewController>()
        : Get.put(ReviewController());

    _textEditingController = TextEditingController(text: _controller.content.value);
    // 重置表单但保留控制器实例
    _controller.resetForm();
  }

  @override
  void dispose() {
    _textEditingController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final targetUser = widget.isBuyer ? widget.order.seller : widget.order.buyer;
    final targetRoleText = widget.isBuyer ? '卖家' : '买家';
    final targetNickname = targetUser?.nickname ??
        targetUser?.username ??
        (widget.isBuyer ? '卖家同学' : '买家同学');
    final targetAvatar = targetUser?.avatar;

    final presetTags = _controller.getPresetTags(isBuyer: widget.isBuyer);

    return Container(
      padding: EdgeInsets.only(
        bottom: MediaQuery.of(context).viewInsets.bottom,
      ),
      decoration: const BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      child: SafeArea(
        top: false,
        child: SingleChildScrollView(
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // 顶部指示条与关闭按钮
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
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text(
                    '评价本次交易',
                    style: theme.textTheme.titleLarge?.copyWith(
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  IconButton(
                    icon: const Icon(Icons.close),
                    onPressed: () => Navigator.of(context).pop(false),
                    tooltip: '关闭',
                  ),
                ],
              ),
              const SizedBox(height: 8),

              // 1. 被评价对象信息展示卡片
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: theme.colorScheme.surfaceContainerHighest.withValues(alpha: 0.3),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Row(
                  children: [
                    CircleAvatar(
                      radius: 20,
                      backgroundColor: theme.colorScheme.primaryContainer,
                      backgroundImage: targetAvatar != null && targetAvatar.isNotEmpty
                          ? NetworkImage(targetAvatar)
                          : null,
                      child: targetAvatar == null || targetAvatar.isEmpty
                          ? Text(
                              initialOf(targetNickname, '用户'),
                              style: TextStyle(
                                fontWeight: FontWeight.bold,
                                color: theme.colorScheme.onPrimaryContainer,
                              ),
                            )
                          : null,
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Row(
                            children: [
                              Text(
                                targetNickname,
                                style: const TextStyle(
                                  fontWeight: FontWeight.bold,
                                  fontSize: 15,
                                ),
                              ),
                              const SizedBox(width: 6),
                              Container(
                                padding: const EdgeInsets.symmetric(
                                    horizontal: 6, vertical: 1),
                                decoration: BoxDecoration(
                                  color: Colors.blue.withValues(alpha: 0.1),
                                  borderRadius: BorderRadius.circular(4),
                                ),
                                child: Text(
                                  targetRoleText,
                                  style: const TextStyle(
                                    fontSize: 11,
                                    color: Colors.blue,
                                    fontWeight: FontWeight.w500,
                                  ),
                                ),
                              ),
                            ],
                          ),
                          const SizedBox(height: 2),
                          Text(
                            '商品: ${widget.order.goodsTitleSnapshot}',
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              fontSize: 12,
                              color: Colors.grey.shade600,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 16),

              // 2. 星级评分交互 (强制 1~5 星，不可默认 5 星)
              Center(
                child: Column(
                  children: [
                    const Text(
                      '轻触星级进行评分',
                      style: TextStyle(
                        fontSize: 13,
                        color: Colors.grey,
                      ),
                    ),
                    const SizedBox(height: 8),
                    Obx(() {
                      final currentScore = _controller.selectedScore.value;
                      return Row(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: List.generate(5, (index) {
                          final starIndex = index + 1;
                          final isSelected = starIndex <= currentScore;
                          return IconButton(
                            iconSize: 36,
                            padding: const EdgeInsets.symmetric(horizontal: 4),
                            onPressed: _controller.isSubmitting
                                ? null
                                : () => _controller.setScore(starIndex),
                            icon: Icon(
                              isSelected ? Icons.star_rounded : Icons.star_outline_rounded,
                              color: isSelected ? Colors.amber.shade600 : Colors.grey.shade400,
                            ),
                          );
                        }),
                      );
                    }),
                    const SizedBox(height: 4),
                    Obx(() {
                      final score = _controller.selectedScore.value;
                      String desc = '未选择星级 (必填)';
                      Color textColor = Colors.grey;
                      switch (score) {
                        case 1:
                          desc = '1星 - 非常差 (将扣除对方信用分)';
                          textColor = Colors.red.shade700;
                          break;
                        case 2:
                          desc = '2星 - 较差 (将扣除对方信用分)';
                          textColor = Colors.deepOrange;
                          break;
                        case 3:
                          desc = '3星 - 一般 (信用分不变)';
                          textColor = Colors.amber.shade800;
                          break;
                        case 4:
                          desc = '4星 - 满意 (将为对方增加信用分)';
                          textColor = Colors.teal;
                          break;
                        case 5:
                          desc = '5星 - 非常满意 (将为对方增加信用分)';
                          textColor = Colors.green.shade700;
                          break;
                      }
                      return Text(
                        desc,
                        style: TextStyle(
                          fontSize: 13,
                          fontWeight: FontWeight.w600,
                          color: textColor,
                        ),
                      );
                    }),
                  ],
                ),
              ),
              const SizedBox(height: 16),

              // 3. 评价标签 (白名单多选)
              const Text(
                '快捷标签',
                style: TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.bold,
                ),
              ),
              const SizedBox(height: 8),
              Obx(() {
                final selected = _controller.selectedTags;
                return Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: presetTags.map((tag) {
                    final isChecked = selected.contains(tag);
                    return FilterChip(
                      label: Text(tag),
                      selected: isChecked,
                      onSelected: _controller.isSubmitting
                          ? null
                          : (_) => _controller.toggleTag(tag),
                      selectedColor: theme.colorScheme.primaryContainer,
                      checkmarkColor: theme.colorScheme.primary,
                      labelStyle: TextStyle(
                        fontSize: 12,
                        color: isChecked
                            ? theme.colorScheme.primary
                            : Colors.black87,
                        fontWeight: isChecked ? FontWeight.bold : FontWeight.normal,
                      ),
                    );
                  }).toList(),
                );
              }),
              const SizedBox(height: 16),

              // 4. 评价文本内容 (最多500字，实时字数统计)
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  const Text(
                    '评价内容',
                    style: TextStyle(
                      fontSize: 14,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  Obx(() => Text(
                        '${_controller.content.value.length} / 500',
                        style: TextStyle(
                          fontSize: 12,
                          color: _controller.content.value.length >= 500
                              ? Colors.red
                              : Colors.grey,
                        ),
                      )),
                ],
              ),
              const SizedBox(height: 6),
              TextField(
                controller: _textEditingController,
                maxLines: 4,
                maxLength: 500,
                enabled: !_controller.isSubmitting,
                decoration: InputDecoration(
                  hintText: '认真描述本次交易体验，如商品成色、守时情况、沟通态度等 (选填)...',
                  hintStyle: TextStyle(color: Colors.grey.shade400, fontSize: 13),
                  counterText: '',
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(10),
                    borderSide: BorderSide(color: Colors.grey.shade300),
                  ),
                  contentPadding: const EdgeInsets.all(12),
                ),
                onChanged: (val) => _controller.setContent(val),
              ),
              const SizedBox(height: 12),

              // 5. 匿名评价开关
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
                decoration: BoxDecoration(
                  color: Colors.grey.shade50,
                  borderRadius: BorderRadius.circular(10),
                  border: Border.all(color: Colors.grey.shade200),
                ),
                child: Row(
                  children: [
                    const Icon(Icons.security, size: 20, color: Colors.grey),
                    const SizedBox(width: 8),
                    const Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            '匿名评价',
                            style: TextStyle(fontSize: 14, fontWeight: FontWeight.w500),
                          ),
                          Text(
                            '匿名后，其他用户将不会看到你的真实昵称和头像',
                            style: TextStyle(fontSize: 11, color: Colors.grey),
                          ),
                        ],
                      ),
                    ),
                    Obx(() => Switch(
                          value: _controller.isAnonymous.value,
                          onChanged: _controller.isSubmitting
                              ? null
                              : (val) => _controller.setAnonymous(val),
                        )),
                  ],
                ),
              ),
              const SizedBox(height: 12),

              // 错误提示区域 (失败时不丢弃用户已输入内容)
              Obx(() {
                if (_controller.errorMessage.value.isNotEmpty) {
                  return Padding(
                    padding: const EdgeInsets.only(bottom: 12),
                    child: Text(
                      _controller.errorMessage.value,
                      style: const TextStyle(
                        color: Colors.red,
                        fontSize: 13,
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                  );
                }
                return const SizedBox.shrink();
              }),

              // 6. 提交按钮 (状态机与 Loading 防重)
              Obx(() {
                final isSubmitting = _controller.isSubmitting;
                return ElevatedButton(
                  style: ElevatedButton.styleFrom(
                    backgroundColor: theme.colorScheme.primary,
                    foregroundColor: Colors.white,
                    padding: const EdgeInsets.symmetric(vertical: 14),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                  onPressed: isSubmitting
                      ? null
                      : () async {
                          final res =
                              await _controller.submitReview(widget.order.id);
                          if (res.isSuccess) {
                            if (context.mounted) {
                              Navigator.of(context).pop(true);
                              ScaffoldMessenger.of(context).showSnackBar(
                                const SnackBar(
                                  content: Text('评价提交成功！感谢你的反馈'),
                                  backgroundColor: Colors.green,
                                ),
                              );
                              widget.onSuccess?.call();
                            }
                          }
                        },
                  child: isSubmitting
                      ? const SizedBox(
                          width: 20,
                          height: 20,
                          child: CircularProgressIndicator(
                            strokeWidth: 2,
                            valueColor:
                                AlwaysStoppedAnimation<Color>(Colors.white),
                          ),
                        )
                      : const Text(
                          '提交评价',
                          style: TextStyle(
                            fontSize: 16,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                );
              }),
              const SizedBox(height: 8),
            ],
          ),
        ),
      ),
    );
  }
}
