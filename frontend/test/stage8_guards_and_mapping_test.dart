import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:frontend/api/dio_client.dart';
import 'package:frontend/controllers/auth_controller.dart';
import 'package:frontend/models/user_model.dart';
import 'package:frontend/routes/app_pages.dart';
import 'package:frontend/routes/app_routes.dart';
import 'package:frontend/services/storage_service.dart';
import 'package:frontend/utils/api_error.dart';
import 'package:frontend/utils/app_logger.dart';
import 'package:frontend/utils/constants.dart';
import 'package:get/get.dart' hide Response;

/// Stage 8 其余前端加固项的回归测试：
/// - A3 `tryAutoLogin` 失败必须清空**全部**本地凭据（含 7 天有效的 refresh token）；
/// - A4 页面级提示统一走 safeSnackbar、裸 debugPrint 已收敛到日志层（源码级守卫）；
/// - B5 轻量日志层的可观测性契约（release 静默 + 可注入上报回调）；
/// - C8 后端新错误码（409 校园邮箱已被他人认证 / 429 配额）的用户可见文案。
void main() {
  Interceptor? mockInterceptor;

  void installMockApi(Response Function(RequestOptions options) responder) {
    if (mockInterceptor != null) {
      DioClient().dio.interceptors.remove(mockInterceptor);
    }
    mockInterceptor = InterceptorsWrapper(
      onRequest: (options, handler) {
        try {
          return handler.resolve(responder(options));
        } on DioException catch (dioErr) {
          return handler.reject(dioErr);
        } catch (e) {
          return handler.reject(DioException(
            requestOptions: options,
            error: e,
            type: DioExceptionType.unknown,
          ));
        }
      },
    );
    DioClient().dio.interceptors.insert(0, mockInterceptor!);
  }

  Response jsonOk(RequestOptions options, Object? data) => Response(
        requestOptions: options,
        statusCode: 200,
        data: {'code': 200, 'message': 'success', 'data': data},
      );

  DioException offline(RequestOptions options) => DioException(
        requestOptions: options,
        type: DioExceptionType.connectionError,
        message: 'connection refused',
      );

  DioException businessError(
    RequestOptions options,
    int status,
    String? message,
  ) =>
      DioException(
        requestOptions: options,
        type: DioExceptionType.badResponse,
        response: Response(
          requestOptions: options,
          statusCode: status,
          data: message == null
              ? null
              : {
                  'code': status,
                  'message': message,
                  'data': null,
                  'timestamp': 1758100000000,
                },
        ),
      );

  setUp(() {
    Get.reset();
  });

  tearDown(() {
    if (mockInterceptor != null) {
      DioClient().dio.interceptors.remove(mockInterceptor);
      mockInterceptor = null;
    }
    Get.reset();
  });

  // ==========================================================================
  // A3：tryAutoLogin 失败后的凭据清理
  // ==========================================================================

  group('Stage 8-A3: tryAutoLogin 失败', () {
    test('1. 自动登录失败必须清空 access token 与 refresh token（不接受"只清 access"）',
        () async {
      FlutterSecureStorage.setMockInitialValues({});
      final StorageService storage =
          await Get.putAsync(() => StorageService().init());

      await storage.saveToken('access-token-stale');
      await storage.saveRefreshToken('refresh-token-still-valid-7d');
      await storage.write(AppConstants.userInfoKey, '{"id":"1001"}');

      // 携带旧 token 的 /user/profile 失败（服务端认为会话已失效）
      installMockApi((options) => throw offline(options));

      // 直接构造以避免 onInit 自动触发一次 tryAutoLogin，让断言只针对本次调用
      final AuthController auth = AuthController();
      await auth.tryAutoLogin();

      expect(auth.isLoggedIn.value, isFalse);
      expect(auth.token.value, isEmpty);
      expect(await storage.getToken(), isNull,
          reason: '失效的 access token 必须清掉');
      expect(
        await storage.getRefreshToken(),
        isNull,
        reason: 'refresh token 必须一起清掉：残留它会让下一次 401 用旧凭据静默重登回来',
      );
      expect(await storage.read(AppConstants.userInfoKey), isNull);
    });
  });

  // ==========================================================================
  // A4：页面级提示与裸日志的源码级守卫
  // ==========================================================================

  group('Stage 8-A4: 页面级提示与日志落点', () {
    List<String> dartFilesUnder(List<String> dirs) {
      final files = <String>[];
      for (final dir in dirs) {
        final Directory d = Directory(dir);
        if (!d.existsSync()) continue;
        for (final entity in d.listSync(recursive: true)) {
          if (entity is File && entity.path.endsWith('.dart')) {
            files.add(entity.path.replaceAll('\\', '/'));
          }
        }
      }
      return files;
    }

    test('2. lib/pages 与 lib/widgets 不再直接调用 Get.snackbar（统一走 safeSnackbar）',
        () {
      final offenders = <String>[];
      for (final path in dartFilesUnder(['lib/pages', 'lib/widgets'])) {
        if (File(path).readAsStringSync().contains('Get.snackbar')) {
          offenders.add(path);
        }
      }
      expect(offenders, isEmpty,
          reason: '页面级提示必须统一经 safeSnackbar（无 Overlay 时不会抛异常，且带挂载保护）');
    });

    test('3. 裸 debugPrint 已收敛到日志层（仅 app_logger 自身实现使用）', () {
      final files = dartFilesUnder(['lib']);
      final owners = files
          .where((p) => File(p).readAsStringSync().contains('debugPrint'))
          .toList();
      expect(owners, equals(['lib/utils/app_logger.dart']),
          reason: '业务代码一律走 AppLogger，便于统一注入上报与按级别过滤');
    });
  });

  // ==========================================================================
  // B5：轻量日志层
  // ==========================================================================

  group('Stage 8-B5: AppLogger', () {
    late List<String> lines;
    late DebugPrintCallback savedDebugPrint;

    setUp(() {
      savedDebugPrint = debugPrint;
      lines = <String>[];
      debugPrint = (String? message, {int? wrapWidth}) {
        if (message != null) lines.add(message);
      };
      AppLogger.consoleEnabled = true;
      AppLogger.onErrorReport = null;
    });

    tearDown(() {
      debugPrint = savedDebugPrint;
      AppLogger.consoleEnabled = kDebugMode;
      AppLogger.onErrorReport = null;
    });

    test('4. debug/info/warn/error 都会在 debug 构建输出到控制台', () {
      AppLogger.debug('req trace');
      AppLogger.info('degraded');
      AppLogger.warn('slow');
      AppLogger.error('boom');

      expect(lines.length, 4);
      expect(lines[0], contains('[DEBUG] req trace'));
      expect(lines[1], contains('[INFO] degraded'));
      expect(lines[2], contains('[WARN] slow'));
      expect(lines[3], contains('[ERROR] boom'));
    });

    test('5. release 形态（consoleEnabled=false）下完全静默，但 error 仍走上报回调', () {
      AppLogger.consoleEnabled = false;

      final reported = <String>[];
      AppLogger.onErrorReport = (message, {error, stackTrace}) {
        reported.add('$message|$error');
      };

      AppLogger.debug('nobody sees this');
      AppLogger.info('nor this');
      AppLogger.warn('nor that');
      AppLogger.error('login failed', error: 'bad credentials');

      expect(lines, isEmpty, reason: 'release 构建默认静默：既不落盘也不打印');
      expect(reported, ['login failed|bad credentials'],
          reason: 'error 是 release 下唯一可上报的通道');
    });

    test('6. 上报回调自身抛异常不会反噬业务', () {
      AppLogger.onErrorReport = (message, {error, stackTrace}) {
        throw StateError('reporting backend down');
      };

      // 不应抛出：业务侧的凭据清理 / 错误态已经写完，日志通道不能反过来打断它们
      expect(() => AppLogger.error('order action failed'), returnsNormally);
    });
  });

  // ==========================================================================
  // C8：后端新错误码（409 / 429）的用户可见文案
  // ==========================================================================

  group('Stage 8-C8: 409/429 错误映射', () {
    test('7. 服务端 message 原样作为提示（校园邮箱已被他人认证）', () {
      final e = businessError(
        RequestOptions(path: '/student/verify/code'),
        409,
        '该校园邮箱已被其他用户认证',
      );
      expect(describeApiError(e, fallback: '核验失败'), '该校园邮箱已被其他用户认证');
    });

    test('8. 服务端漏发 message 时 409/429 给出可读兜底而不是泛化文案', () {
      final conflict = businessError(
        RequestOptions(path: '/student/verify/code'),
        409,
        null,
      );
      expect(describeApiError(conflict, fallback: '核验失败'),
          '核验失败（与当前状态冲突，可能已被他人占用，请刷新后重试）');
      expect(describeApiError(conflict, fallback: '核验失败'), isNot(contains('服务端异常')));

      final quota = businessError(
        RequestOptions(path: '/ai/goods/description'),
        429,
        null,
      );
      expect(describeApiError(quota, fallback: 'AI 生成失败'),
          'AI 生成失败（操作过于频繁，请稍后再试）');
    });

    test('9. 其他状态码保持既有"（HTTP xxx）"形态，不引入新分支', () {
      final e = businessError(RequestOptions(path: '/goods/1/status'), 500, null);
      expect(describeApiError(e, fallback: '商品状态修改失败'),
          '商品状态修改失败（HTTP 500）');
    });

    testWidgets('10. 校园邮箱被他人认证（409）时，认证页给出明确提示',
        (WidgetTester tester) async {
      tester.view.physicalSize = const Size(1080, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);

      FlutterSecureStorage.setMockInitialValues({});
      await Get.putAsync(() => StorageService().init());
      Get.put(AuthController());

      installMockApi((options) {
        if (options.path.contains('/school/list')) {
          return jsonOk(options, [
            {
              'id': '1',
              'schoolName': '清华大学',
              'schoolCode': '10001',
              'emailSuffix': '@mails.tsinghua.edu.cn',
            }
          ]);
        }
        if (options.path.contains('/student/verify/code')) {
          throw businessError(options, 409, '该校园邮箱已被其他用户认证');
        }
        return jsonOk(options, null);
      });

      await tester.pumpWidget(GetMaterialApp(
        initialRoute: AppRoutes.studentVerify,
        getPages: AppPages.routes,
      ));
      await tester.pumpAndSettle();

      // 选择高校（选中后邮箱会被自动填充为 school.emailSuffix 对应的地址）
      await tester.tap(find.byType(DropdownButtonFormField<SchoolModel>).first);
      await tester.pumpAndSettle();
      await tester.tap(find.text('清华大学 (10001)').last);
      await tester.pumpAndSettle();

      // 学号 / 邮箱 / 验证码三个输入框按页面顺序排列
      await tester.enterText(find.byType(TextFormField).at(0), '2024010203');
      await tester.enterText(find.byType(TextFormField).at(2), '123456');
      await tester.pumpAndSettle();

      await tester.tap(find.text('完成认证'));
      await tester.pumpAndSettle();

      expect(find.text('该校园邮箱已被其他用户认证'), findsOneWidget,
          reason: '409 的业务提示必须原样展示给用户');

      // safeSnackbar 的默认存活时长是 3 秒。此前它把 duration 透传为 null，
      // 而 GetX 在 duration == null 时**根本不创建自动关闭定时器**，提示会永久留在屏幕上
      // （用户实测："登录成功"一直挂在底部）。这里把时钟推过 3 秒，
      // 既避免用例结束时"仍有 pending timer"而失败，也顺带守住"提示一定会自动消失"。
      await tester.pump(const Duration(seconds: 4));
      await tester.pumpAndSettle();
      expect(find.text('该校园邮箱已被其他用户认证'), findsNothing,
          reason: '提示必须在默认时长（3 秒）后自动关闭，不允许永久停留');
    });
  });
}
