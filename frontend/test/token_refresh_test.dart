import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:frontend/services/storage_service.dart';
import 'package:frontend/utils/constants.dart';
import 'package:get/get.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('Stage 7-B: Refresh Token 本地持久化与常量验证', () {
    setUp(() {
      FlutterSecureStorage.setMockInitialValues({});
      Get.reset();
    });

    test('1. AppConstants 常量包含 refreshTokenKey', () {
      expect(AppConstants.refreshTokenKey, isNotEmpty);
      expect(AppConstants.refreshTokenKey, equals('campus_trade_refresh_token'));
    });

    test('2. StorageService 读写与清除 Refresh Token 验证', () async {
      final storage = StorageService();
      await storage.init();
      Get.put<StorageService>(storage);

      expect(await storage.getRefreshToken(), isNull);

      // 保存 Refresh Token
      const testRefreshToken = 'mock_refresh_token_xyz_123';
      await storage.saveRefreshToken(testRefreshToken);

      expect(await storage.getRefreshToken(), equals(testRefreshToken));

      // 清除 Refresh Token
      await storage.clearRefreshToken();
      expect(await storage.getRefreshToken(), isNull);
    });

    test('3. StorageService clearToken 与 clearRefreshToken 互相独立', () async {
      final storage = StorageService();
      await storage.init();
      Get.put<StorageService>(storage);

      await storage.saveToken('access_token_1');
      await storage.saveRefreshToken('refresh_token_1');

      expect(await storage.getToken(), equals('access_token_1'));
      expect(await storage.getRefreshToken(), equals('refresh_token_1'));

      await storage.clearToken();
      expect(await storage.getToken(), isNull);
      expect(await storage.getRefreshToken(), equals('refresh_token_1'));

      await storage.clearRefreshToken();
      expect(await storage.getRefreshToken(), isNull);
    });
  });
}
