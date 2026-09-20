import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:get/get.dart';
import '../utils/app_logger.dart';
import '../utils/constants.dart';

/// 本地高安全键值存储服务 (基于 Flutter Secure Storage)
///
/// 全量读写都做了 try/catch 降级：flutter_secure_storage 在 Web 的非安全上下文
/// （HTTP 且非 localhost）会整体抛错，若把异常漏出去，所有携带 Token 的请求都会
/// 直接失败，表现为"整个 App 都用不了"。降级策略：
/// - 读失败 => 返回 null（等价"本地没有凭据"，请求走未认证路径，由 401 流程兜底）；
/// - 写/删失败 => 静默跳过（内存态 Token 仍然可用，本次会话内功能不受影响）;
/// - 首次降级打印一次警告，避免刷屏同时又能定位原因。
class StorageService extends GetxService {
  late final FlutterSecureStorage _storage;

  /// 是否已进入降级模式（仅用于日志去重）。
  bool _degraded = false;

  /// 初始化。可注入自定义的 [FlutterSecureStorage]（仅用于测试不可用场景的降级行为）。
  Future<StorageService> init({FlutterSecureStorage? storage}) async {
    _storage = storage ??
        const FlutterSecureStorage(
          aOptions: AndroidOptions(encryptedSharedPreferences: true),
        );
    return this;
  }

  /// 统一的安全存储访问包装：任何异常都不外抛，失败时返回 null。
  Future<T?> _guard<T>(String operation, Future<T> Function() action) async {
    try {
      return await action();
    } catch (e, stack) {
      if (!_degraded) {
        _degraded = true;
        AppLogger.error(
          '[StorageService] 安全存储不可用（$operation 失败），已降级为无持久化模式',
          error: e,
          stackTrace: stack,
        );
      }
      return null;
    }
  }

  /// 保存 Token
  Future<void> saveToken(String token) async {
    await _guard<void>('saveToken', () async {
      await _storage.write(key: AppConstants.tokenKey, value: token);
    });
  }

  /// 获取 Token
  Future<String?> getToken() async {
    return _guard<String?>('getToken', () => _storage.read(key: AppConstants.tokenKey));
  }

  /// 清除 Token
  Future<void> clearToken() async {
    await _guard<void>('clearToken', () async {
      await _storage.delete(key: AppConstants.tokenKey);
    });
  }

  /// 保存 Refresh Token
  Future<void> saveRefreshToken(String token) async {
    await _guard<void>('saveRefreshToken', () async {
      await _storage.write(key: AppConstants.refreshTokenKey, value: token);
    });
  }

  /// 获取 Refresh Token
  Future<String?> getRefreshToken() async {
    final primary = await _guard<String?>(
      'getRefreshToken',
      () => _storage.read(key: AppConstants.refreshTokenKey),
    );
    if (primary != null) return primary;
    // 兼容历史遗留键
    return _guard<String?>(
      'getRefreshToken(legacy)',
      () => _storage.read(key: 'refresh_token'),
    );
  }

  /// 清除 Refresh Token
  Future<void> clearRefreshToken() async {
    await _guard<void>('clearRefreshToken', () async {
      await _storage.delete(key: AppConstants.refreshTokenKey);
    });
    await _guard<void>('clearRefreshToken(legacy)', () async {
      await _storage.delete(key: 'refresh_token');
    });
  }

  /// 清空全部本地持久化状态：Access Token、Refresh Token 与用户信息。
  /// 用于会话彻底失效（无感刷新失败）或主动登出时，确保无残留凭据泄露。
  ///
  /// 每一步都独立降级：某一项删除失败不会中断其余项的清理。
  Future<void> clearAll() async {
    await _guard<void>('clearAll(token)', () async {
      await _storage.delete(key: AppConstants.tokenKey);
    });
    await _guard<void>('clearAll(refreshToken)', () async {
      await _storage.delete(key: AppConstants.refreshTokenKey);
    });
    await _guard<void>('clearAll(userInfo)', () async {
      await _storage.delete(key: AppConstants.userInfoKey);
    });
    await _guard<void>('clearAll(legacy refresh_token)', () async {
      await _storage.delete(key: 'refresh_token');
    });
    await _guard<void>('clearAll(deleteAll)', () async {
      await _storage.deleteAll();
    });
  }

  /// 写入通用键值
  Future<void> write(String key, String value) async {
    await _guard<void>('write($key)', () async {
      await _storage.write(key: key, value: value);
    });
  }

  /// 读取通用键值
  Future<String?> read(String key) async {
    return _guard<String?>('read($key)', () => _storage.read(key: key));
  }

  /// 删除通用键值
  Future<void> delete(String key) async {
    await _guard<void>('delete($key)', () async {
      await _storage.delete(key: key);
    });
  }
}
