import 'package:flutter/material.dart';
import 'package:get/get.dart';

import 'app_logger.dart';

/// UI 反馈的安全包装。
///
/// 为什么需要它：控制器里的 `Get.snackbar` 是"从状态层触发的 UI 副作用"，
/// 而它依赖当前 Navigator/Overlay。以下场景没有 Overlay 时会直接抛异常
/// （实测：`Null check operator used on a null value` @ SnackbarController._configureOverlay）：
/// - 单元测试中直接驱动控制器；
/// - 页面已销毁、App 正在退出，但异步请求刚刚失败。
/// 提示展示失败绝不应该反过来破坏业务状态（错误态、页码回滚都已经写完），
/// 因此这里统一兜底并留下日志。
/// 提示派发器：默认调用 GetX 的 snackbar；测试可替换为记录器，
/// 从而对"提示的时机/时长"做确定性断言（GetX snackbar 的动画与 widget 测试时钟不兼容，
/// 直接断言渲染结果会触发框架层的 AnimationController 断言）。
typedef SnackbarDispatcher = void Function(
  String title,
  String message,
  SnackPosition snackPosition,
  Color? backgroundColor,
  Color? colorText,
  Duration duration,
);

SnackbarDispatcher snackbarDispatcher = _defaultSnackbarDispatcher;

/// 关闭提示的钩子，同样可替换以便测试断言"跳转前会先清掉残留提示"。
void Function() snackbarCloser = _defaultSnackbarCloser;

void _defaultSnackbarDispatcher(
  String title,
  String message,
  SnackPosition snackPosition,
  Color? backgroundColor,
  Color? colorText,
  Duration duration,
) {
  Get.snackbar(
    title,
    message,
    snackPosition: snackPosition,
    backgroundColor: backgroundColor,
    colorText: colorText,
    duration: duration,
  );
}

void _defaultSnackbarCloser() => Get.closeAllSnackbars();

void safeSnackbar(
  String title,
  String message, {
  SnackPosition snackPosition = SnackPosition.BOTTOM,
  Color? backgroundColor,
  Color? colorText,
  Duration? duration,
}) {
  // 默认 3 秒自动消失。显式给出（而不是依赖 GetX 的默认值）是为了保证每条提示都有明确的存活时间上限。
  final effectiveDuration = duration ?? const Duration(seconds: 3);
  // GetX 的 snackbar 内部直接对 overlayContext 做空断言（且展示动作是排到队列里
  // 异步执行的，无法用 try/catch 拦住）。因此必须在调用前显式检查 overlay 是否存在：
  // 没有 overlay（单元测试直接驱动控制器、页面已销毁、App 退出中）就只留日志。
  if (Get.overlayContext == null) {
    AppLogger.debug('[safeSnackbar] 当前无 Overlay，跳过提示（$title / $message）');
    return;
  }
  try {
    snackbarDispatcher(
      title,
      message,
      snackPosition,
      backgroundColor,
      colorText,
      effectiveDuration,
    );
  } catch (e, stack) {
    AppLogger.warn('[safeSnackbar] 提示展示失败（$title / $message）', error: e, stackTrace: stack);
  }
}

/// 先跳转、再提示——**只要是"提示完立刻换页"的场景，都必须用这个而不是 [safeSnackbar]+跳转**。
///
/// 原因（实测缺陷）：在 `offAllNamed`/`offNamed` 之前弹出的 snackbar，其"自动关闭定时器"
/// 随旧路由一起被销毁，提示会**永久留在 overlay 上不消失**。登录成功提示一直挂在底部
/// 就是这个问题（用户已逛到商品详情页，左下角还显示"登录成功"）。
/// 这里改为：先清掉可能残留的旧提示 → 执行跳转 → 等新路由挂载后再弹提示，
/// 于是定时器属于新路由，3 秒后会正常自动关闭。
void safeSnackbarAfterNavigation(
  void Function() navigate,
  String title,
  String message, {
  SnackPosition snackPosition = SnackPosition.BOTTOM,
  Color? backgroundColor,
  Color? colorText,
  Duration? duration,
}) {
  closeSnackbars();
  navigate();
  // 跳转已触发重建，等这一帧结束后新路由的 overlay 就绪再弹提示。
  WidgetsBinding.instance.addPostFrameCallback((_) {
    safeSnackbar(
      title,
      message,
      snackPosition: snackPosition,
      backgroundColor: backgroundColor,
      colorText: colorText,
      duration: duration,
    );
  });
}

/// 关闭当前所有提示（无 Overlay 或框架内部异常时静默降级）。
void closeSnackbars() {
  try {
    snackbarCloser();
  } catch (e) {
    AppLogger.debug('[closeSnackbars] 关闭提示失败: $e');
  }
}

/// 安全跳转：清空路由栈后进入 [route]。
///
/// 与 [safeSnackbar] 同理——跳转依赖当前 Navigator（`Get.key.currentState`）。
/// 在没有 GetMaterialApp/已销毁的环境里 `Get.offAllNamed` 会抛
/// "contextless navigation" 异常；若让它逃逸，"登录已经成功"的事实会被
/// 上层误判为失败（token 已写入 storage，却向用户报"登录失败"）。
void safeOffAllNamed(String route) => _safeNavigate(
      () => Get.offAllNamed(route),
      'offAllNamed($route)',
    );

/// 安全跳转：替换当前页为 [route]。
void safeOffNamed(String route) => _safeNavigate(
      () => Get.offNamed(route),
      'offNamed($route)',
    );

void _safeNavigate(void Function() action, String label) {
  try {
    if (Get.key.currentState == null) {
      AppLogger.debug('[safeNavigate] 当前没有 Navigator，跳过 $label');
      return;
    }
    action();
  } catch (e, stack) {
    AppLogger.warn('[safeNavigate] $label 失败', error: e, stackTrace: stack);
  }
}
