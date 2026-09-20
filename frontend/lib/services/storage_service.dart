import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:get/get.dart';
import '../utils/constants.dart';

/// 本地高安全键值存储服务 (基于 Flutter Secure Storage)
class StorageService extends GetxService {
  late final FlutterSecureStorage _storage;

  Future<StorageService> init() async {
    _storage = const FlutterSecureStorage(
      aOptions: AndroidOptions(encryptedSharedPreferences: true),
    );
    return this;
  }

  /// 保存 Token
  Future<void> saveToken(String token) async {
    await _storage.write(key: AppConstants.tokenKey, value: token);
  }

  /// 获取 Token
  Future<String?> getToken() async {
    return await _storage.read(key: AppConstants.tokenKey);
  }

  /// 清除 Token
  Future<void> clearToken() async {
    await _storage.delete(key: AppConstants.tokenKey);
  }

  /// 保存 Refresh Token
  Future<void> saveRefreshToken(String token) async {
    await _storage.write(key: AppConstants.refreshTokenKey, value: token);
  }

  /// 获取 Refresh Token
  Future<String?> getRefreshToken() async {
    return await _storage.read(key: AppConstants.refreshTokenKey) ??
        await _storage.read(key: 'refresh_token');
  }

  /// 清除 Refresh Token
  Future<void> clearRefreshToken() async {
    await _storage.delete(key: AppConstants.refreshTokenKey);
    await _storage.delete(key: 'refresh_token');
  }

  /// 清空全部本地持久化状态：Access Token、Refresh Token 与用户信息。
  /// 用于会话彻底失效（无感刷新失败）或主动登出时，确保无残留凭据泄露。
  Future<void> clearAll() async {
    await _storage.delete(key: AppConstants.tokenKey);
    await _storage.delete(key: AppConstants.refreshTokenKey);
    await _storage.delete(key: AppConstants.userInfoKey);
    await _storage.delete(key: 'refresh_token'); // 兼容历史遗留键
    try {
      await _storage.deleteAll();
    } catch (_) {}
  }

  /// 写入通用键值
  Future<void> write(String key, String value) async {
    await _storage.write(key: key, value: value);
  }

  /// 读取通用键值
  Future<String?> read(String key) async {
    return await _storage.read(key: key);
  }

  /// 删除通用键值
  Future<void> delete(String key) async {
    await _storage.delete(key: key);
  }
}
