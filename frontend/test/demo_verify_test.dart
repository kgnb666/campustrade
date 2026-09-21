import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:frontend/api/dio_client.dart';
import 'package:frontend/controllers/auth_controller.dart';
import 'package:frontend/models/user_model.dart';
import 'package:frontend/routes/app_pages.dart';
import 'package:frontend/routes/app_routes.dart';
import 'package:frontend/services/storage_service.dart';
import 'package:frontend/utils/ui_feedback.dart';
import 'package:get/get.dart' hide Response;

/// 校园认证"演示模式"的前端回归测试。
///
/// 背景：个人开发者没有学校邮箱，无法演示"申请验证码 → 输码 → 认证成功"这条链路。
/// 服务端在**白名单邮箱**上新增了演示通道：不发真实邮件，验证码随响应返回。
/// 前端必须做到两件事，本文件就是它们的守门人：
///   1. 演示模式下自动填入验证码，并显著标注"演示模式"（否则演示者会一直去翻邮箱）；
///   2. 正常模式下**一个字节都不能变**——不回传验证码、不自动填入、不出现"演示模式"字样。
///
/// 提示（snackbar）通过 [snackbarDispatcher] 接缝替换为记录器：GetX 的 snackbar 自带
/// 动画与关闭定时器，在 widget 测试里会以"仍有 pending timer / Overlay 带着活跃 Ticker 被销毁"
/// 收场；本文件要断言的是页面行为与提示内容，不是提示的渲染效果。
void main() {
  Interceptor? mockInterceptor;
  late List<({String title, String message, Duration duration})> toasts;
  late SnackbarDispatcher originalDispatcher;
  late void Function() originalCloser;

  Response jsonOk(RequestOptions options, Object? data) => Response(
        requestOptions: options,
        statusCode: 200,
        data: {'code': 200, 'message': '验证码已发送至校园邮箱（5分钟内有效）', 'data': data},
      );

  /// 让 /student/verify 返回给定的 data（null = 正常通道，Map = 演示通道）
  void installMockApi(Object? verifyData) {
    if (mockInterceptor != null) {
      DioClient().dio.interceptors.remove(mockInterceptor);
    }
    mockInterceptor = InterceptorsWrapper(
      onRequest: (options, handler) {
        if (options.path.contains('/school/list')) {
          return handler.resolve(jsonOk(options, [
            {
              'id': '1',
              'schoolName': '清华大学',
              'schoolCode': '10001',
              'emailSuffix': '@mails.tsinghua.edu.cn',
            }
          ]));
        }
        return handler.resolve(jsonOk(options, verifyData));
      },
    );
    DioClient().dio.interceptors.insert(0, mockInterceptor!);
  }

  setUp(() async {
    Get.reset();
    FlutterSecureStorage.setMockInitialValues({});
    await Get.putAsync(() => StorageService().init());
    Get.put(AuthController());

    toasts = [];
    originalDispatcher = snackbarDispatcher;
    originalCloser = snackbarCloser;
    snackbarDispatcher = (title, message, position, background, foreground, duration) {
      toasts.add((title: title, message: message, duration: duration));
    };
    snackbarCloser = () {};
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

  /// 打开认证页、选学校、填学号，停在"获取邮箱验证码"之前
  Future<void> openVerifyForm(WidgetTester tester) async {
    tester.view.physicalSize = const Size(1080, 2400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);

    await tester.pumpWidget(GetMaterialApp(
      initialRoute: AppRoutes.studentVerify,
      getPages: AppPages.routes,
    ));
    await tester.pumpAndSettle();

    await tester.tap(find.byType(DropdownButtonFormField<SchoolModel>).first);
    await tester.pumpAndSettle();
    await tester.tap(find.text('清华大学 (10001)').last);
    await tester.pumpAndSettle();

    // 学号（索引 0）、校园邮箱（索引 1，选中学校后已自动填入占位地址）、验证码（索引 2）
    await tester.enterText(find.byType(TextFormField).at(0), '2024010203');
    await tester.pumpAndSettle();
  }

  String codeFieldText(WidgetTester tester) =>
      tester.widget<TextFormField>(find.byType(TextFormField).at(2)).controller?.text ?? '';

  /// 卸载页面：页面 dispose 会取消 60 秒重发倒计时，否则这个 Timer 会残留到下一个用例
  Future<void> closePage(WidgetTester tester) async {
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump();
  }

  testWidgets('演示模式：验证码自动填入并显著标注"演示模式"', (tester) async {
    installMockApi({'demoMode': true, 'demoCode': '135790'});
    await openVerifyForm(tester);

    await tester.tap(find.text('获取邮箱验证码'));
    await tester.pumpAndSettle();

    // 1. 页面上的常驻提示条（比一闪而过的 snackbar 更可靠）：必须说清"没有发真实邮件"
    expect(find.text('演示模式'), findsOneWidget,
        reason: '演示模式必须被显著标注，否则演示者会一直去翻邮箱');
    expect(find.textContaining('未发送真实邮件'), findsOneWidget);
    expect(find.textContaining('135790'), findsWidgets,
        reason: '提示条里要直接写出验证码，演示现场无需再去别处找');

    // 2. 验证码自动填入，演示者点一下"完成认证"就能走完
    expect(codeFieldText(tester), '135790', reason: '演示模式下应自动填入服务端带回的验证码');
    expect(Get.find<AuthController>().isVerifyDemoMode, isTrue);

    // 3. 同时给一条 snackbar 提示（时长足够看清，且仍会自动消失——见 toast_dismiss_test）
    final demoToast = toasts.firstWhere((t) => t.title == '演示模式');
    expect(demoToast.message, contains('135790'));
    expect(demoToast.duration, const Duration(seconds: 6));

    await closePage(tester);
  });

  testWidgets('正常通道：不回传验证码、不自动填入、不出现"演示模式"', (tester) async {
    // data 为 null —— 这正是线上接口的既有契约（验证码只能从校园邮箱获取）
    installMockApi(null);
    await openVerifyForm(tester);

    await tester.tap(find.text('获取邮箱验证码'));
    await tester.pumpAndSettle();

    expect(Get.find<AuthController>().verifyDemoCode.value, '',
        reason: '正常通道下控制器不得保留任何验证码');
    expect(codeFieldText(tester), '', reason: '正常通道必须由用户从邮箱取码后手动输入');
    expect(find.text('演示模式'), findsNothing, reason: '正常通道不得出现演示模式字样');
    expect(find.textContaining('未发送真实邮件'), findsNothing);
    expect(find.textContaining('验证码已发送至'), findsOneWidget,
        reason: '正常通道仍应提示验证码已发送到校园邮箱');
    expect(toasts.map((t) => t.title), contains('验证码已发送'));
    expect(toasts.any((t) => t.title == '演示模式'), isFalse,
        reason: '正常通道不得出现演示模式提示');

    await closePage(tester);
  });
}
