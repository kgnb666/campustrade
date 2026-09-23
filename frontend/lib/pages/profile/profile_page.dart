import 'package:flutter/material.dart';
import 'package:get/get.dart';
import '../../controllers/auth_controller.dart';
import '../../controllers/review_controller.dart';
import '../../routes/app_routes.dart';
import '../../models/status_enums.dart';
import '../../utils/name_utils.dart';

/// 个人中心页面
class ProfilePage extends StatelessWidget {
  const ProfilePage({super.key});

  void _showEditProfileDialog(BuildContext context, AuthController authController) {
    Get.dialog(_EditProfileDialog(authController: authController));
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final authController = Get.find<AuthController>();
    final reviewController = Get.isRegistered<ReviewController>()
        ? Get.find<ReviewController>()
        : Get.put(ReviewController());

    return Scaffold(
      appBar: AppBar(
        title: const Text('个人中心'),
        centerTitle: true,
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: () {
              authController.fetchProfile();
              final uid = authController.currentUser.value?.id;
              if (uid != null) {
                reviewController.fetchUserReviews(uid);
              }
            },
            tooltip: '刷新资料',
          ),
        ],
      ),
      body: Obx(() {
        final user = authController.currentUser.value;

        if (user == null) {
          return Center(
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                const Icon(Icons.lock_person_outlined, size: 64, color: Colors.grey),
                const SizedBox(height: 16),
                const Text('您尚未登录，请先登录账号', style: TextStyle(fontSize: 16)),
                const SizedBox(height: 16),
                ElevatedButton(
                  onPressed: () => Get.toNamed(AppRoutes.login),
                  child: const Text('前往登录'),
                ),
              ],
            ),
          );
        }

        final isVerified = VerifyStatus.fromCode(user.verifyStatus).isVerified;
        final credit = user.credit;

        return SingleChildScrollView(
          padding: const EdgeInsets.all(20.0),
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 680),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  // 1. 用户基本信息卡片
                  Card(
                    elevation: 2,
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
                    child: Padding(
                      padding: const EdgeInsets.all(20.0),
                      child: Row(
                        children: [
                          CircleAvatar(
                            radius: 36,
                            backgroundColor: theme.colorScheme.primaryContainer,
                            backgroundImage: user.avatar != null && user.avatar!.isNotEmpty
                                ? NetworkImage(user.avatar!)
                                : null,
                            child: user.avatar == null || user.avatar!.isEmpty
                                ? Text(
                                    initialOf(user.nickname, user.username),
                                    style: TextStyle(
                                      fontSize: 28,
                                      fontWeight: FontWeight.bold,
                                      color: theme.colorScheme.onPrimaryContainer,
                                    ),
                                  )
                                : null,
                          ),
                          const SizedBox(width: 18),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Row(
                                  children: [
                                    Flexible(
                                      child: Text(
                                        displayNameOf(user.nickname, user.username),
                                        style: theme.textTheme.titleLarge?.copyWith(
                                          fontWeight: FontWeight.bold,
                                        ),
                                        overflow: TextOverflow.ellipsis,
                                      ),
                                    ),
                                    const SizedBox(width: 8),
                                    Container(
                                      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                                      decoration: BoxDecoration(
                                        color: Colors.blue.withAlpha(30),
                                        borderRadius: BorderRadius.circular(4),
                                      ),
                                      child: Text(
                                        user.role,
                                        style: const TextStyle(fontSize: 10, color: Colors.blue, fontWeight: FontWeight.bold),
                                      ),
                                    ),
                                  ],
                                ),
                                const SizedBox(height: 6),
                                Text(
                                  '@${user.username} · ${user.email ?? "未绑定邮箱"}',
                                  style: TextStyle(fontSize: 13, color: Colors.grey[600]),
                                ),
                                if (user.phone != null && user.phone!.isNotEmpty) ...[
                                  const SizedBox(height: 4),
                                  Text(
                                    '手机: ${user.phone}',
                                    style: TextStyle(fontSize: 12, color: Colors.grey[700]),
                                  ),
                                ],
                              ],
                            ),
                          ),
                          IconButton(
                            icon: const Icon(Icons.edit_outlined),
                            tooltip: '编辑资料',
                            onPressed: () => _showEditProfileDialog(context, authController),
                          ),
                        ],
                      ),
                    ),
                  ),
                  const SizedBox(height: 16),

                  // 2. 校园认证状态卡片
                  Card(
                    elevation: 1.5,
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
                    child: Padding(
                      padding: const EdgeInsets.all(18.0),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Row(
                            children: [
                              Icon(
                                isVerified ? Icons.verified_rounded : Icons.gpp_maybe_outlined,
                                color: isVerified ? Colors.green : Colors.amber[800],
                              ),
                              const SizedBox(width: 8),
                              Text(
                                '校园身份认证',
                                style: theme.textTheme.titleMedium?.copyWith(
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
                              const Spacer(),
                              Container(
                                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                                decoration: BoxDecoration(
                                  color: isVerified ? Colors.green.withAlpha(25) : Colors.amber.withAlpha(25),
                                  borderRadius: BorderRadius.circular(6),
                                ),
                                child: Text(
                                  isVerified ? VerifyStatus.success.label : VerifyStatus.fromCode(user.verifyStatus).label,
                                  style: TextStyle(
                                    color: isVerified ? Colors.green[800] : Colors.amber[900],
                                    fontWeight: FontWeight.bold,
                                    fontSize: 12,
                                  ),
                                ),
                              ),
                            ],
                          ),
                          const Divider(height: 20),
                          if (isVerified) ...[
                            Row(
                              children: [
                                const Icon(Icons.school_outlined, size: 18, color: Colors.grey),
                                const SizedBox(width: 8),
                                Text(
                                  '认证高校: ${user.schoolName ?? "高校认证通过"}',
                                  style: const TextStyle(fontWeight: FontWeight.w500),
                                ),
                              ],
                            ),
                              if (user.studentNumber != null) ...[
                                const SizedBox(height: 6),
                                Row(
                                  children: [
                                    const Icon(Icons.badge_outlined, size: 18, color: Colors.grey),
                                    const SizedBox(width: 8),
                                    Text(
                                      '已核验学号: ${user.studentNumber}',
                                      style: TextStyle(color: Colors.grey[700], fontSize: 13),
                                    ),
                                  ],
                                ),
                              ],
                              const SizedBox(height: 6),
                              Row(
                                children: [
                                  const Icon(Icons.stars_outlined, size: 18, color: Colors.amber),
                                  const SizedBox(width: 8),
                                  Text(
                                    '信用分: ${credit?.creditScore ?? 100}',
                                    style: TextStyle(color: Colors.grey[700], fontSize: 13),
                                  ),
                                ],
                              ),
                          ] else ...[
                            Text(
                              '您尚未完成校园真实身份认证，认证后将点亮高校认证勋章并获得校内信誉背书。',
                              style: TextStyle(fontSize: 13, color: Colors.grey[700]),
                            ),
                            const SizedBox(height: 12),
                            Align(
                              alignment: Alignment.centerRight,
                              child: ElevatedButton.icon(
                                onPressed: () => Get.toNamed(AppRoutes.studentVerify),
                                icon: const Icon(Icons.arrow_forward_rounded, size: 16),
                                label: const Text('立即前往认证'),
                              ),
                            ),
                          ],
                        ],
                      ),
                    ),
                  ),
                  const SizedBox(height: 16),

                  // 3. 校园信用档案卡片 (升级为完整信用与评价中心)
                  Card(
                    elevation: 1.5,
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
                    child: Padding(
                      padding: const EdgeInsets.all(18.0),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Row(
                            children: [
                              const Icon(Icons.shield_outlined, color: Colors.indigo),
                              const SizedBox(width: 8),
                              Text(
                                '校园信誉档案',
                                style: theme.textTheme.titleMedium?.copyWith(
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
                              const Spacer(),
                              // 等级徽章 (根据后端返回的 creditLevel / computedCreditLevel)
                              Container(
                                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                                decoration: BoxDecoration(
                                  color: _getCreditLevelColor(credit?.computedCreditLevel).withValues(alpha: 0.15),
                                  borderRadius: BorderRadius.circular(6),
                                ),
                                child: Text(
                                  '${credit?.levelDescription ?? "信用良好"} (${credit?.computedCreditLevel ?? "GOOD"})',
                                  style: TextStyle(
                                    fontSize: 12,
                                    fontWeight: FontWeight.bold,
                                    color: _getCreditLevelColor(credit?.computedCreditLevel),
                                  ),
                                ),
                              ),
                            ],
                          ),
                          const SizedBox(height: 12),
                          // 信用分数值与区间展示
                          Row(
                            mainAxisAlignment: MainAxisAlignment.spaceBetween,
                            crossAxisAlignment: CrossAxisAlignment.end,
                            children: [
                              Row(
                                crossAxisAlignment: CrossAxisAlignment.baseline,
                                textBaseline: TextBaseline.alphabetic,
                                children: [
                                  Text(
                                    '${credit?.creditScore ?? 100}',
                                    style: TextStyle(
                                      fontSize: 32,
                                      fontWeight: FontWeight.bold,
                                      color: _getCreditLevelColor(credit?.computedCreditLevel),
                                    ),
                                  ),
                                  const SizedBox(width: 4),
                                  const Text(
                                    '分',
                                    style: TextStyle(fontSize: 14, color: Colors.grey),
                                  ),
                                ],
                              ),
                              const Text(
                                '信用分范围：0～200',
                                style: TextStyle(fontSize: 12, color: Colors.grey),
                              ),
                            ],
                          ),
                          const SizedBox(height: 8),
                          ClipRRect(
                            borderRadius: BorderRadius.circular(4),
                            child: LinearProgressIndicator(
                              value: ((credit?.creditScore ?? 100) / 200.0).clamp(0.0, 1.0),
                              minHeight: 6,
                              backgroundColor: Colors.grey.shade200,
                              valueColor: AlwaysStoppedAnimation<Color>(
                                _getCreditLevelColor(credit?.computedCreditLevel),
                              ),
                            ),
                          ),
                          const Divider(height: 24),
                          // 统计指标: 完成交易、取消交易、好评数、差评数
                          Row(
                            mainAxisAlignment: MainAxisAlignment.spaceAround,
                            children: [
                              _buildCreditStatItem(
                                '完成交易',
                                '${credit?.completedCount ?? credit?.tradeCount ?? 0} 笔',
                                Colors.indigo,
                              ),
                              _buildCreditStatItem(
                                '取消交易',
                                '${credit?.cancelCount ?? 0} 笔',
                                Colors.red.shade700,
                              ),
                              _buildCreditStatItem(
                                '好评数',
                                '${credit?.goodReviewCount ?? 0}',
                                Colors.green.shade700,
                              ),
                              _buildCreditStatItem(
                                '差评数',
                                '${credit?.badReviewCount ?? 0}',
                                Colors.deepOrange,
                              ),
                            ],
                          ),
                        ],
                      ),
                    ),
                  ),
                  const SizedBox(height: 16),

                  // 3.1 用户收到的评价列表卡片
                  _buildUserReviewsCard(theme, reviewController, user.id),
                  const SizedBox(height: 16),
                  // 4. 我的互动与闲置商品管理卡片
                  Card(
                    elevation: 1.5,
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
                    child: Column(
                      children: [
                        ListTile(
                          leading: const Icon(Icons.receipt_long_outlined, color: Colors.blueAccent),
                          title: const Text('我的订单'),
                          subtitle: const Text('查看购买与出售的交易订单'),
                          trailing: const Icon(Icons.chevron_right),
                          onTap: () => Get.toNamed(AppRoutes.orderMy),
                        ),
                        const Divider(height: 1),
                        ListTile(
                          leading: const Icon(Icons.favorite_outline, color: Colors.pink),
                          title: const Text('我的收藏'),
                          subtitle: const Text('查看我心仪的二手好物'),
                          trailing: const Icon(Icons.chevron_right),
                          onTap: () => Get.toNamed(AppRoutes.favorite),
                        ),
                        const Divider(height: 1),
                        ListTile(
                          leading: const Icon(Icons.history, color: Colors.teal),
                          title: const Text('浏览足迹'),
                          subtitle: const Text('回顾最近浏览的商品'),
                          trailing: const Icon(Icons.chevron_right),
                          onTap: () => Get.toNamed(AppRoutes.history),
                        ),
                        const Divider(height: 1),
                        ListTile(
                          leading: const Icon(Icons.inventory_2_outlined, color: Colors.deepOrange),
                          title: const Text('我的发布'),
                          subtitle: const Text('管理我上架的闲置商品'),
                          trailing: const Icon(Icons.chevron_right),
                          onTap: () => Get.toNamed(AppRoutes.goodsMy),
                        ),
                        const Divider(height: 1),
                        ListTile(
                          leading: const Icon(Icons.add_circle_outline, color: Colors.blue),
                          title: const Text('发布新商品'),
                          subtitle: const Text('快速发布校园二手物品 (支持 AI 助手)'),
                          trailing: const Icon(Icons.chevron_right),
                          onTap: () => Get.toNamed(AppRoutes.goodsCreate),
                        ),
                        // 管理端入口：只在管理员账号下出现。前端不出现是"不打扰普通用户"，
                        // 真正的权限边界在后端（/admin/** 由 hasRole('ADMIN') 拦截）。
                        if (user.role.toUpperCase() == 'ADMIN') ...[
                          const Divider(height: 1),
                          ListTile(
                            leading: const Icon(Icons.fact_check_outlined, color: Colors.purple),
                            title: const Text('认证审核'),
                            subtitle: const Text('审核学生提交的学号认证（无邮箱通道）'),
                            trailing: const Icon(Icons.chevron_right),
                            onTap: () => Get.toNamed(AppRoutes.adminVerifyReview),
                          ),
                        ],
                      ],
                    ),
                  ),
                  const SizedBox(height: 24),

                  // 5. 退出登录按钮
                  OutlinedButton.icon(
                    onPressed: () => authController.logout(),
                    style: OutlinedButton.styleFrom(
                      foregroundColor: Colors.red,
                      side: const BorderSide(color: Colors.red),
                      padding: const EdgeInsets.symmetric(vertical: 14),
                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
                    ),
                    icon: const Icon(Icons.logout),
                    label: const Text('退出登录', style: TextStyle(fontSize: 15)),
                  ),
                ],
              ),
            ),
          ),
        );
      }),
    );
  }

  Widget _buildCreditStatItem(String label, String value, Color color) {
    return Column(
      children: [
        Text(
          value,
          style: TextStyle(
            fontSize: 18,
            fontWeight: FontWeight.bold,
            color: color,
          ),
        ),
        const SizedBox(height: 4),
        Text(
          label,
          style: TextStyle(fontSize: 12, color: Colors.grey[600]),
        ),
      ],
    );
  }

  Color _getCreditLevelColor(String? level) {
    switch (level?.toUpperCase()) {
      case 'EXCELLENT':
        return Colors.green.shade700;
      case 'GOOD':
        return Colors.blue.shade700;
      case 'FAIR':
        return Colors.amber.shade800;
      case 'POOR':
        return Colors.red.shade700;
      default:
        return Colors.blue.shade700;
    }
  }

  Widget _buildUserReviewsCard(
    ThemeData theme,
    ReviewController reviewController,
    String? userId,
  ) {
    // 每个用户只自动加载一次：仅凭「列表为空」判断会让没有评价的用户陷入无限请求与重建（页面闪烁）
    if (userId != null &&
        reviewController.loadedUserReviewsUserId != userId &&
        !reviewController.loadingUserReviews.value) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        reviewController.fetchUserReviews(userId);
      });
    }

    return Card(
      elevation: 1.5,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      child: Padding(
        padding: const EdgeInsets.all(18.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Row(
                  children: [
                    const Icon(Icons.rate_review_outlined, color: Colors.teal),
                    const SizedBox(width: 8),
                    Text(
                      '收到的评价',
                      style: theme.textTheme.titleMedium?.copyWith(
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                  ],
                ),
                Obx(() => Text(
                      '共 ${reviewController.userReviews.length} 条',
                      style: TextStyle(fontSize: 12, color: Colors.grey.shade600),
                    )),
              ],
            ),
            const Divider(height: 20),
            Obx(() {
              if (reviewController.loadingUserReviews.value &&
                  reviewController.userReviews.isEmpty) {
                return const Center(
                  child: Padding(
                    padding: EdgeInsets.symmetric(vertical: 16),
                    child: CircularProgressIndicator(),
                  ),
                );
              }

              if (reviewController.userReviews.isEmpty) {
                return Container(
                  width: double.infinity,
                  padding: const EdgeInsets.symmetric(vertical: 16),
                  child: Column(
                    children: [
                      Icon(Icons.speaker_notes_off_outlined,
                          size: 32, color: Colors.grey.shade400),
                      const SizedBox(height: 8),
                      Text(
                        '暂未收到交易评价，积极完成订单可积累信誉',
                        style: TextStyle(fontSize: 13, color: Colors.grey.shade600),
                      ),
                    ],
                  ),
                );
              }

              return ListView.separated(
                shrinkWrap: true,
                physics: const NeverScrollableScrollPhysics(),
                itemCount: reviewController.userReviews.length,
                separatorBuilder: (ctx, idx) => const Divider(height: 16),
                itemBuilder: (ctx, index) {
                  final review = reviewController.userReviews[index];
                  return Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Row(
                            mainAxisSize: MainAxisSize.min,
                            children: List.generate(5, (starIdx) {
                              final filled = starIdx < review.score;
                              return Icon(
                                filled
                                    ? Icons.star_rounded
                                    : Icons.star_outline_rounded,
                                size: 16,
                                color: filled
                                    ? Colors.amber.shade600
                                    : Colors.grey.shade300,
                              );
                            }),
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
                          Text(
                            '交易完成',
                            style: TextStyle(
                              fontSize: 11,
                              color: Colors.green.shade700,
                              fontWeight: FontWeight.w500,
                            ),
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
                                color: theme.colorScheme.surfaceContainerHighest
                                    .withValues(alpha: 0.5),
                                borderRadius: BorderRadius.circular(4),
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
              );
            }),
          ],
        ),
      ),
    );
  }
}

/// 修改个人资料弹窗（独立 StatefulWidget）
///
/// 为什么必须是 StatefulWidget：三个 [TextEditingController] 原先在弹窗外部创建、
/// 从不 dispose，弹窗被关闭后控制器仍然挂在 Element 树上（反复打开会持续泄漏）。
/// 放进弹窗自己的 State 里，[State.dispose] 才能与弹窗生命周期严格对齐。
/// 视觉与交互与改造前完全一致（同样的字段、同样的取消/保存按钮语义）。
class _EditProfileDialog extends StatefulWidget {
  const _EditProfileDialog({required this.authController});

  final AuthController authController;

  @override
  State<_EditProfileDialog> createState() => _EditProfileDialogState();
}

class _EditProfileDialogState extends State<_EditProfileDialog> {
  late final TextEditingController _nicknameController;
  late final TextEditingController _avatarController;
  late final TextEditingController _phoneController;

  @override
  void initState() {
    super.initState();
    final user = widget.authController.currentUser.value;
    _nicknameController = TextEditingController(text: user?.nickname ?? '');
    _avatarController = TextEditingController(text: user?.avatar ?? '');
    _phoneController = TextEditingController(text: user?.phone ?? '');
  }

  @override
  void dispose() {
    _nicknameController.dispose();
    _avatarController.dispose();
    _phoneController.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    // 先取值再关闭弹窗：控制器随即被 dispose，不能再读 .text
    final nickname = _nicknameController.text;
    final avatar = _avatarController.text;
    final phone = _phoneController.text;

    Get.back();
    await widget.authController.updateProfile(
      nickname: nickname,
      avatar: avatar,
      phone: phone,
    );
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('修改个人资料'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: _nicknameController,
              decoration: const InputDecoration(
                labelText: '个性昵称',
                hintText: '展示在商品与个人主页',
              ),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _avatarController,
              decoration: const InputDecoration(
                labelText: '头像图片链接',
                hintText: '输入 HTTP/HTTPS 图片 URL',
              ),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _phoneController,
              decoration: const InputDecoration(
                labelText: '联系电话',
                hintText: '用于线下自提电话联系',
              ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Get.back(),
          child: const Text('取消'),
        ),
        ElevatedButton(
          onPressed: _save,
          child: const Text('保存'),
        ),
      ],
    );
  }
}
