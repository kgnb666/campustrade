import 'package:flutter/material.dart';
import 'package:get/get.dart';

import '../../models/verify_models.dart';
import '../../services/verify_service.dart';
import '../../utils/api_error.dart';
import '../../utils/ui_feedback.dart';
import '../../widgets/goods_thumbnail.dart';

/// 管理员：校园认证审核队列（「无邮箱通道」提交的学生证材料）。
///
/// 这是平台里第一个管理端页面：此前举报治理只有 API，审核人需要手工发请求；
/// 认证审核如果也那样，"没有学生邮箱的学生"这条路就等于没有人能走完。
///
/// 页面只做三件事：列出待审核材料、看照片与资料、通过或驳回（驳回必须写原因）。
class AdminVerifyReviewPage extends StatefulWidget {
  const AdminVerifyReviewPage({super.key});

  @override
  State<AdminVerifyReviewPage> createState() => _AdminVerifyReviewPageState();
}

class _AdminVerifyReviewPageState extends State<AdminVerifyReviewPage> {
  final VerifyService _verifyService = VerifyService();

  List<AdminVerifyItem> _items = const [];
  bool _loading = true;
  String? _error;
  String _statusFilter = 'PENDING';

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final page = await _verifyService.pageVerifies(status: _statusFilter, size: 50);
      if (!mounted) return;
      setState(() {
        _items = page.records;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = describeApiError(e, fallback: '审核队列加载失败');
      });
    }
  }

  Future<void> _approve(AdminVerifyItem item) async {
    final note = await _askNote(
      title: '通过认证',
      hint: '可选：补充说明（如"已电话核实"）',
      confirmText: '通过',
      required: false,
    );
    if (note == null) return;
    await _review(item, approve: true, note: note);
  }

  Future<void> _reject(AdminVerifyItem item) async {
    final note = await _askNote(
      title: '驳回认证',
      hint: '必填：请说明需要修改什么（学生才能改对）',
      confirmText: '驳回',
      required: true,
    );
    if (note == null) return;
    await _review(item, approve: false, note: note);
  }

  /// 弹出输入框收集审核意见。返回 null 表示用户取消；驳回时未填原因会当场提示而不是发请求。
  Future<String?> _askNote({
    required String title,
    required String hint,
    required String confirmText,
    required bool required,
  }) async {
    final controller = TextEditingController();
    final result = await showDialog<String?>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(title),
        content: TextField(
          controller: controller,
          maxLines: 3,
          maxLength: 255,
          decoration: InputDecoration(hintText: hint, border: const OutlineInputBorder()),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(ctx).pop(null), child: const Text('取消')),
          FilledButton(
            onPressed: () {
              final text = controller.text.trim();
              if (required && text.isEmpty) {
                safeSnackbar('提示', '驳回必须填写原因', snackPosition: SnackPosition.BOTTOM);
                return;
              }
              Navigator.of(ctx).pop(text);
            },
            child: Text(confirmText),
          ),
        ],
      ),
    );
    controller.dispose();
    return result;
  }

  Future<void> _review(AdminVerifyItem item, {required bool approve, String? note}) async {
    try {
      await _verifyService.reviewVerify(id: item.id, approve: approve, note: note);
      if (!mounted) return;
      safeSnackbar(approve ? '已通过' : '已驳回',
          approve ? '该学生的校园认证已生效' : '学生可在认证页看到驳回原因并重新提交',
          snackPosition: SnackPosition.BOTTOM);
      await _load();
    } catch (e) {
      if (!mounted) return;
      safeSnackbar('处置失败', describeApiError(e, fallback: '审核处置失败'),
          snackPosition: SnackPosition.BOTTOM);
      // 处置失败常见原因是"别人刚处理过"，刷新一次让列表回到真实状态
      await _load();
    }
  }

  void _previewEvidence(String url) {
    showDialog<void>(
      context: context,
      builder: (ctx) => Dialog(
        insetPadding: const EdgeInsets.all(16),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Flexible(child: InteractiveViewer(child: Image.network(url, fit: BoxFit.contain))),
            TextButton(onPressed: () => Navigator.of(ctx).pop(), child: const Text('关闭')),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('校园认证审核'),
        centerTitle: true,
        actions: [
          IconButton(onPressed: _load, icon: const Icon(Icons.refresh)),
        ],
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
            child: SegmentedButton<String>(
              segments: const [
                ButtonSegment(value: 'PENDING', label: Text('待审核'), icon: Icon(Icons.pending_actions)),
                ButtonSegment(value: 'SUCCESS', label: Text('已通过'), icon: Icon(Icons.verified_outlined)),
                ButtonSegment(value: 'REJECTED', label: Text('已驳回'), icon: Icon(Icons.block_outlined)),
              ],
              selected: {_statusFilter},
              onSelectionChanged: (selection) {
                setState(() => _statusFilter = selection.first);
                _load();
              },
            ),
          ),
          Expanded(child: _buildBody()),
        ],
      ),
    );
  }

  Widget _buildBody() {
    if (_loading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_error != null) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.error_outline, color: Colors.orange, size: 40),
            const SizedBox(height: 10),
            Text(_error!, textAlign: TextAlign.center),
            const SizedBox(height: 12),
            OutlinedButton(onPressed: _load, child: const Text('重试')),
          ],
        ),
      );
    }
    if (_items.isEmpty) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.inbox_outlined, size: 48, color: Colors.grey[400]),
            const SizedBox(height: 10),
            Text(_statusFilter == 'PENDING' ? '暂无待审核的认证材料' : '暂无记录',
                style: TextStyle(color: Colors.grey[600])),
          ],
        ),
      );
    }
    return ListView.separated(
      padding: const EdgeInsets.all(16),
      itemCount: _items.length,
      separatorBuilder: (_, _) => const SizedBox(height: 12),
      itemBuilder: (context, index) => _buildItemCard(_items[index]),
    );
  }

  Widget _buildItemCard(AdminVerifyItem item) {
    final theme = Theme.of(context);
    final pending = _statusFilter == 'PENDING';
    return Card(
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(color: Colors.grey.shade300),
      ),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(item.displayName,
                      style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold)),
                ),
                Text(item.submittedTime ?? '',
                    style: TextStyle(fontSize: 11.5, color: Colors.grey[600])),
              ],
            ),
            const SizedBox(height: 8),
            _kv('所属学校', item.schoolName ?? '-'),
            _kv('学号', item.studentNumber ?? '-'),
            _kv('姓名', item.realName ?? '-'),
            _kv('账号', item.username ?? '-'),
            if (item.reviewNote != null && item.reviewNote!.isNotEmpty)
              _kv('审核意见', item.reviewNote!),
            const SizedBox(height: 10),
            if ((item.evidenceUrl ?? '').isNotEmpty)
              Row(
                children: [
                  GoodsThumbnail(
                    imageUrl: item.evidenceUrl,
                    width: 92,
                    height: 68,
                    borderRadius: 8,
                    placeholderIcon: Icons.badge_outlined,
                  ),
                  const SizedBox(width: 12),
                  TextButton.icon(
                    onPressed: () => _previewEvidence(item.evidenceUrl!),
                    icon: const Icon(Icons.zoom_in, size: 18),
                    label: const Text('查看大图'),
                  ),
                ],
              ),
            if (pending) ...[
              const SizedBox(height: 6),
              Row(
                mainAxisAlignment: MainAxisAlignment.end,
                children: [
                  OutlinedButton(
                    onPressed: () => _reject(item),
                    style: OutlinedButton.styleFrom(foregroundColor: Colors.red),
                    child: const Text('驳回'),
                  ),
                  const SizedBox(width: 10),
                  FilledButton(onPressed: () => _approve(item), child: const Text('通过')),
                ],
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _kv(String label, String value) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 3),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 68,
            child: Text(label, style: TextStyle(fontSize: 12.5, color: Colors.grey[600])),
          ),
          Expanded(child: Text(value, style: const TextStyle(fontSize: 13))),
        ],
      ),
    );
  }
}
