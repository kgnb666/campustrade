import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:frontend/api/dio_client.dart';
import 'package:frontend/controllers/auth_controller.dart';
import 'package:frontend/pages/auth/login_page.dart';
import 'package:frontend/routes/app_routes.dart';
import 'package:frontend/services/storage_service.dart';
import 'package:frontend/utils/ui_feedback.dart';
import 'package:get/get.dart' hide Response;

/// 回归测试：提示必须自动消失，且**不能在跳转后永久残留**。
///
/// 实测缺陷：登录成功提示是在 `Get.offAllNamed(home)` 之前弹出的，
/// 整套路由被替换时该提示的自动关闭定时器随旧路由一起销毁，
/// 于是提示永久留在 overlay 上（用户已经逛到商品详情页，底部还显示"登录成功"）。
/// 修复方式是"先跳转、再提示"（[safeSnackbarAfterNavigation]）。
///
/// 断言方式：GetX 的 snackbar 依赖真实 overlay + 动画，与 widget 测试的 fake clock
/// 互不兼容（`pumpAndSettle` 永不静止，或触发框架层 AnimationController 断言）。
/// 因此这里替换 [snackbarDispatcher] / [snackbarCloser] 两个接缝，
/// 对"提示的时机（跳转前还是跳转后）、默认时长、以及跳转前是否先清场"做确定性断言。
/// 用页面桩替代真实路由表：本用例只验证"提示的时机与时长"，
/// 真实首页会在 initState 里发起"待办汇总/最新商品"两个请求，留下未完成的 dio 定时器
/// （widget 测试以"仍有 pending timer"判失败），与断言目标无关。
final List<GetPage> testRoutes = [
  GetPage(name: AppRoutes.login, page: () => const LoginPage()),
  GetPage(name: AppRoutes.home, page: () => const Scaffold(body: Text('HOME-STUB'))),
];

void main() {
  late List<({String title, String message, Duration duration, String route})> dispatched;
  late List<String> closedAtRoute;
  late SnackbarDispatcher originalDispatcher;
  late void Function() originalCloser;
  Interceptor? mockInterceptor;

  void installMockApi(Response Function(RequestOptions options) responder) {
    if (mockInterceptor != null) {
      DioClient().dio.interceptors.remove(mockInterceptor);
    }
    mockInterceptor = InterceptorsWrapper(
      onRequest: (options, handler) => handler.resolve(responder(options)),
    );
    DioClient().dio.interceptors.insert(0, mockInterceptor!);
  }

  setUp(() async {
    Get.reset();
    FlutterSecureStorage.setMockInitialValues({});
    await Get.putAsync(() => StorageService().init());
    Get.put(AuthController());

    dispatched = [];
    closedAtRoute = [];
    originalDispatcher = snackbarDispatcher;
    originalCloser = snackbarCloser;
    snackbarDispatcher = (title, message, position, background, foreground, duration) {
      // 记录"弹提示那一刻所处路由"，这是判断先跳转还是先提示的唯一可靠依据
      dispatched.add((
        title: title,
        message: message,
        duration: duration,
        route: Get.currentRoute,
      ));
    };
    snackbarCloser = () => closedAtRoute.add(Get.currentRoute);
  });

  tearDown(() {
    snackbarDispatcher = originalDispatcher;
    snackbarCloser = originalCloser;
    if (mockInterceptor != null) {
      DioClient().dio.interceptors.remove(mockInterceptor);
      mockInterceptor = null;
    }
    Get.reset();
  });

  Response okLogin(RequestOptions options) => Response(        requestOptions: options,
        statusCode: 200,
        data: {
          'code': 200,
          'message': '登录成功',
          'data': {
            'accessToken': 'access-token-for-test',
            'refreshToken': 'refresh-token-for-test',
            'userInfo': {
              'id': '1',
              'username': 'toast_user',
              'nickname': '提示测试',
              'role': 'USER',
              'status': 'ACTIVE',
              'verifyStatus': 'SUCCESS',
            },
          },
        },
      );

  testWidgets('登录成功提示在跳转之后才弹出，且默认 3 秒后自动消失', (tester) async {
    installMockApi(okLogin);

    await tester.pumpWidget(GetMaterialApp(
      initialRoute: AppRoutes.login,
      getPages: testRoutes,
    ));
    await tester.pumpAndSettle();
    expect(Get.currentRoute, AppRoutes.login);

    final auth = Get.find<AuthController>();
    // 注意：不能 `await auth.login(...)`——请求回包依赖 fake clock 推进，
    // 直接 await 会与测试时钟互锁。先发起、再推进帧。
    final pending = auth.login('toast_user', 'campus123456');
    // 第一帧让登录流程跑完并触发跳转，后续帧让 addPostFrameCallback 执行
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));
    await tester.pump();

    expect(await pending, isTrue, reason: '登录本身应当成功');
    expect(Get.currentRoute, AppRoutes.home, reason: '登录成功后应跳转到首页');

    final loginToasts = dispatched.where((e) => e.title == '登录成功').toList();
    expect(loginToasts, hasLength(1),
        reason: '登录成功应给出且只给出一条提示，实际：${dispatched.map((e) => e.title).toList()}');
    // 核心回归断言：提示的关闭定时器属于新路由，因此弹提示时必须已经在首页。
    // 若仍在登录页（旧实现），定时器会随路由替换一起销毁 → 提示永久残留。
    expect(loginToasts.single.route, AppRoutes.home,
        reason: '提示必须在跳转到首页之后弹出，否则其自动关闭定时器会随旧路由一起销毁（回归点）');
    expect(loginToasts.single.duration, const Duration(seconds: 3),
        reason: '登录成功提示应停留 3 秒后自动消失');

    expect(closedAtRoute, isNotEmpty, reason: '跳转前应先清掉可能残留的旧提示');
    expect(closedAtRoute.first, AppRoutes.login,
        reason: '清场发生在跳转之前，因此那一刻仍在登录页');
  });

  testWidgets('safeSnackbar 默认停留 3 秒，显式时长优先', (tester) async {
    await tester.pumpWidget(GetMaterialApp(
      initialRoute: AppRoutes.login,
      getPages: testRoutes,
    ));
    await tester.pumpAndSettle();

    safeSnackbar('提示', '这是一条普通提示');
    safeSnackbar('绑定失败', '这是一条需要更长阅读时间的错误提示',
        duration: const Duration(seconds: 6), snackPosition: SnackPosition.TOP);

    expect(dispatched.map((e) => e.duration).toList(),
        [const Duration(seconds: 3), const Duration(seconds: 6)],
        reason: 'safeSnackbar 必须给出明确的默认时长（3 秒），不允许无限期停留；显式时长应生效');
  });

  testWidgets('没有 Overlay 时安全降级：不派发提示也不抛异常', (tester) async {
    // 未 pumpWidget，此时不存在 Overlay（对应"页面已销毁 / 单测直接驱动控制器"的场景）
    expect(() => safeSnackbar('提示', '无 Overlay 时不应崩溃'), returnsNormally);
    expect(dispatched, isEmpty,
        reason: '没有 Overlay 时不应尝试弹提示（GetX 内部会对 overlayContext 做空断言）');
  });

  testWidgets('safeSnackbarAfterNavigation 先清场、再跳转、最后提示', (tester) async {
    await tester.pumpWidget(GetMaterialApp(
      initialRoute: AppRoutes.login,
      getPages: testRoutes,
    ));
    await tester.pumpAndSettle();

    safeSnackbarAfterNavigation(
      () => safeOffAllNamed(AppRoutes.home),
      '已安全退出',
      '您已安全退出当前账号',
    );
    // 提示排在本帧之后，多推一帧让它执行
    await tester.pump();
    await tester.pump();

    expect(closedAtRoute, [AppRoutes.login],
        reason: '第一步必须是"清掉残留提示"，此时尚未跳转');
    expect(dispatched, hasLength(1));
    expect(dispatched.single.title, '已安全退出');
    expect(dispatched.single.route, AppRoutes.home, reason: '提示必须在跳转完成后才弹出');
    expect(dispatched.single.duration, const Duration(seconds: 3));
  });
}
