import 'package:get/get.dart';
import '../controllers/favorite_controller.dart';
import '../controllers/goods_controller.dart';
import '../controllers/history_controller.dart';
import '../controllers/order_controller.dart';
import '../controllers/review_controller.dart';
import '../pages/auth/login_page.dart';
import '../pages/auth/register_page.dart';
import '../pages/favorite/favorite_page.dart';
import '../pages/goods/create_goods_page.dart';
import '../pages/goods/goods_detail_page.dart';
import '../pages/goods/goods_list_page.dart';
import '../pages/goods/my_goods_page.dart';
import '../pages/history/history_page.dart';
import '../pages/home/home_page.dart';
import '../pages/order/my_orders_page.dart';
import '../pages/order/order_detail_page.dart';
import '../pages/profile/profile_page.dart';
import '../pages/profile/student_verify_page.dart';
import 'app_routes.dart';

/// GetX 路由映射配置
///
/// 关于 `binding`：页面级控制器（订单 / 商品 / 收藏 / 足迹 / 评价）都通过
/// `Get.lazyPut(..., fenix: false)`（非 permanent）在本路由的 binding 中注册，
/// 因此实例与**路由同生命周期**——进入页面创建、离开页面由 GetX 释放。
///
/// 为什么必须这样：`Get.put` 遇到已存在的同类型 key 时是"取回旧实例"而非替换，
/// 于是页面级控制器退化成全局单例被多个页面共用，任一页面写入的
/// `errorMessage` / `currentOrder` 都会串到另一个页面（实测：在商品详情下单失败后
/// 回到"我的订单"，列表页显示"创建订单失败"而不是空态）。
/// 另外，语义不同且可能同时共存的页面实例用 tag 区分
/// （见 [OrderController.tagMyOrders]、[OrderController.tagCreate]、
/// [GoodsController.tagMyGoods]）。
class AppPages {
  static final routes = [
    GetPage(
      name: AppRoutes.initial,
      page: () => const HomePage(),
      transition: Transition.fadeIn,
    ),
    GetPage(
      name: AppRoutes.home,
      page: () => const HomePage(),
    ),
    GetPage(
      name: AppRoutes.login,
      page: () => const LoginPage(),
      transition: Transition.rightToLeft,
    ),
    GetPage(
      name: AppRoutes.register,
      page: () => const RegisterPage(),
      transition: Transition.rightToLeft,
    ),
    GetPage(
      name: AppRoutes.profile,
      page: () => const ProfilePage(),
      binding: BindingsBuilder(() {
        Get.lazyPut<ReviewController>(() => ReviewController(), fenix: false);
      }),
      transition: Transition.rightToLeft,
    ),
    GetPage(
      name: AppRoutes.studentVerify,
      page: () => const StudentVerifyPage(),
      transition: Transition.rightToLeft,
    ),
    // Stage 2: 商品中心路由
    GetPage(
      name: AppRoutes.goodsList,
      page: () => const GoodsListPage(),
      binding: BindingsBuilder(() {
        // 集市页（默认实例）：分类 + 商品列表 + 热搜/搜索历史
        Get.lazyPut<GoodsController>(() => GoodsController(), fenix: false);
      }),
      transition: Transition.rightToLeft,
    ),
    GetPage(
      name: AppRoutes.goodsDetail,
      page: () => const GoodsDetailPage(),
      binding: BindingsBuilder(() {
        // 商品详情需要：评价状态（详情页内联展示）+ "立即购买"下单用的订单控制器。
        // 下单用独立 tag，避免它写入的 errorMessage 影响到"我的订单"列表页。
        Get.lazyPut<ReviewController>(() => ReviewController(), fenix: false);
        Get.lazyPut<OrderController>(
          () => OrderController(),
          tag: OrderController.tagCreate,
          fenix: false,
        );
      }),
      transition: Transition.rightToLeft,
    ),
    GetPage(
      name: AppRoutes.goodsCreate,
      page: () => const CreateGoodsPage(),
      transition: Transition.downToUp,
    ),
    GetPage(
      name: AppRoutes.goodsMy,
      page: () => const MyGoodsPage(),
      binding: BindingsBuilder(() {
        // "我的发布"只需要自己的商品列表：不预热集市页的分类 / 商品列表 / 热搜
        Get.lazyPut<GoodsController>(
          () => GoodsController(autoLoadMarketplace: false),
          tag: GoodsController.tagMyGoods,
          fenix: false,
        );
      }),
      transition: Transition.rightToLeft,
    ),
    // Stage 3: 收藏与浏览历史
    GetPage(
      name: AppRoutes.favorite,
      page: () => const FavoritePage(),
      binding: BindingsBuilder(() {
        Get.lazyPut<FavoriteController>(() => FavoriteController(), fenix: false);
      }),
      transition: Transition.rightToLeft,
    ),
    GetPage(
      name: AppRoutes.history,
      page: () => const HistoryPage(),
      binding: BindingsBuilder(() {
        Get.lazyPut<HistoryController>(() => HistoryController(), fenix: false);
      }),
      transition: Transition.rightToLeft,
    ),
    // Stage 4: 订单模块路由
    GetPage(
      name: AppRoutes.orderMy,
      page: () => const MyOrdersPage(),
      binding: BindingsBuilder(() {
        // "我的订单"列表页专属实例：视角、状态筛选、列表状态都在这里
        Get.lazyPut<OrderController>(
          () => OrderController(),
          tag: OrderController.tagMyOrders,
          fenix: false,
        );
      }),
      transition: Transition.rightToLeft,
    ),
    GetPage(
      name: AppRoutes.orderDetail,
      page: () => const OrderDetailPage(),
      binding: BindingsBuilder(() {
        // 订单详情用**无 tag 的默认实例**：评价提交成功后需要按订单 ID 回刷详情，
        // 而跨控制器回刷（ReviewController -> OrderController）只按类型查找。
        Get.lazyPut<OrderController>(() => OrderController(), fenix: false);
        Get.lazyPut<ReviewController>(() => ReviewController(), fenix: false);
      }),
      transition: Transition.rightToLeft,
    ),
  ];
}
