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

  // 服务层抛出的统一异常：message 已经是给人看的中文文案，直接返回
  if (error is ApiException) {
    final String message = error.message.trim();
    return message.isEmpty ? base : message;
  }

  if (error is DioException) {
    // 1. 服务端业务提示优先（响应体结构与成功时一致：{code,message,data,timestamp}）
    final String? serverMessage = serverMessageOf(error.response?.data);
    if (serverMessage != null) {
      return serverMessage;
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

    // 3. 有状态码但无 message：把状态码带上，便于用户描述问题。
    //    对后端新增的、语义明确的业务状态额外给一句可读解释，
    //    避免服务端漏发 message 时用户只看到"失败（HTTP 409）"这种说不出所以然的提示。
    final int? status = error.response?.statusCode;
    if (status == null) return base;
    final String? hint = statusHintFor(status);
    return hint == null ? '$base（HTTP $status）' : '$base（$hint）';
  }

  // 非网络异常（例如服务层显式抛出的业务提示）
  String message = error.toString();
  if (message.startsWith('Exception: ')) {
    message = message.substring(11);
  }
  message = message.trim();
  return message.isEmpty ? base : message;
}

/// 从响应体里读服务端业务提示（响应体结构：`{code,message,data,timestamp}`）。
///
/// 返回 null 表示"响应体里没有可用的 message"（不是 Map / 字段缺失 / 只有空白），
/// 由调用方决定兜底文案。
///
/// 这是"服务端 message 从哪来"的**唯一实现**：错误通道（[describeApiError] 从
/// `DioException.response.data` 取）与"HTTP 成功但业务码非 200"这条防御分支
/// （[serverMessageOr] 从 `Response.data` 取）都走这里，避免两处各写一遍解析、
/// 改动时只改一处（此前三个服务类各自实现了同一段取文逻辑）。
String? serverMessageOf(Object? responseBody) {
  if (responseBody is Map) {
    final String message = (responseBody['message'] ?? '').toString().trim();
    if (message.isNotEmpty) {
      return message;
    }
  }
  return null;
}

/// 取服务端业务提示，拿不到时回退调用方给的中文兜底文案。
///
/// 用于"HTTP 已经成功（Dio 未抛异常）、但响应体里的业务码不是 200"这一防御分支：
/// 后端现在把业务错误按业务码映射为真实的 HTTP 状态（400/403/404/409/422/429），
/// 因此正常路径下这条分支不会被走到——但不能因此把响应体里的业务提示丢掉：
/// 一旦后端某天回落到"200 + code != 200"（或中间层改写了状态码），
/// 这里仍能给出服务端原文，而不是一句笼统的"失败"。
String serverMessageOr(Response<dynamic> response, String fallback) {
  return serverMessageOf(response.data) ?? fallback;
}

/// 无服务端 message 时的状态码兜底解释（只覆盖语义足够明确的状态）。
///
/// 注意：这是**兜底**文案。服务端一旦返回 message（业务错误的正常形态），
/// [describeApiError] 的第 1 步就直接采用它，不会走到这里。
String? statusHintFor(int status) {
  switch (status) {
    case 409:
      // 本批次后端新增：校园邮箱已被他人认证 / 商品状态并发变更等冲突
      return '与当前状态冲突，可能已被他人占用，请刷新后重试';
    case 429:
      // 本批次后端新增：AI 助手 10 次/分钟、50 次/天，校园邮箱 3 次/24h
      return '操作过于频繁，请稍后再试';
    default:
      return null;
  }
}

/// 服务层对外的统一异常类型。
///
/// 为什么需要它：原先服务层 `catch (e) { return []; }` 把"断网/超时/401"与
/// "后端确实没有数据"折叠成同一个空集合，UI 只能显示空态，用户以为数据被删了。
/// 现在服务层把失败一律转成 [ApiException] 抛出，控制器据此把 `errorMessage`
/// 与空数据区分开：`errorMessage` 非空 => 错误态 + 重试；为空且列表为空 => 真正的空态。
///
/// message 永远是可直接展示给用户的中文文案（由 [describeApiError] 产出），
/// 排障细节通过 [cause] 保留在日志里，不再拼进用户可见文本。
class ApiException implements Exception {
  ApiException(this.message, {this.statusCode, this.cause});

  /// 把任意底层异常（DioException / 解析异常 / 业务异常）归一为 [ApiException]。
  ///
  /// - 已是 [ApiException] 时原样返回，避免重复包装丢失原始信息。
  /// - 文案走 [describeApiError]，保证"服务端 message 优先"的既有约定不退化。
  factory ApiException.from(
    Object error, {
    String? fallback,
    int? statusCode,
    String? timeoutMessage,
  }) {
    if (error is ApiException) return error;
    return ApiException(
      describeApiError(error, fallback: fallback, timeoutMessage: timeoutMessage),
      statusCode: statusCode ??
          (error is DioException ? error.response?.statusCode : null),
      cause: error,
    );
  }

  /// 可直接展示给用户的中文文案。
  final String message;

  /// 对应的 HTTP 状态码（能拿到时），供上层做 401/404 之类的分支判断。
  final int? statusCode;

  /// 原始异常，仅用于日志/排障。
  final Object? cause;

  @override
  String toString() => message.isEmpty ? 'ApiException' : message;
}
