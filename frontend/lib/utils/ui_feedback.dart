import 'package:flutter/material.dart';
import 'package:get/get.dart';

/// UI 反馈的安全包装。
///
/// 为什么需要它：控制器里的 `Get.snackbar` 是"从状态层触发的 UI 副作用"，
/// 而它依赖当前 Navigator/Overlay。以下场景没有 Overlay 时会直接抛异常
/// （实测：`Null check operator used on a null value` @ SnackbarController._configureOverlay）：
/// - 单元测试中直接驱动控制器；
/// - 页面已销毁、App 正在退出，但异步请求刚刚失败。
/// 提示展示失败绝不应该反过来破坏业务状态（错误态、页码回滚都已经写完），
/// 因此这里统一兜底并留下日志。
void safeSnackbar(
  String title,
  String message, {
  SnackPosition snackPosition = SnackPosition.BOTTOM,
  Color? backgroundColor,
  Color? colorText,
  Duration? duration,
}) {
  // GetX 的 snackbar 内部直接对 overlayContext 做空断言（且展示动作是排到队列里
  // 异步执行的，无法用 try/catch 拦住）。因此必须在调用前显式检查 overlay 是否存在：
  // 没有 overlay（单元测试直接驱动控制器、页面已销毁、App 退出中）就只留日志。
  if (Get.overlayContext == null) {
    debugPrint('[safeSnackbar] 当前无 Overlay，跳过提示（$title / $message）');
    return;
  }
  try {
    Get.snackbar(
      title,
      message,
      snackPosition: snackPosition,
      backgroundColor: backgroundColor,
      colorText: colorText,
      duration: duration,
    );
  } catch (e, stack) {
    debugPrint('[safeSnackbar] 提示展示失败（$title / $message）: $e\n$stack');
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
      debugPrint('[safeNavigate] 当前没有 Navigator，跳过 $label');
      return;
    }
    action();
  } catch (e, stack) {
    debugPrint('[safeNavigate] $label 失败: $e\n$stack');
  }
}
