import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:get/get.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../utils/app_logger.dart';
import '../utils/constants.dart';

/// 本地键值存储服务：主存 [FlutterSecureStorage]，失败时自动降级。
///
/// <h3>为什么需要降级</h3>
/// flutter_secure_storage 在 Web 上依赖浏览器的 Web Crypto（`window.crypto.subtle`），
/// 而该 API **只在安全上下文**（HTTPS 或 localhost）暴露。站点跑在
/// 「HTTP + 公网 IP」时读写会整体抛错，若只做"失败即忽略"，后果是：
/// 登录接口成功返回了 Token，但落到存储时丢失，DioClient 每次取 Token 都是 null，
/// 于是**所有需要认证的请求都变成匿名请求 → 401 → 被"会话失效"流程踢回登录页**。
/// 现象就是"能登录，但一点击就弹回登录页"。
///
/// <h3>现在的三级策略</h3>
/// 1. 内存缓存：任何一次成功读写的值都留在这里（本次会话内必定可用）；
/// 2. [FlutterSecureStorage]：可用时始终优先（Web 的 HTTPS、Android/iOS/桌面）；
/// 3. [SharedPreferences]：仅在安全存储**确实失败之后**才懒加载作为兜底
///    （Web 上落到 localStorage，因此刷新页面后登录态仍在）。
///
/// 安全存储可用的环境（HTTPS、localhost、移动端/桌面端）行为与改造前完全一致，
/// 降级路径不会被触发；生产环境仍应使用 HTTPS，这里只是让 HTTP 部署也能正常工作。
class StorageService extends GetxService {
  late final FlutterSecureStorage _storage;

  /// 内存副本：避免同一会话内反复读盘，也保证降级时至少"本次会话可用"。
  final Map<String, String> _memory = <String, String>{};

  /// 降级后端（懒加载）。加载失败则保持 null，且不再重试。
  SharedPreferences? _fallback;
  bool _fallbackUnavailable = false;

  /// 是否已进入降级模式（仅用于日志去重，避免刷屏）。
  bool _degraded = false;

  /// 初始化。可注入自定义的 [FlutterSecureStorage]（仅用于测试不可用场景的降级行为）。
  Future<StorageService> init({FlutterSecureStorage? storage}) async {
    _storage = storage ??
        const FlutterSecureStorage(
          aOptions: AndroidOptions(encryptedSharedPreferences: true),
        );
    return this;
  }

  /// 降级后端：**只在安全存储失败之后**才尝试初始化，
  /// 这样在安全存储正常的环境（含单元测试的 mock 存储）不会引入额外依赖。
  Future<SharedPreferences?> _fallbackStore() async {
    if (_fallback != null || _fallbackUnavailable) return _fallback;
    try {
      _fallback = await SharedPreferences.getInstance();
    } catch (_) {
      // 例如单元测试环境没有平台实现：标记不可用，后续不再重试。
      _fallbackUnavailable = true;
    }
    return _fallback;
  }

  void _markDegraded(String operation, Object error, StackTrace stack) {
    if (_degraded) return;
    _degraded = true;
    AppLogger.error(
      '[StorageService] 安全存储不可用（$operation 失败），'
      '已降级为「内存 + 本地偏好」持久化（Web 上即 localStorage）',
      error: error,
      stackTrace: stack,
    );
  }

  Future<void> _writeValue(String key, String value) async {
    _memory[key] = value;
    try {
      await _storage.write(key: key, value: value);
      return;
    } catch (e, stack) {
      _markDegraded('write($key)', e, stack);
    }
    try {
      await (await _fallbackStore())?.setString(key, value);
    } catch (_) {
      // 降级后端也失败：至少内存副本仍可用（本次会话有效）。
    }
  }

  Future<String?> _readValue(String key) async {
    try {
      final value = await _storage.read(key: key);
      if (value != null) {
        _memory[key] = value;
        return value;
      }
    } catch (e, stack) {
      _markDegraded('read($key)', e, stack);
    }
    // 安全存储读不到（或不可用）时，先看降级后端，再看内存副本。
    if (_degraded) {
      try {
        final value = (await _fallbackStore())?.getString(key);
        if (value != null) {
          _memory[key] = value;
          return value;
        }
      } catch (_) {
        // 忽略：继续走内存副本。
      }
    }
    return _memory[key];
  }

  Future<void> _deleteValue(String key) async {
    _memory.remove(key);
    try {
      await _storage.delete(key: key);
    } catch (e, stack) {
      _markDegraded('delete($key)', e, stack);
    }
    if (_degraded) {
      try {
        await (await _fallbackStore())?.remove(key);
      } catch (_) {
        // 忽略：内存副本已清。
      }
    }
  }

  /// 保存 Token
  Future<void> saveToken(String token) => _writeValue(AppConstants.tokenKey, token);

  /// 获取 Token
  Future<String?> getToken() => _readValue(AppConstants.tokenKey);

  /// 清除 Token
  Future<void> clearToken() => _deleteValue(AppConstants.tokenKey);

  /// 保存 Refresh Token
  Future<void> saveRefreshToken(String token) =>
      _writeValue(AppConstants.refreshTokenKey, token);

  /// 获取 Refresh Token
  Future<String?> getRefreshToken() async {
    final primary = await _readValue(AppConstants.refreshTokenKey);
    if (primary != null) return primary;
    // 兼容历史遗留键
    return _readValue('refresh_token');
  }

  /// 清除 Refresh Token
  Future<void> clearRefreshToken() async {
    await _deleteValue(AppConstants.refreshTokenKey);
    await _deleteValue('refresh_token');
  }

  /// 清空全部本地持久化状态：Access Token、Refresh Token 与用户信息。
  /// 用于会话彻底失效（无感刷新失败）或主动登出时，确保无残留凭据泄露。
  ///
  /// 每一步都独立降级：某一项删除失败不会中断其余项的清理。
  Future<void> clearAll() async {
    _memory.clear();
    await _deleteValue(AppConstants.tokenKey);
    await _deleteValue(AppConstants.refreshTokenKey);
    await _deleteValue(AppConstants.userInfoKey);
    await _deleteValue('refresh_token');
    try {
      await _storage.deleteAll();
    } catch (e, stack) {
      _markDegraded('deleteAll', e, stack);
    }
  }

  /// 写入通用键值
  Future<void> write(String key, String value) => _writeValue(key, value);

  /// 读取通用键值
  Future<String?> read(String key) => _readValue(key);

  /// 删除通用键值
  Future<void> delete(String key) => _deleteValue(key);
}
