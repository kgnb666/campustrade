import 'package:flutter/material.dart';
import 'package:get/get.dart';

import '../routes/app_routes.dart';

/// 首页搜索框（未登录也可用）。
///
/// 交互：输入关键词后按回车或点右侧搜索图标 → 跳转校园集市并带上关键词。
/// 关键词是通过**路由参数**传给集市页的（集市页在 initState 里读取），
/// 而不是在这里去操作集市页的控制器——集市页有自己的实例与分页状态机，
/// 从外部写入会让"第 1 页只有几条"这类状态错乱。
class HomeSearchField extends StatefulWidget {
  const HomeSearchField({super.key});

  /// 输入框提示文案（测试与页面共用同一常量，避免两处写死后不一致）。
  static const String hintText = '搜索你想要的二手好物...';

  /// 搜索提交按钮的 Key：让"回车/点图标都要能搜"这件事在 widget 测试里可被稳定触发。
  static const Key submitButtonKey = Key('home_search_submit');

  @override
  State<HomeSearchField> createState() => _HomeSearchFieldState();
}

class _HomeSearchFieldState extends State<HomeSearchField> {
  final TextEditingController _editController = TextEditingController();

  @override
  void dispose() {
    _editController.dispose();
    super.dispose();
  }

  /// 跳转集市页并带上关键词。
  ///
  /// 关键词为空时仍然跳转（相当于"去看全部在售商品"），但不带参数，
  /// 让集市页保持它原本的默认行为。
  void _submit() {
    final String keyword = _editController.text.trim();
    if (keyword.isEmpty) {
      Get.toNamed(AppRoutes.goodsList);
      return;
    }
    Get.toNamed(AppRoutes.goodsList, arguments: keyword);
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return TextField(
      controller: _editController,
      textInputAction: TextInputAction.search,
      onSubmitted: (_) => _submit(),
      decoration: InputDecoration(
        hintText: HomeSearchField.hintText,
        prefixIcon: const Icon(Icons.search, size: 20),
        suffixIcon: IconButton(
          key: HomeSearchField.submitButtonKey,
          icon: const Icon(Icons.arrow_forward_rounded, size: 20),
          tooltip: '搜索',
          onPressed: _submit,
        ),
        contentPadding: const EdgeInsets.symmetric(vertical: 0, horizontal: 16),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(24),
          borderSide: BorderSide.none,
        ),
        filled: true,
        fillColor: theme.colorScheme.surfaceContainerHighest.withValues(alpha: 0.5),
      ),
    );
  }
}
