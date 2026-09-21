import 'package:flutter/material.dart';
import 'package:get/get.dart';

import '../../controllers/auth_controller.dart';
import '../../controllers/home_controller.dart';
import '../../routes/app_routes.dart';
import '../../utils/page_controller_scope.dart';
import '../../widgets/home_latest_goods_section.dart';
import '../../widgets/home_search_field.dart';
import '../../widgets/home_todo_section.dart';
import '../../widgets/home_welcome_banner.dart';

/// CampusTrade 首页（面向用户的"可用首页"）
///
/// 结构自上而下：欢迎条 → 搜索框 → 我的待办（仅登录） → 最新商品。
/// 整页支持下拉刷新，同时刷新"待办"与"最新商品"两个数据源。
///
/// 为什么不再有"开发进度说明页"：早先的两张卡片写的是实现细节名词与开发进度字样，
/// 对用户没有任何意义——用户打开首页想做的事只有三件：找东西、看待办、看新上架的。
/// 开发过程信息属于提交历史与文档，不属于用户界面。
class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  /// 首页自己的控制器（待办汇总 + 最新商品）。
  ///
  /// 由路由 binding 用 `Get.lazyPut(..., fenix: false)` 注册、随本路由释放；
  /// 没有 binding 时（直接以 widget 构造页面、widget 测试）退化为自建自释放。
  /// 刻意不复用集市页的 `GoodsController` 与订单页的 `OrderController`：
  /// 复用会把首页的请求混进它们的分页/筛选状态机（详见 `HomeController` 的说明）。
  late final PageControllerRef<HomeController> _controllerRef;
  HomeController get _controller => _controllerRef.controller;

  @override
  void initState() {
    super.initState();
    _controllerRef = PageControllerScope.acquire<HomeController>(
      () => HomeController(),
    );
  }

  @override
  void dispose() {
    _controllerRef.release();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
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
      body: RefreshIndicator(
        onRefresh: _controller.loadAll,
        child: ListView(
          // 首页内容可能不足一屏，用 AlwaysScrollable 保证"下拉刷新"在任何情况下都能触发
          physics: const AlwaysScrollableScrollPhysics(),
          padding: const EdgeInsets.fromLTRB(16, 16, 16, 28),
          children: [
            // 1. 欢迎条（已登录显示昵称与校园认证/信用分，未登录显示登录注册引导）
            const HomeWelcomeBanner(),
            const SizedBox(height: 16),

            // 2. 搜索框（未登录也能用）
            const HomeSearchField(),
            const SizedBox(height: 20),

            // 3. 我的待办（仅登录用户；未登录整块不出现）
            Obx(() {
              if (!authController.isLoggedIn.value) {
                return const SizedBox.shrink();
              }
              return Padding(
                padding: const EdgeInsets.only(bottom: 20),
                child: HomeTodoSection(controller: _controller),
              );
            }),

            // 4. 最新商品
            HomeLatestGoodsSection(controller: _controller),
          ],
        ),
      ),
    );
  }
}
