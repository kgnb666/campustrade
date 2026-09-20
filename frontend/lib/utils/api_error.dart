import 'package:dio/dio.dart';

/// 统一的"把异常翻译成给用户看的一句话"的入口。
///
/// 为什么需要它：后端现在把业务错误按业务码映射为真实 HTTP 状态（400/403/404/409/422/429...），
/// Dio 默认只把 2xx 视为成功，因此业务错误不再走"200 + body.code != 200"的分支，
/// 而是以 [DioException] 抛出。若各处仍直接展示 `e.toString()`，
/// 用户会看到一整段英文的 HTTP 状态说明，而不是服务端的业务提示。
///
/// 本函数优先取服务端响应体里的 `message`（这正是业务文案的唯一来源），
/// 只有在拿不到时才退回网络类兜底文案，从而保证提示文案不因错误通道变化而退化。
String describeApiError(
  Object error, {
  String? fallback,
  String? timeoutMessage,
}) {
  const String defaultFallback = '操作失败，请稍后重试';
  final String base = fallback ?? defaultFallback;

  if (error is DioException) {
    // 1. 服务端业务提示优先（响应体结构与成功时一致：{code,message,data,timestamp}）
    final dynamic data = error.response?.data;
    if (data is Map) {
      final String message = (data['message'] ?? '').toString().trim();
      if (message.isNotEmpty) {
        return message;
      }
    }

    // 2. 网络类异常给出可操作的兜底文案
    switch (error.type) {
      case DioExceptionType.connectionTimeout:
      case DioExceptionType.sendTimeout:
      case DioExceptionType.receiveTimeout:
        return timeoutMessage ?? '网络请求超时，请检查网络后重试';
      case DioExceptionType.connectionError:
        return '网络连接失败，请检查网络后重试';
      case DioExceptionType.cancel:
        return '请求已取消';
      case DioExceptionType.badCertificate:
        return '服务端证书校验失败';
      case DioExceptionType.badResponse:
      case DioExceptionType.unknown:
      case DioExceptionType.transformTimeout:
        break;
    }

    // 3. 有状态码但无 message：把状态码带上，便于用户描述问题
    final int? status = error.response?.statusCode;
    return status == null ? base : '$base（HTTP $status）';
  }

  // 非网络异常（例如服务层显式抛出的业务提示）
  String message = error.toString();
  if (message.startsWith('Exception: ')) {
    message = message.substring(11);
  }
  message = message.trim();
  return message.isEmpty ? base : message;
}
