// ignore_for_file: constant_identifier_names
/// 路由路径常量定义
abstract class AppRoutes {
  static const initial = '/';
  static const home = '/home';
  static const login = '/login';
  static const register = '/register';
  static const profile = '/profile';
  static const studentVerify = '/student-verify';
  /// 「无邮箱通道」认证：学生证照片 + 管理员人工审核
  static const studentVerifyManual = '/student-verify-manual';
  /// 管理员审核队列（仅 ROLE_ADMIN 可见）
  static const adminVerifyReview = '/admin/verify-review';

  // 大写别名（兼容规范与快捷引用）
  static const INITIAL = initial;
  static const HOME = home;
  static const LOGIN = login;
  static const REGISTER = register;
  static const PROFILE = profile;
  static const STUDENT_VERIFY = studentVerify;
  static const STUDENT_VERIFY_MANUAL = studentVerifyManual;
  static const ADMIN_VERIFY_REVIEW = adminVerifyReview;

  // Stage 2: 商品模块路由
  static const goodsList = '/goods/list';
  static const goodsDetail = '/goods/detail';
  static const goodsCreate = '/goods/create';
  static const goodsMy = '/goods/my';

  // Stage 3: 互动增强路由
  static const favorite = '/favorite';
  static const history = '/history';

  // Stage 4: 订单模块路由
  static const orderMy = '/orders/my';
  static const orderDetail = '/orders/detail';
}
