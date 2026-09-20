import 'dart:async';
import 'package:dio/dio.dart';
// 仅为 @visibleForTesting 注解（package:flutter/foundation.dart 转出的 meta 注解）
import 'package:flutter/foundation.dart' show visibleForTesting;
import '../config/app_config.dart';
import '../controllers/auth_controller.dart';
import '../routes/app_routes.dart';
import '../services/storage_service.dart';
import '../utils/app_logger.dart';
import 'package:get/get.dart' as getx;

/// 统一 Dio 网络客户端封装 (集成 401 自动无感刷新与并发请求排队重放)
///
/// 设计要点：
/// 1. [onError] 拦截 401，对受保护接口触发「无感静默刷新 Token」。
/// 2. [_tryRefreshToken] 通过单例 Completer 充当互斥锁，保证多个并发请求
///    同时遭遇 401 时，仅向服务端发起一次 /auth/refresh 请求，其余请求复用结果。
/// 3. [_retryRequest] 在刷新成功后携带新令牌重放上一次失败的原请求。
/// 4. 刷新彻底失败（无 Refresh Token / Refresh Token 失效 / 被封禁）时，
///    清空本地全部凭据并优雅跳转登录页。
class DioClient {
  late final Dio dio;

  static final DioClient _instance = DioClient._internal();
  factory DioClient() => _instance;

  /// 无感刷新互斥锁：非空表示已有一次刷新请求在途。
  Completer<bool>? _refreshCompleter;

  /// 会话过期处理去重标记：避免一次刷新失败触发多个并发 401 时重复弹窗。
  /// 刷新成功时复位，**登录成功时也必须复位**（见 [markSessionRestored]）。
  bool _sessionExpiredHandled = false;

  /// 重放请求防无限循环标记键（写入 RequestOptions.extra）。
  static const String _retriedFlag = '_silentRefreshRetried';

  DioClient._internal() {
    final options = BaseOptions(
      baseUrl: AppConfig.apiBaseUrl,
      connectTimeout: const Duration(milliseconds: AppConfig.connectTimeout),
      receiveTimeout: const Duration(milliseconds: AppConfig.receiveTimeout),
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'application/json',
      },
    );

    dio = Dio(options);

    // 请求与认证拦截器
    dio.interceptors.add(
      InterceptorsWrapper(
        onRequest: (options, handler) async {
          if (getx.Get.isRegistered<StorageService>()) {
            final storage = getx.Get.find<StorageService>();
            final token = await storage.getToken();
            if (token != null && token.isNotEmpty) {
              options.headers['Authorization'] = 'Bearer $token';
            }
          }
          AppLogger.debug('[Dio Req] ${options.method} ${options.uri}');
          return handler.next(options);
        },
        onResponse: (response, handler) {
          AppLogger.debug(
              '[Dio Resp] ${response.statusCode} ${response.requestOptions.uri}');
          return handler.next(response);
        },
        onError: (DioException e, handler) async {
          AppLogger.debug(
              '[Dio Err] ${e.response?.statusCode} ${e.message} path=${e.requestOptions.path}');

          final statusCode = e.response?.statusCode;
          final requestPath = e.requestOptions.path;

          // 拦截 401 且排除登录、刷新及注册接口自身（避免错误凭据触发死循环）
          final isAuthEndpoint = requestPath.contains('/auth/login') ||
              requestPath.contains('/auth/refresh') ||
              requestPath.contains('/auth/register');

          if (statusCode == 401 && !isAuthEndpoint) {
            // 已重放过仍 401：说明刷新后的令牌依旧无效，直接走登出，防止无限刷新
            if (e.requestOptions.extra[_retriedFlag] == true) {
              await _forceLogoutAndClear();
              return handler.next(e);
            }

            // 1. 尝试使用本地存储的 Refresh Token 进行无感静默刷新
            final refreshed = await _tryRefreshToken();
            if (refreshed) {
              // 刷新成功：更新请求头并重新发起上一次失败的原请求
              try {
                final retryResponse = await _retryRequest(e.requestOptions);
                return handler.resolve(retryResponse);
              } on DioException catch (retryErr) {
                return handler.next(retryErr);
              } catch (retryUnknown, retryStack) {
                // 重放失败但非 DioException（解析/拦截器异常）：记录后回传原 401
                AppLogger.warn('[DioClient] 刷新后重放请求失败',
                    error: retryUnknown, stackTrace: retryStack);
                return handler.next(e);
              }
            } else {
              // 刷新失败或 Refresh Token 也已过期：彻底清空本地缓存并跳转登录
              await _forceLogoutAndClear();
              return handler.next(e);
            }
          }

          return handler.next(e);
        },
      ),
    );
  }

  // ---------------------------------------------------------------------------
  // 无感刷新核心实现
  // ---------------------------------------------------------------------------

  /// 尝试使用本地 Refresh Token 静默刷新 Access Token。
  ///
  /// 通过 [_refreshCompleter] 充当互斥锁：
  /// - 若已有刷新在途，直接复用其 Future 结果，确保仅向服务端请求一次。
  /// - 返回 true 表示刷新成功且新 Access Token 已写入本地与内存态。
  Future<bool> _tryRefreshToken() async {
    // 并发 401：已有刷新任务在途，直接复用其结果
    if (_refreshCompleter != null) {
      return _refreshCompleter!.future;
    }

    final completer = Completer<bool>();
    _refreshCompleter = completer;

    try {
      final storage = _resolveStorage();
      final refreshToken = await storage?.getRefreshToken();

      if (refreshToken == null || refreshToken.isEmpty) {
        completer.complete(false);
        return false;
      }

      // 使用独立轻量 Dio 实例发起刷新请求，避免拦截器递归死循环
      final refreshDio = _buildRefreshDio();
      final refreshResp = await refreshDio.post(
        '/auth/refresh',
        data: {'refreshToken': refreshToken},
      );

      final data = refreshResp.data;
      if (refreshResp.statusCode == 200 && data is Map && data['code'] == 200) {
        final resData = data['data'] as Map?;
        final newAccessToken = resData?['accessToken'] as String?;
        final newRefreshToken = resData?['refreshToken'] as String?;
        if (newAccessToken != null && newAccessToken.isNotEmpty) {
          await storage?.saveToken(newAccessToken);
          if (newRefreshToken != null && newRefreshToken.isNotEmpty) {
            await storage?.saveRefreshToken(newRefreshToken);
          }
          _syncAuthControllerToken(newAccessToken);
          _sessionExpiredHandled = false; // 会话已恢复，允许后续真实过期再次提示
          completer.complete(true);
        } else {
          completer.complete(false);
        }
      } else {
        completer.complete(false);
      }
    } catch (err, stack) {
      AppLogger.warn('[_tryRefreshToken failed]', error: err, stackTrace: stack);
      completer.complete(false);
    } finally {
      // 无论成功失败，释放互斥锁，允许后续全新周期再次刷新
      _refreshCompleter = null;
    }

    return await completer.future;
  }

  /// 携带最新 Access Token 重放上一次失败的原请求。
  Future<Response<dynamic>> _retryRequest(RequestOptions requestOptions) async {
    final storage = _resolveStorage();
    final token = await storage?.getToken();

    final options = requestOptions.copyWith(
      // 标记已重放，避免刷新后仍 401 触发无限循环
      extra: {...requestOptions.extra, _retriedFlag: true},
      headers: {
        ...requestOptions.headers,
        if (token != null && token.isNotEmpty)
          'Authorization': 'Bearer $token',
      },
    );

    // 重新走完整拦截器链（onRequest 会再次注入最新令牌）
    return dio.fetch(options);
  }

  /// 刷新彻底失败时：清空本地全部凭据并优雅跳转登录页。
  ///
  /// 处理动作全部委托给 [AuthController.handleSessionExpired]（单一落点）：
  /// 它负责清空 storage、清空内存态、提示并跳转登录。这里只保留"未注册
  /// AuthController"（如单元测试直接驱动 Dio 的场景）下的兜底清理。
  Future<void> _forceLogoutAndClear() async {
    // 同一次会话过期仅处理一次，避免并发 401 重复弹窗
    if (_sessionExpiredHandled) return;
    _sessionExpiredHandled = true;

    if (getx.Get.isRegistered<AuthController>()) {
      await getx.Get.find<AuthController>().handleSessionExpired();
      return;
    }

    final storage = _resolveStorage();
    await storage?.clearAll();

    try {
      if (getx.Get.context != null) {
        getx.Get.offAllNamed(AppRoutes.LOGIN);
        getx.Get.snackbar(
          '登录已过期',
          '请重新登录以继续使用',
          snackPosition: getx.SnackPosition.TOP,
        );
      }
    } catch (e, stack) {
      // 跳转失败（例如测试环境没有 Navigator）不应吞掉：记录以便排障
      AppLogger.warn('[DioClient] 会话过期跳转登录失败', error: e, stackTrace: stack);
    }
  }

  // ---------------------------------------------------------------------------
  // 内部工具方法
  // ---------------------------------------------------------------------------

  /// 可在单元测试中注入自定义的 refreshDio
  @visibleForTesting
  Dio? customRefreshDio;

  /// 复位"本次会话已过期并处理过"的标记。
  ///
  /// 必须在**登录成功后**调用：该标记的语义是"当前这轮会话已经过期并处理完毕"，
  /// 用户重新登录即进入全新会话，若不复位，同一进程内第二次会话过期会被静默忽略
  /// （不清 storage、不跳登录、不提示），用户会停在"看似已登录但请求全 401"的状态。
  void markSessionRestored() {
    _sessionExpiredHandled = false;
  }

  /// 复位会话与刷新锁状态（在用户登出或自动化测试重置时调用）
  void resetSessionState() {
    _refreshCompleter = null;
    _sessionExpiredHandled = false;
    customRefreshDio = null;
  }

  StorageService? _resolveStorage() {
    return getx.Get.isRegistered<StorageService>()
        ? getx.Get.find<StorageService>()
        : null;
  }

  void _syncAuthControllerToken(String token) {
    if (getx.Get.isRegistered<AuthController>()) {
      getx.Get.find<AuthController>().token.value = token;
    }
  }

  Dio _buildRefreshDio() {
    if (customRefreshDio != null) return customRefreshDio!;
    return Dio(BaseOptions(
      baseUrl: AppConfig.apiBaseUrl,
      connectTimeout: const Duration(milliseconds: AppConfig.connectTimeout),
      receiveTimeout: const Duration(milliseconds: AppConfig.receiveTimeout),
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'application/json',
      },
    ));
  }
}
