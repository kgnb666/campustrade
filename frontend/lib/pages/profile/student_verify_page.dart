import 'package:flutter/material.dart';
import 'package:get/get.dart';
import '../../controllers/auth_controller.dart';
import '../../models/user_model.dart';

/// 校园身份认证页面
class StudentVerifyPage extends StatefulWidget {
  const StudentVerifyPage({super.key});

  @override
  State<StudentVerifyPage> createState() => _StudentVerifyPageState();
}

class _StudentVerifyPageState extends State<StudentVerifyPage> {
  final _formKey = GlobalKey<FormState>();
  final _studentNumberController = TextEditingController();
  final _emailController = TextEditingController();
  final _verifyCodeController = TextEditingController();
  final AuthController _authController = Get.find<AuthController>();

  SchoolModel? _selectedSchool;
  bool _codeSent = false;
  String? _lastSentCode; // 用于联调与演示显示

  @override
  void initState() {
    super.initState();
    _authController.loadSchools();
  }

  @override
  void dispose() {
    _studentNumberController.dispose();
    _emailController.dispose();
    _verifyCodeController.dispose();
    super.dispose();
  }

  void _handleSendCode() async {
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

    final code = await _authController.submitVerify(
      _selectedSchool!.id,
      _studentNumberController.text,
      _emailController.text,
    );

    if (code != null) {
      setState(() {
        _codeSent = true;
        _lastSentCode = code;
        _verifyCodeController.text = code; // 自动填充以便于即时核验
      });
    }
  }

  void _handleSubmitVerification() {
    if (_verifyCodeController.text.trim().isEmpty) {
      Get.snackbar('提示', '请输入6位邮箱验证码', snackPosition: SnackPosition.BOTTOM);
      return;
    }

    _authController.verifyCode(
      _emailController.text,
      _verifyCodeController.text,
    );
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

                  // 发送验证码按钮
                  Obx(() {
                    return OutlinedButton.icon(
                      onPressed: _authController.isLoading.value ? null : _handleSendCode,
                      style: OutlinedButton.styleFrom(
                        padding: const EdgeInsets.symmetric(vertical: 12),
                      ),
                      icon: const Icon(Icons.send_rounded),
                      label: Text(_codeSent ? '重新发送验证码' : '获取邮箱验证码'),
                    );
                  }),
                  const SizedBox(height: 20),

                  // 步骤 4: 验证码输入
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
                  ),

                  if (_lastSentCode != null) ...[
                    const SizedBox(height: 6),
                    Container(
                      padding: const EdgeInsets.all(8),
                      decoration: BoxDecoration(
                        color: Colors.green.withAlpha(20),
                        borderRadius: BorderRadius.circular(6),
                      ),
                      child: Text(
                        '联调提示：已自动填入当前生成的验证码 $_lastSentCode',
                        style: TextStyle(fontSize: 12, color: Colors.green[800]),
                      ),
                    ),
                  ],

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
