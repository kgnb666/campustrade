import 'package:get/get.dart';
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
      transition: Transition.rightToLeft,
    ),
    GetPage(
      name: AppRoutes.goodsDetail,
      page: () => const GoodsDetailPage(),
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
      transition: Transition.rightToLeft,
    ),
    // Stage 3: 收藏与浏览历史
    GetPage(
      name: AppRoutes.favorite,
      page: () => const FavoritePage(),
      transition: Transition.rightToLeft,
    ),
    GetPage(
      name: AppRoutes.history,
      page: () => const HistoryPage(),
      transition: Transition.rightToLeft,
    ),
    // Stage 4: 订单模块路由
    GetPage(
      name: AppRoutes.orderMy,
      page: () => const MyOrdersPage(),
      transition: Transition.rightToLeft,
    ),
    GetPage(
      name: AppRoutes.orderDetail,
      page: () => const OrderDetailPage(),
      transition: Transition.rightToLeft,
    ),
  ];
}
