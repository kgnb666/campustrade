import 'package:flutter/material.dart';
import 'package:get/get.dart';
import '../../config/app_config.dart';
import '../../controllers/auth_controller.dart';
import '../../routes/app_routes.dart';
import '../../widgets/status_badge.dart';
import '../../models/status_enums.dart';

/// CampusTrade 首页 (Stage 1: 用户中心与校园认证就绪)
class HomePage extends StatelessWidget {
  const HomePage({super.key});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final authController = Get.find<AuthController>();

    return Scaffold(
      appBar: AppBar(
        title: const Text('CampusTrade · 校园二手交易平台'),
        centerTitle: true,
        elevation: 0,
        actions: [
          Obx(() {
            if (authController.isLoggedIn.value) {
              return IconButton(
                icon: const Icon(Icons.account_circle),
                tooltip: '个人中心',
                onPressed: () => Get.toNamed(AppRoutes.profile),
              );
            }
            return TextButton.icon(
              onPressed: () => Get.toNamed(AppRoutes.login),
              icon: const Icon(Icons.login),
              label: const Text('登录'),
            );
          }),
        ],
      ),
      body: Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(24.0),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 680),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                // 顶部品牌 Logo 与标语
                Icon(
                  Icons.storefront_rounded,
                  size: 72,
                  color: theme.colorScheme.primary,
                ),
                const SizedBox(height: 16),
                Text(
                  AppConfig.appName,
                  textAlign: TextAlign.center,
                  style: theme.textTheme.headlineMedium?.copyWith(
                    fontWeight: FontWeight.bold,
                    color: theme.colorScheme.primary,
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  '校园闲置流转 · 安全信誉认证 · 真实学籍背书',
                  textAlign: TextAlign.center,
                  style: theme.textTheme.bodyMedium?.copyWith(
                    color: Colors.grey[700],
                  ),
                ),
                const SizedBox(height: 24),

                // 用户状态横幅卡片
                Obx(() {
                  final isLoggedIn = authController.isLoggedIn.value;
                  final user = authController.currentUser.value;

                  if (isLoggedIn && user != null) {
                    final isVerified = VerifyStatus.fromCode(user.verifyStatus).isVerified;
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
                                (user.nickname ?? user.username).substring(0, 1).toUpperCase(),
                                style: const TextStyle(color: Colors.white, fontWeight: FontWeight.bold),
                              ),
                            ),
                            const SizedBox(width: 14),
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(
                                    '欢迎回来，${user.nickname ?? user.username}！',
                                    style: const TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
                                  ),
                                  const SizedBox(height: 4),
                                  Text(
                                    isVerified
                                        ? '已通过 ${user.schoolName ?? "高校"} 校园认证 · 信用分: ${user.credit?.creditScore ?? 100}'
                                        : '尚未完成学生实名认证 · 初始信用分: 100',
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
                }),
                const SizedBox(height: 20),

                // Stage 1 阶段卡片
                Card(
                  elevation: 2,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(16),
                  ),
                  child: Padding(
                    padding: const EdgeInsets.all(20.0),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            const Icon(Icons.verified_user_outlined, color: Colors.indigo),
                            const SizedBox(width: 8),
                            Text(
                              'Stage 1：用户中心与校园认证就绪',
                              style: theme.textTheme.titleMedium?.copyWith(
                                fontWeight: FontWeight.bold,
                              ),
                            ),
                            const Spacer(),
                            Container(
                              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                              decoration: BoxDecoration(
                                color: Colors.green.withAlpha(30),
                                borderRadius: BorderRadius.circular(6),
                              ),
                              child: const Text(
                                'Active',
                                style: TextStyle(
                                  color: Colors.green,
                                  fontWeight: FontWeight.bold,
                                  fontSize: 12,
                                ),
                              ),
                            ),
                          ],
                        ),
                        const Divider(height: 24),
                        const Text(
                          '当前阶段已完成全部用户认证与校园身份核验闭环，支持以下服务模块：',
                          style: TextStyle(fontSize: 13, color: Colors.black87),
                        ),
                        const SizedBox(height: 16),
                        const Wrap(
                          spacing: 8,
                          runSpacing: 8,
                          children: [
                            StatusBadge(label: '认证安全', status: 'Spring Security 6 + JWT'),
                            StatusBadge(label: '会话保护', status: 'Redis 双 Token + 黑名单'),
                            StatusBadge(label: '校园核验', status: '教育邮箱验证码 (5m TTL)'),
                            StatusBadge(label: '信誉体系', status: '初始 100 分信用档案'),
                            StatusBadge(label: '客户端', status: 'GetX 状态流转 + 4 大页面'),
                          ],
                        ),
                        const SizedBox(height: 20),
                        // 快捷功能导航按钮
                        Wrap(
                          spacing: 12,
                          runSpacing: 10,
                          children: [
                            ActionChip(
                              avatar: const Icon(Icons.login, size: 16),
                              label: const Text('用户登录'),
                              onPressed: () => Get.toNamed(AppRoutes.login),
                            ),
                            ActionChip(
                              avatar: const Icon(Icons.person_add, size: 16),
                              label: const Text('新用户注册'),
                              onPressed: () => Get.toNamed(AppRoutes.register),
                            ),
                            ActionChip(
                              avatar: const Icon(Icons.account_box, size: 16),
                              label: const Text('个人中心'),
                              onPressed: () => Get.toNamed(AppRoutes.profile),
                            ),
                            ActionChip(
                              avatar: const Icon(Icons.school, size: 16),
                              label: const Text('校园认证'),
                              onPressed: () => Get.toNamed(AppRoutes.studentVerify),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 20),

                // Stage 2 阶段卡片 (商品中心)
                Card(
                  elevation: 2,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(16),
                  ),
                  child: Padding(
                    padding: const EdgeInsets.all(20.0),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            const Icon(Icons.shopping_bag_outlined, color: Colors.deepOrange),
                            const SizedBox(width: 8),
                            Text(
                              'Stage 2：商品发布与浏览体系就绪',
                              style: theme.textTheme.titleMedium?.copyWith(
                                fontWeight: FontWeight.bold,
                              ),
                            ),
                            const Spacer(),
                            Container(
                              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                              decoration: BoxDecoration(
                                color: Colors.green.withAlpha(30),
                                borderRadius: BorderRadius.circular(6),
                              ),
                              child: const Text(
                                'Ready',
                                style: TextStyle(
                                  color: Colors.green,
                                  fontWeight: FontWeight.bold,
                                  fontSize: 12,
                                ),
                              ),
                            ),
                          ],
                        ),
                        const Divider(height: 24),
                        const Text(
                          '实现多级分类、MinIO 多图上传、校园属性强制绑定、搜索筛选与卡片式浏览：',
                          style: TextStyle(fontSize: 13, color: Colors.black87),
                        ),
                        const SizedBox(height: 16),
                        const Wrap(
                          spacing: 8,
                          runSpacing: 8,
                          children: [
                            StatusBadge(label: '对象存储', status: 'MinIO 图片上传 (5MB限制)'),
                            StatusBadge(label: '分类体系', status: '二级树形分类结构'),
                            StatusBadge(label: '校园绑定', status: '强制 seller_id & school_id'),
                            StatusBadge(label: '性能优化', status: 'Redis INCR 浏览量缓存'),
                            StatusBadge(label: '客户端', status: '集市/详情/发布/我的发布 4大页面'),
                          ],
                        ),
                        const SizedBox(height: 20),
                        Wrap(
                          spacing: 12,
                          runSpacing: 10,
                          children: [
                            ElevatedButton.icon(
                              icon: const Icon(Icons.storefront, size: 18),
                              label: const Text('进入校园集市'),
                              style: ElevatedButton.styleFrom(
                                backgroundColor: theme.colorScheme.primary,
                                foregroundColor: theme.colorScheme.onPrimary,
                              ),
                              onPressed: () => Get.toNamed(AppRoutes.goodsList),
                            ),
                            OutlinedButton.icon(
                              icon: const Icon(Icons.add_photo_alternate_outlined, size: 18),
                              label: const Text('发布二手商品'),
                              onPressed: () => Get.toNamed(AppRoutes.goodsCreate),
                            ),
                            OutlinedButton.icon(
                              icon: const Icon(Icons.inventory_2_outlined, size: 18),
                              label: const Text('我的发布'),
                              onPressed: () => Get.toNamed(AppRoutes.goodsMy),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
