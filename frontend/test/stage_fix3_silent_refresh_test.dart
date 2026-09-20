import 'dart:convert';
import 'dart:typed_data';
import 'package:dio/dio.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:frontend/api/dio_client.dart';
import 'package:frontend/controllers/auth_controller.dart';
import 'package:frontend/routes/app_routes.dart';
import 'package:frontend/services/storage_service.dart';
import 'package:frontend/utils/constants.dart';
import 'package:get/get.dart' hide Response;

class MockHttpAdapter implements HttpClientAdapter {
  final Future<ResponseBody> Function(RequestOptions options) handler;
  MockHttpAdapter(this.handler);

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) {
    return handler(options);
  }

  @override
  void close({bool force = false}) {}
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late StorageService storage;
  late DioClient dioClient;
  late HttpClientAdapter defaultAdapter;

  setUp(() async {
    FlutterSecureStorage.setMockInitialValues({});
    Get.testMode = true;
    Get.reset();

    storage = StorageService();
    await storage.init();
    Get.put<StorageService>(storage);

    dioClient = DioClient();
    defaultAdapter = dioClient.dio.httpClientAdapter;
    dioClient.resetSessionState();
  });

  tearDown(() {
    dioClient.dio.httpClientAdapter = defaultAdapter;
    dioClient.resetSessionState();
    Get.reset();
  });

  group('Stage Fix-3: AppRoutes 与 Storage 清理机制验证', () {
    test('1. AppRoutes.LOGIN 常量兼容大写别名', () {
      expect(AppRoutes.LOGIN, equals(AppRoutes.login));
      expect(AppRoutes.LOGIN, equals('/login'));
      expect(AppRoutes.HOME, equals('/home'));
      expect(AppRoutes.REGISTER, equals('/register'));
      expect(AppRoutes.PROFILE, equals('/profile'));
      expect(AppRoutes.STUDENT_VERIFY, equals('/student-verify'));
    });

    test('2. StorageService.clearAll 彻底清除全部持久化凭据', () async {
      await storage.saveToken('test_access_token');
      await storage.saveRefreshToken('test_refresh_token');
      await storage.write(AppConstants.userInfoKey, '{"username":"test"}');

      expect(await storage.getToken(), equals('test_access_token'));
      expect(await storage.getRefreshToken(), equals('test_refresh_token'));
      expect(await storage.read(AppConstants.userInfoKey), isNotNull);

      // 执行 clearAll
      await storage.clearAll();

      expect(await storage.getToken(), isNull);
      expect(await storage.getRefreshToken(), isNull);
      expect(await storage.read(AppConstants.userInfoKey), isNull);
    });

    test('3. AuthController.logout 同步调用 storage.clearAll 并重置认证状态', () async {
      await storage.saveToken('logout_test_token');
      await storage.saveRefreshToken('logout_test_refresh');

      dioClient.dio.httpClientAdapter = MockHttpAdapter((options) async {
        return ResponseBody.fromString(
          jsonEncode({'code': 200, 'message': 'success'}),
          200,
          headers: {Headers.contentTypeHeader: [Headers.jsonContentType]},
        );
      });

      final authController = Get.put(AuthController());
      authController.token.value = 'logout_test_token';
      authController.isLoggedIn.value = true;

      // 执行登出
      await authController.logout();

      expect(authController.token.value, isEmpty);
      expect(authController.isLoggedIn.value, isFalse);
      expect(authController.currentUser.value, isNull);
      expect(await storage.getToken(), isNull);
      expect(await storage.getRefreshToken(), isNull);
    });
  });

  group('Stage Fix-3: DioClient 401 无感静默刷新与并发请求排队重放', () {
    test('4. 遭遇 401 自动使用 Refresh Token 刷新并重放原请求成功', () async {
      const expiredToken = 'expired_access_token_111';
      const validRefreshToken = 'valid_refresh_token_222';
      const refreshedAccessToken = 'refreshed_access_token_333';

      await storage.saveToken(expiredToken);
      await storage.saveRefreshToken(validRefreshToken);

      // 配置 Mock Refresh Dio
      int refreshCallCount = 0;
      final mockRefreshDio = Dio();
      mockRefreshDio.httpClientAdapter = MockHttpAdapter((options) async {
        if (options.path.contains('/auth/refresh')) {
          refreshCallCount++;
          final reqData = options.data is Map ? options.data as Map : jsonDecode(options.data.toString()) as Map;
          if (reqData['refreshToken'] == validRefreshToken) {
            return ResponseBody.fromString(
              jsonEncode({
                'code': 200,
                'message': 'success',
                'data': {
                  'accessToken': refreshedAccessToken,
                  'refreshToken': 'updated_refresh_token_444',
                }
              }),
              200,
              headers: {Headers.contentTypeHeader: [Headers.jsonContentType]},
            );
          }
        }
        return ResponseBody.fromString(
          jsonEncode({'code': 400, 'message': 'invalid refresh token'}),
          400,
          headers: {Headers.contentTypeHeader: [Headers.jsonContentType]},
        );
      });
      dioClient.customRefreshDio = mockRefreshDio;

      // 业务接口：初次携带 expiredToken 时返回 401，重试携带 refreshedAccessToken 时返回 200
      int businessCallCount = 0;
      String? lastAuthHeader;
      dioClient.dio.httpClientAdapter = MockHttpAdapter((options) async {
        businessCallCount++;
        lastAuthHeader = options.headers['Authorization'] as String?;

        if (lastAuthHeader == 'Bearer $expiredToken') {
          return ResponseBody.fromString(
            jsonEncode({'code': 401, 'message': 'Token expired'}),
            401,
            headers: {Headers.contentTypeHeader: [Headers.jsonContentType]},
          );
        }
        if (lastAuthHeader == 'Bearer $refreshedAccessToken') {
          return ResponseBody.fromString(
            jsonEncode({'code': 200, 'message': 'OK', 'data': {'result': 'success'}}),
            200,
            headers: {Headers.contentTypeHeader: [Headers.jsonContentType]},
          );
        }
        return ResponseBody.fromString(
          jsonEncode({'code': 400, 'message': 'bad request'}),
          400,
          headers: {Headers.contentTypeHeader: [Headers.jsonContentType]},
        );
      });

      final resp = await dioClient.dio.get('/goods/detail/100');
      expect(resp.statusCode, equals(200));
      expect(resp.data['code'], equals(200));
      expect(resp.data['data']['result'], equals('success'));

      // 验证各环节调用情况
      expect(refreshCallCount, equals(1), reason: '刷新接口应恰好被调用一次');
      expect(businessCallCount, equals(2), reason: '业务接口应经历初次 401 和一次携带新 Token 的重试');
      expect(lastAuthHeader, equals('Bearer $refreshedAccessToken'), reason: '最终重发请求必须携带新 Access Token');

      // 验证 Storage 中持久化数据已同步更新为最新 Token
      expect(await storage.getToken(), equals(refreshedAccessToken));
      expect(await storage.getRefreshToken(), equals('updated_refresh_token_444'));
    });

    test('5. 并发 401 场景：Completer 互斥锁确保只向服务端发起 1 次刷新请求', () async {
      const expiredToken = 'expired_concurrent_token';
      const validRefreshToken = 'valid_concurrent_refresh';
      const refreshedAccessToken = 'refreshed_concurrent_token';

      await storage.saveToken(expiredToken);
      await storage.saveRefreshToken(validRefreshToken);

      int refreshCallCount = 0;
      final mockRefreshDio = Dio();
      mockRefreshDio.httpClientAdapter = MockHttpAdapter((options) async {
        if (options.path.contains('/auth/refresh')) {
          refreshCallCount++;
          // 模拟异步网络延迟使并发请求同时排队
          await Future.delayed(const Duration(milliseconds: 30));
          return ResponseBody.fromString(
            jsonEncode({
              'code': 200,
              'message': 'success',
              'data': {
                'accessToken': refreshedAccessToken,
                'refreshToken': 'new_refresh_token',
              }
            }),
            200,
            headers: {Headers.contentTypeHeader: [Headers.jsonContentType]},
          );
        }
        return ResponseBody.fromString(
          jsonEncode({'code': 400, 'message': 'bad request'}),
          400,
          headers: {Headers.contentTypeHeader: [Headers.jsonContentType]},
        );
      });
      dioClient.customRefreshDio = mockRefreshDio;

      dioClient.dio.httpClientAdapter = MockHttpAdapter((options) async {
        final authHeader = options.headers['Authorization'] as String?;
        if (authHeader == 'Bearer $expiredToken') {
          return ResponseBody.fromString(
            jsonEncode({'code': 401, 'message': 'Token expired'}),
            401,
            headers: {Headers.contentTypeHeader: [Headers.jsonContentType]},
          );
        }
        if (authHeader == 'Bearer $refreshedAccessToken') {
          return ResponseBody.fromString(
            jsonEncode({'code': 200, 'path': options.path}),
            200,
            headers: {Headers.contentTypeHeader: [Headers.jsonContentType]},
          );
        }
        return ResponseBody.fromString(
          jsonEncode({'code': 400, 'message': 'bad request'}),
          400,
          headers: {Headers.contentTypeHeader: [Headers.jsonContentType]},
        );
      });

      // 同时发起 3 个受保护的并发网络请求
      final results = await Future.wait([
        dioClient.dio.get('/orders/my'),
        dioClient.dio.get('/favorite/list'),
        dioClient.dio.get('/history/list'),
      ]);

      // 3 个请求全部成功重试并获得 200 响应
      for (final resp in results) {
        expect(resp.statusCode, equals(200));
        expect(resp.data['code'], equals(200));
      }

      // 核心断言：刷新接口必须仅被调用 1 次！
      expect(refreshCallCount, equals(1), reason: '并发 401 请求必须通过 Completer 互斥复用单次刷新');
    });

    test('6. Refresh Token 也已失效时，彻底清理凭据并拒绝请求', () async {
      await storage.saveToken('expired_token');
      await storage.saveRefreshToken('invalid_or_expired_refresh_token');

      final mockRefreshDio = Dio();
      mockRefreshDio.httpClientAdapter = MockHttpAdapter((options) async {
        // 服务端判定 Refresh Token 过期，返回 401
        return ResponseBody.fromString(
          jsonEncode({'code': 401, 'message': 'Refresh token expired'}),
          401,
          headers: {Headers.contentTypeHeader: [Headers.jsonContentType]},
        );
      });
      dioClient.customRefreshDio = mockRefreshDio;

      dioClient.dio.httpClientAdapter = MockHttpAdapter((options) async {
        return ResponseBody.fromString(
          jsonEncode({'code': 401, 'message': 'Unauthorized'}),
          401,
          headers: {Headers.contentTypeHeader: [Headers.jsonContentType]},
        );
      });

      try {
        await dioClient.dio.get('/user/profile');
        fail('刷新失败应向调用方传递 DioException');
      } on DioException catch (e) {
        expect(e.response?.statusCode, equals(401));

        // 凭据必须彻底清空
        expect(await storage.getToken(), isNull);
        expect(await storage.getRefreshToken(), isNull);
      }
    });

    test('7. 登录、注册、刷新接口自身遭遇 401 时不触发递归刷新循环', () async {
      int refreshCallCount = 0;
      final mockRefreshDio = Dio();
      mockRefreshDio.httpClientAdapter = MockHttpAdapter((options) async {
        refreshCallCount++;
        return ResponseBody.fromString(
          jsonEncode({'code': 200, 'message': 'ok'}),
          200,
          headers: {Headers.contentTypeHeader: [Headers.jsonContentType]},
        );
      });
      dioClient.customRefreshDio = mockRefreshDio;

      dioClient.dio.httpClientAdapter = MockHttpAdapter((options) async {
        // 模拟密码错误返回 401
        return ResponseBody.fromString(
          jsonEncode({'code': 401, 'message': 'Bad credentials'}),
          401,
          headers: {Headers.contentTypeHeader: [Headers.jsonContentType]},
        );
      });

      try {
        await dioClient.dio.post('/auth/login', data: {'username': 'u', 'password': 'p'});
        fail('登录失败应直接抛出异常');
      } on DioException catch (e) {
        expect(e.response?.statusCode, equals(401));
        // /auth/login 不应触发 /auth/refresh
        expect(refreshCallCount, equals(0), reason: '认证端点自身 401 严禁触发静默刷新');
      }
    });
  });
}
