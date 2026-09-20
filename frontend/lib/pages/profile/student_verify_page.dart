import 'dart:async';

import 'package:flutter/material.dart';
import 'package:get/get.dart';
import '../../controllers/auth_controller.dart';
import '../../models/user_model.dart';

/// 校园身份认证页面
///
/// 验证码由服务端通过真实邮件发送到校园邮箱，页面不再展示、也不再自动填充验证码：
/// 用户必须查收邮件后手动输入，认证才具备"邮箱可达"的可信性。
class StudentVerifyPage extends StatefulWidget {
  const StudentVerifyPage({super.key});

  @override
  State<StudentVerifyPage> createState() => _StudentVerifyPageState();
}

class _StudentVerifyPageState extends State<StudentVerifyPage> {
  /// 重新发送验证码的冷却秒数（与后端"10 分钟 3 次"的限流配合使用）
  static const int _resendCooldownSeconds = 60;

  final _formKey = GlobalKey<FormState>();
  final _studentNumberController = TextEditingController();
  final _emailController = TextEditingController();
  final _verifyCodeController = TextEditingController();
  final AuthController _authController = Get.find<AuthController>();

  SchoolModel? _selectedSchool;
  bool _codeSent = false;
  String? _sentEmail; // 验证码实际发送到的校园邮箱，用于提示文案
  int _resendCountdown = 0;
  Timer? _countdownTimer;

  @override
  void initState() {
    super.initState();
    _authController.loadSchools();
  }

  @override
  void dispose() {
    _countdownTimer?.cancel();
    _studentNumberController.dispose();
    _emailController.dispose();
    _verifyCodeController.dispose();
    super.dispose();
  }

  Future<void> _handleSendCode() async {
    if (_selectedSchool == null) {
      Get.snackbar('提示', '请先选择所属高校', snackPosition: SnackPosition.BOTTOM);
      return;
    }
    if (_studentNumberController.text.trim().isEmpty) {
      Get.snackbar('提示', '请填写学号', snackPosition: SnackPosition.BOTTOM);
      return;
    }
    if (_emailController.text.trim().isEmpty) {
      Get.snackbar('提示', '请填写校园官方邮箱', snackPosition: SnackPosition.BOTTOM);
      return;
    }

    final sent = await _authController.submitVerify(
      _selectedSchool!.id,
      _studentNumberController.text,
      _emailController.text,
    );

    if (!sent || !mounted) {
      return;
    }

    setState(() {
      _codeSent = true;
      _sentEmail = _emailController.text.trim();
      _verifyCodeController.clear(); // 新验证码需要重新输入，避免旧输入造成误判
      _resendCountdown = _resendCooldownSeconds;
    });
    _startCountdown();
  }

  /// 发送成功后启动 60 秒倒计时，避免用户连续点击（后端另有 10 分钟 3 次的硬限流）
  void _startCountdown() {
    _countdownTimer?.cancel();
    _countdownTimer = Timer.periodic(const Duration(seconds: 1), (timer) {
      if (!mounted) {
        timer.cancel();
        return;
      }
      setState(() {
        _resendCountdown--;
        if (_resendCountdown <= 0) {
          timer.cancel();
          _countdownTimer = null;
        }
      });
    });
  }

  Future<void> _handleSubmitVerification() async {
    if (!_formKey.currentState!.validate()) {
      return;
    }

    final ok = await _authController.verifyCode(
      _emailController.text,
      _verifyCodeController.text,
    );

    // 核验失败：清空输入方便重试；失败原因（验证码错误 / 已失效 / 发送过于频繁）
    // 已由 AuthController 通过 Get.snackbar 提示，重新发送入口在同一页面即可继续操作。
    if (!ok && mounted) {
      setState(() {
        _verifyCodeController.clear();
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Scaffold(
      appBar: AppBar(
        title: const Text('校园身份认证'),
        centerTitle: true,
      ),
      body: Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(24.0),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 500),
            child: Form(
              key: _formKey,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  // 顶部图标与指引
                  Icon(
                    Icons.school_rounded,
                    size: 64,
                    color: theme.colorScheme.primary,
                  ),
                  const SizedBox(height: 12),
                  Text(
                    '高校学生实名认证',
                    textAlign: TextAlign.center,
                    style: theme.textTheme.titleLarge?.copyWith(
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  const SizedBox(height: 6),
                  Text(
                    '通过校内教育邮箱 (.edu.cn) 完成实名学生身份核验',
                    textAlign: TextAlign.center,
                    style: TextStyle(color: Colors.grey[600], fontSize: 13),
                  ),
                  const SizedBox(height: 24),

                  // 步骤 1: 选择高校
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
                      items: schoolList.map((school) {
                        return DropdownMenuItem<SchoolModel>(
                          value: school,
                          child: Text('${school.schoolName} (${school.schoolCode})'),
                        );
                      }).toList(),
                      onChanged: (val) {
                        setState(() {
                          _selectedSchool = val;
                          if (val != null && _emailController.text.isEmpty) {
                            _emailController.text = 'student${val.emailSuffix}';
                          }
                        });
                      },
                      validator: (v) => v == null ? '请选择高校' : null,
                    );
                  }),

                  // 高校列表加载失败时的可见降级：给出原因 + 重新加载入口，
                  // 而不是留一个永远空着的下拉框让用户猜。
                  Obx(() {
                    final error = _authController.schoolsError.value;
                    if (error.isEmpty) return const SizedBox.shrink();
                    return Padding(
                      padding: const EdgeInsets.only(top: 8),
                      child: Row(
                        children: [
                          const Icon(Icons.error_outline, size: 16, color: Colors.orange),
                          const SizedBox(width: 6),
                          Expanded(
                            child: Text(
                              error,
                              style: TextStyle(fontSize: 12, color: Colors.orange[900]),
                            ),
                          ),
                          TextButton(
                            onPressed: () => _authController.loadSchools(),
                            child: const Text('重新加载', style: TextStyle(fontSize: 12)),
                          ),
                        ],
                      ),
                    );
                  }),
                  const SizedBox(height: 16),

                  // 步骤 2: 填写学号
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

                  // 步骤 3: 校园邮箱
                  TextFormField(
                    controller: _emailController,
                    keyboardType: TextInputType.emailAddress,
                    decoration: InputDecoration(
                      labelText: '校园邮箱 (.edu.cn)',
                      hintText: _selectedSchool != null
                          ? '须以 ${_selectedSchool!.emailSuffix} 结尾'
                          : '请输入学校官方教育邮箱',
                      prefixIcon: const Icon(Icons.email_outlined),
                      border: const OutlineInputBorder(),
                    ),
                    validator: (v) {
                      if (v == null || v.trim().isEmpty) return '邮箱不能为空';
                      if (_selectedSchool != null && !v.trim().endsWith(_selectedSchool!.emailSuffix)) {
                        return '邮箱后缀须为 ${_selectedSchool!.emailSuffix}';
                      }
                      return null;
                    },
                  ),
                  const SizedBox(height: 16),

                  // 发送验证码按钮（发送成功后 60 秒内不可重复点击）
                  Obx(() {
                    final sending = _authController.isLoading.value;
                    final label = _resendCountdown > 0
                        ? '$_resendCountdown 秒后可重新发送'
                        : (_codeSent ? '重新发送验证码' : '获取邮箱验证码');
                    return OutlinedButton.icon(
                      onPressed: (sending || _resendCountdown > 0) ? null : _handleSendCode,
                      style: OutlinedButton.styleFrom(
                        padding: const EdgeInsets.symmetric(vertical: 12),
                      ),
                      icon: const Icon(Icons.send_rounded),
                      label: Text(label),
                    );
                  }),

                  // 发送结果提示：只告知"已发送到哪个邮箱"，不再展示验证码
                  if (_codeSent) ...[
                    const SizedBox(height: 12),
                    Container(
                      padding: const EdgeInsets.all(10),
                      decoration: BoxDecoration(
                        color: Colors.blue.withAlpha(20),
                        borderRadius: BorderRadius.circular(6),
                      ),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            '验证码已发送至 ${_sentEmail ?? _emailController.text.trim()} 邮箱',
                            style: TextStyle(fontSize: 13, color: Colors.blue[900]),
                          ),
                          const SizedBox(height: 4),
                          Text(
                            '请在 5 分钟内查收（含垃圾邮件箱）并输入下方验证码；未收到可稍后重新发送。',
                            style: TextStyle(fontSize: 12, color: Colors.grey[700]),
                          ),
                        ],
                      ),
                    ),
                  ],
                  const SizedBox(height: 20),

                  // 步骤 4: 验证码输入（验证码只能来自校园邮箱）
                  TextFormField(
                    controller: _verifyCodeController,
                    keyboardType: TextInputType.number,
                    maxLength: 6,
                    decoration: const InputDecoration(
                      labelText: '6位邮箱验证码',
                      hintText: '5分钟内有效',
                      prefixIcon: Icon(Icons.key_outlined),
                      border: OutlineInputBorder(),
                      counterText: '',
                    ),
                    validator: (v) {
                      final code = v?.trim() ?? '';
                      if (code.isEmpty) return '请输入6位邮箱验证码';
                      if (!RegExp(r'^\d{6}$').hasMatch(code)) return '验证码为6位数字';
                      return null;
                    },
                  ),

                  const SizedBox(height: 24),

                  // 提交认证按钮
                  Obx(() {
                    return ElevatedButton(
                      onPressed: _authController.isLoading.value ? null : _handleSubmitVerification,
                      style: ElevatedButton.styleFrom(
                        padding: const EdgeInsets.symmetric(vertical: 14),
                        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
                      ),
                      child: _authController.isLoading.value
                          ? const SizedBox(
                              height: 20,
                              width: 20,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : const Text('完成认证', style: TextStyle(fontSize: 16)),
                    );
                  }),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
