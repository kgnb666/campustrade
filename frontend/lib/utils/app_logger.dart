import 'package:flutter/foundation.dart';

/// 轻量日志层（零第三方依赖）。
///
/// 为什么需要它：全前端此前散落 79 处裸 `debugPrint`，其中仅 6 处带 `kDebugMode` 守卫；
/// 而 `debugPrint` 在 release 构建下既不输出也不落盘、更不会上报，线上出问题时
/// 只能靠用户复述现象。这里把日志收敛到单一入口，并给出一个可注入的上报通道。
///
/// 行为约定：
/// - debug 构建（[kDebugMode]）：输出到控制台；
/// - release 构建：默认完全静默（[consoleEnabled] 初值即 `kDebugMode`）；
/// - 任何构建：`error` 级别在 [onErrorReport] 已注入时额外回调一次
///   —— 这是 release 下唯一的上报通道。
///
/// 接入远端日志 / APM 时在 `main()` 里注入即可：
/// ```dart
/// AppLogger.onErrorReport = (message, {error, stackTrace}) async {
///   await myCrashReporter.report(message, error, stackTrace);
/// };
/// ```
class AppLogger {
  const AppLogger._();

  /// 关键错误的上报回调（未注入时 release 下保持静默）。
  static void Function(
    String message, {
    Object? error,
    StackTrace? stackTrace,
  })? onErrorReport;

  /// 控制台输出开关，默认跟随构建模式。
  ///
  /// 声明为可变字段的唯一目的，是让测试能够断言"release 下静默"这条契约
  /// （`kDebugMode` 是编译期常量，测试进程里恒为 true，无法直接模拟 release）。
  static bool consoleEnabled = kDebugMode;

  /// 流程性信息（请求/响应轨迹、页面加载等），仅在控制台可见。
  static void debug(String message) => _emit('DEBUG', message);

  /// 正常但值得留痕的信息（降级、跳过等）。
  static void info(String message) => _emit('INFO', message);

  /// 可自愈或影响范围有限的异常（装饰性数据降级、重试成功等）。
  static void warn(
    String message, {
    Object? error,
    StackTrace? stackTrace,
  }) {
    _emit('WARN', message, error: error, stackTrace: stackTrace);
  }

  /// 关键错误：登录失败、支付/订单动作失败、存储异常、数据解析异常等。
  ///
  /// 除控制台输出外，还会回调 [onErrorReport]（若已注入）。
  ///
  /// 注意：调用了用户的回调并不代表它一定执行成功——但**无论失败与否**，
  /// 都绝不能因为它反噬业务（业务侧的清理与错误态已经写完）。
  static void error(
    String message, {
    Object? error,
    StackTrace? stackTrace,
  }) {
    _emit('ERROR', message, error: error, stackTrace: stackTrace);

    final void Function(
      String message, {
      Object? error,
      StackTrace? stackTrace,
    })? reporter = onErrorReport;
    if (reporter == null) return;

    try {
      reporter(message, error: error, stackTrace: stackTrace);
    } catch (reportError, reportStack) {
      // 上报通道本身出错时只做控制台提示，绝不向上抛
      _emit(
        'WARN',
        '[AppLogger] onErrorReport 回调自身抛出异常，已忽略',
        error: reportError,
        stackTrace: reportStack,
      );
    }
  }

  static void _emit(
    String level,
    String message, {
    Object? error,
    StackTrace? stackTrace,
  }) {
    if (!consoleEnabled) return;

    final StringBuffer buffer = StringBuffer('[$level] $message');
    if (error != null) {
      buffer.write(' | error=$error');
    }
    if (stackTrace != null) {
      buffer.write('\n$stackTrace');
    }
    // 沿用 debugPrint：release 下本分支不会执行，debug 下自带节流与长行折行
    debugPrint(buffer.toString());
  }
}
