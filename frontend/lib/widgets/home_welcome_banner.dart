import 'package:flutter/material.dart';
import 'package:get/get.dart';

import '../controllers/auth_controller.dart';
import '../models/status_enums.dart';
import '../routes/app_routes.dart';
import '../utils/name_utils.dart';

/// 首页顶部欢迎条。
///
/// 已登录：头像首字 + 昵称 + 校园认证/信用分 + 「进入个人中心」；
/// 未登录：登录/注册引导。
///
/// 文案原则：只讲用户能理解的身份与信用信息，不出现任何实现细节名词
/// （技术名词属于开发归档，不该出现在用户界面上）。
class HomeWelcomeBanner extends StatelessWidget {
  const HomeWelcomeBanner({super.key});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final AuthController authController = Get.find<AuthController>();

    return Obx(() {
      final isLoggedIn = authController.isLoggedIn.value;
      final user = authController.currentUser.value;

      if (isLoggedIn && user != null) {
        final isVerified = VerifyStatus.fromCode(user.verifyStatus).isVerified;
        final creditScore = user.credit?.creditScore ?? 100;

        return Card(
          color: theme.colorScheme.primaryContainer.withAlpha(50),
          elevation: 0,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(16),
            side: BorderSide(color: theme.colorScheme.primary.withAlpha(50)),
          ),
          child: Padding(
            padding: const EdgeInsets.all(16.0),
            child: Row(
              children: [
                CircleAvatar(
                  radius: 24,
                  backgroundColor: theme.colorScheme.primary,
                  child: Text(
                    initialOf(user.nickname, user.username),
                    style: const TextStyle(
                      color: Colors.white,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ),
                const SizedBox(width: 14),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        '欢迎回来，${displayNameOf(user.nickname, user.username)}！',
                        style: const TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        isVerified
                            ? '已通过 ${user.schoolName ?? "高校"} 校园认证 · 信用分 $creditScore'
                            : '尚未完成学生实名认证 · 初始信用分 $creditScore',
                        style: TextStyle(fontSize: 12, color: Colors.grey[800]),
                      ),
                    ],
                  ),
                ),
                ElevatedButton(
                  onPressed: () => Get.toNamed(AppRoutes.profile),
                  child: const Text('进入个人中心'),
                ),
              ],
            ),
          ),
        );
      }

      return Card(
        elevation: 0,
        color: Colors.blue.withAlpha(20),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(16),
          side: BorderSide(color: Colors.blue.withAlpha(60)),
        ),
        child: Padding(
          padding: const EdgeInsets.all(16.0),
          child: Row(
            children: [
              const Icon(Icons.info_outline, color: Colors.blue),
              const SizedBox(width: 12),
              const Expanded(
                child: Text(
                  '登录即可体验完整的校园认证、信誉档案与个人资料服务。',
                  style: TextStyle(fontSize: 13),
                ),
              ),
              OutlinedButton(
                onPressed: () => Get.toNamed(AppRoutes.register),
                child: const Text('注册'),
              ),
              const SizedBox(width: 8),
              ElevatedButton(
                onPressed: () => Get.toNamed(AppRoutes.login),
                child: const Text('登录'),
              ),
            ],
          ),
        ),
      );
    });
  }
}
