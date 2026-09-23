import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:get/get.dart';
import 'package:image_picker/image_picker.dart';

import '../../controllers/auth_controller.dart';
import '../../models/user_model.dart';
import '../../models/verify_models.dart';
import '../../routes/app_routes.dart';
import '../../services/verify_service.dart';
import '../../utils/api_error.dart';
import '../../utils/ui_feedback.dart';

/// 「无邮箱通道」校园认证页：填学号提交 + 管理员审核。
///
/// 为什么需要它：一部分高校不提供学生邮箱，邮箱验证码通道对其学生永远走不通，
/// 而校园认证是"发布商品"的硬前置 —— 走不通就等于用不了平台。
///
/// 门槛刻意压低：**只有学校与学号必填**，姓名与学生证照片都是可选加分项。
/// 这条通道面向的正是"学校连邮箱都没有"的场景，多一个必填项就可能多挡掉一批人。
///
/// 页面形态由服务端返回的认证状态决定：
///   未认证 → 直接展示表单；
///   待审核 → 展示"排队中"，不允许重复提交（避免把审核队列刷满）；
///   已驳回 → 展示驳回原因，并允许修改材料重新提交；
///   已认证 → 展示认证结果，不再提供提交入口。
class StudentVerifyManualPage extends StatefulWidget {
  const StudentVerifyManualPage({super.key});

  @override
  State<StudentVerifyManualPage> createState() => _StudentVerifyManualPageState();
}

class _StudentVerifyManualPageState extends State<StudentVerifyManualPage> {
  final _formKey = GlobalKey<FormState>();
  final _studentNumberController = TextEditingController();
  final _realNameController = TextEditingController();
  final AuthController _authController = Get.find<AuthController>();
  final VerifyService _verifyService = VerifyService();

  SchoolModel? _selectedSchool;
  Uint8List? _evidenceBytes;
  String? _evidenceName;
  bool _submitting = false;
  bool _loadingStatus = true;
  VerifyStatusModel? _status;

  @override
  void initState() {
    super.initState();
    _authController.loadSchools();
    // 姓名默认填昵称：多数人昵称就是真名，少打一次字；但不强制，学生可以改
    _realNameController.text = _authController.currentUser.value?.nickname ?? '';
    _loadStatus();
  }

  @override
  void dispose() {
    _studentNumberController.dispose();
    _realNameController.dispose();
    super.dispose();
  }

  Future<void> _loadStatus() async {
    setState(() => _loadingStatus = true);
    try {
      final status = await _verifyService.getMyVerifyStatus();
      if (!mounted) return;
      setState(() {
        _status = status;
        _loadingStatus = false;
        if (status.studentNumber != null && status.studentNumber!.isNotEmpty) {
          _studentNumberController.text = status.studentNumber!;
        }
        if (status.realName != null && status.realName!.isNotEmpty) {
          _realNameController.text = status.realName!;
        }
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _loadingStatus = false);
      safeSnackbar('认证状态加载失败', describeApiError(e, fallback: '认证状态加载失败'),
          snackPosition: SnackPosition.BOTTOM);
    }
  }

  Future<void> _pickEvidence() async {
    final picker = ImagePicker();
    final picked = await picker.pickImage(
      source: ImageSource.gallery,
      maxWidth: 1600,
      imageQuality: 85,
    );
    if (picked == null) return;
    final bytes = await picked.readAsBytes();
    if (!mounted) return;
    setState(() {
      _evidenceBytes = bytes;
      _evidenceName = picked.name;
    });
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    if (_selectedSchool == null) {
      safeSnackbar('提示', '请选择所属高校', snackPosition: SnackPosition.BOTTOM);
      return;
    }
    setState(() => _submitting = true);
    try {
      // 照片是可选的：选了才上传。上传与提交分开两步，因此上传失败时会在下面被提示，
      // 不会留下"申请已提交但材料丢了"的半成品状态。
      String? evidenceUrl;
      if (_evidenceBytes != null) {
        evidenceUrl = await _verifyService.uploadEvidence(
          _evidenceBytes!,
          _evidenceName ?? 'student-card.png',
        );
      }
      final message = await _verifyService.submitManualVerify(
        schoolId: _selectedSchool!.id,
        studentNumber: _studentNumberController.text.trim(),
        realName: _realNameController.text.trim().isEmpty ? null : _realNameController.text.trim(),
        evidenceUrl: evidenceUrl,
      );
      if (!mounted) return;
      safeSnackbar('已提交', message,
          snackPosition: SnackPosition.BOTTOM,
          backgroundColor: Colors.blue.withAlpha(40),
          colorText: Colors.blue[900]);
      await _loadStatus();
    } catch (e) {
      if (!mounted) return;
      safeSnackbar('提交失败', describeApiError(e, fallback: '材料提交失败'),
          snackPosition: SnackPosition.BOTTOM);
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final status = _status;

    return Scaffold(
      appBar: AppBar(title: const Text('学号认证（人工审核）'), centerTitle: true),
      body: _loadingStatus
          ? const Center(child: CircularProgressIndicator())
          : Center(
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(24),
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 500),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Icon(Icons.badge_outlined, size: 56, color: theme.colorScheme.primary),
                      const SizedBox(height: 12),
                      Text(
                        '没有校园邮箱？用学号认证',
                        textAlign: TextAlign.center,
                        style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.bold),
                      ),
                      const SizedBox(height: 6),
                      Text(
                        '填学号即可提交，管理员审核通过后点亮认证标识；姓名与学生证照片可选（填了能加快审核）',
                        textAlign: TextAlign.center,
                        style: TextStyle(color: Colors.grey[600], fontSize: 13),
                      ),
                      const SizedBox(height: 20),
                      if (status != null) _buildStatusBanner(status),
                      const SizedBox(height: 12),
                      // 已认证 / 待审核时不给表单：前者没有必要，后者会造成重复提交
                      if (status == null || status.isNone || status.isRejected)
                        _buildForm(context)
                      else
                        TextButton(
                          onPressed: () => Get.offNamed(AppRoutes.studentVerify),
                          child: const Text('改用校园邮箱验证码认证'),
                        ),
                    ],
                  ),
                ),
              ),
            ),
    );
  }

  Widget _buildStatusBanner(VerifyStatusModel status) {
    if (status.verified) {
      return _banner(
        icon: Icons.verified_rounded,
        color: Colors.green,
        title: '已完成校园认证',
        detail: '${status.schoolName ?? ''} ${status.realName ?? ''}'.trim(),
      );
    }
    if (status.isPending && status.isManual) {
      return _banner(
        icon: Icons.hourglass_top_rounded,
        color: Colors.orange,
        title: '材料已提交，等待管理员审核',
        detail: '提交时间：${status.submittedTime ?? '-'}',
      );
    }
    if (status.isRejected) {
      return _banner(
        icon: Icons.error_outline_rounded,
        color: Colors.red,
        title: '审核未通过',
        detail: '驳回原因：${status.reviewNote ?? '未填写'}（可修改材料后重新提交）',
      );
    }
    return const SizedBox.shrink();
  }

  Widget _banner({
    required IconData icon,
    required MaterialColor color,
    required String title,
    required String detail,
  }) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: color.withAlpha(24),
        border: Border.all(color: color.withAlpha(90)),
        borderRadius: BorderRadius.circular(10),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, color: color, size: 20),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title, style: TextStyle(fontWeight: FontWeight.bold, color: color[900])),
                if (detail.isNotEmpty) ...[
                  const SizedBox(height: 4),
                  Text(detail, style: TextStyle(fontSize: 12.5, color: Colors.grey[800])),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildForm(BuildContext context) {
    return Form(
      key: _formKey,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Obx(() {
            final schoolList = _authController.schools;
            return DropdownButtonFormField<SchoolModel>(
              decoration: const InputDecoration(
                labelText: '选择所属高校',
                prefixIcon: Icon(Icons.account_balance_outlined),
                border: OutlineInputBorder(),
              ),
              initialValue: _selectedSchool,
              hint: const Text('请选择您就读的高校'),
              items: schoolList
                  .map((school) => DropdownMenuItem<SchoolModel>(
                        value: school,
                        child: Text('${school.schoolName} (${school.schoolCode})'),
                      ))
                  .toList(),
              onChanged: (val) => setState(() => _selectedSchool = val),
              validator: (v) => v == null ? '请选择高校' : null,
            );
          }),
          const SizedBox(height: 16),
          TextFormField(
            controller: _studentNumberController,
            decoration: const InputDecoration(
              labelText: '在读学号',
              hintText: '如 2024010203',
              prefixIcon: Icon(Icons.badge_outlined),
              border: OutlineInputBorder(),
            ),
            validator: (v) => v == null || v.trim().isEmpty ? '学号不能为空' : null,
          ),
          const SizedBox(height: 16),
          TextFormField(
            controller: _realNameController,
            decoration: const InputDecoration(
              labelText: '真实姓名（可选）',
              hintText: '填了便于管理员核对，不填也可以提交',
              prefixIcon: Icon(Icons.person_outline),
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 16),
          _buildEvidencePicker(),
          const SizedBox(height: 20),
          FilledButton(
            onPressed: _submitting ? null : _submit,
            child: _submitting
                ? const SizedBox(
                    height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2))
                : const Text('提交审核'),
          ),
          const SizedBox(height: 10),
          TextButton(
            onPressed: _submitting ? null : () => Get.offNamed(AppRoutes.studentVerify),
            child: const Text('改用校园邮箱验证码认证'),
          ),
        ],
      ),
    );
  }

  Widget _buildEvidencePicker() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        InkWell(
          onTap: _submitting ? null : _pickEvidence,
          child: Container(
            height: 170,
            width: double.infinity,
            decoration: BoxDecoration(
              border: Border.all(color: Colors.grey.shade400),
              borderRadius: BorderRadius.circular(10),
            ),
            child: _evidenceBytes == null
                ? Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      const Icon(Icons.add_a_photo_outlined, size: 34, color: Colors.grey),
                      const SizedBox(height: 8),
                      Text('点击上传学生证 / 校园卡照片（可选）',
                          style: TextStyle(color: Colors.grey[700], fontSize: 13)),
                      const SizedBox(height: 4),
                      Text('照片仅用于身份核验，审核完成后可申请删除',
                          style: TextStyle(color: Colors.grey[500], fontSize: 11.5)),
                    ],
                  )
                : ClipRRect(
                    borderRadius: BorderRadius.circular(10),
                    child: Image.memory(_evidenceBytes!, fit: BoxFit.cover, width: double.infinity),
                  ),
          ),
        ),
        if (_evidenceBytes != null)
          Align(
            alignment: Alignment.centerRight,
            child: TextButton(
              onPressed: _submitting ? null : () => setState(() => _evidenceBytes = null),
              child: const Text('重新选择', style: TextStyle(fontSize: 12)),
            ),
          ),
      ],
    );
  }
}
