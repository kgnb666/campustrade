import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:frontend/api/dio_client.dart';
import 'package:frontend/controllers/auth_controller.dart';
import 'package:frontend/main.dart';
import 'package:frontend/models/user_model.dart';
import 'package:frontend/pages/auth/login_page.dart';
import 'package:frontend/pages/auth/register_page.dart';
import 'package:frontend/pages/profile/profile_page.dart';
import 'package:frontend/pages/profile/student_verify_page.dart';
import 'package:frontend/routes/app_pages.dart';
import 'package:frontend/routes/app_routes.dart';
import 'package:frontend/services/storage_service.dart';
import 'package:get/get.dart' hide Response;

void main() {
  Interceptor? mockInterceptor;

  /// 安装一个统一的接口桩：拦截所有请求并直接返回给定的 [Response]。
  /// 阶段 6 起"请求失败"与"确实没有数据"在页面上必须区分，因此空态测试
  /// 必须显式给出"接口成功但列表为空"的响应，而不是依赖测试环境默认的 400。
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

  Response emptyPage(RequestOptions options) {
    return Response(
      requestOptions: options,
      statusCode: 200,
      data: {
        'code': 200,
        'message': 'success',
        'data': {
          'records': <dynamic>[],
          'total': 0,
          'size': 10,
          'current': 1,
          'pages': 1,
        },
      },
    );
  }

  setUp(() async {
    Get.reset();
    FlutterSecureStorage.setMockInitialValues({});
    await Get.putAsync(() => StorageService().init());
    Get.put(AuthController());
  });

  tearDown(() {
    if (mockInterceptor != null) {
      DioClient().dio.interceptors.remove(mockInterceptor);
      mockInterceptor = null;
    }
    Get.reset();
  });

  testWidgets('1. 验证首页渲染与 Stage 1 标志', (WidgetTester tester) async {
    await tester.pumpWidget(const CampusTradeApp());
    await tester.pumpAndSettle();

    expect(find.text('CampusTrade · 校园二手交易平台'), findsOneWidget);
    expect(find.text('Stage 1：用户中心与校园认证就绪'), findsOneWidget);
    expect(find.text('用户登录'), findsOneWidget);
    expect(find.text('新用户注册'), findsOneWidget);
  });

  testWidgets('2. 验证登录页面渲染与表单组件', (WidgetTester tester) async {
    await tester.pumpWidget(
      GetMaterialApp(
        initialRoute: AppRoutes.login,
        getPages: AppPages.routes,
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byType(LoginPage), findsOneWidget);
    expect(find.widgetWithText(TextFormField, '用户名'), findsOneWidget);
    expect(find.widgetWithText(TextFormField, '密码'), findsOneWidget);
    expect(find.widgetWithText(ElevatedButton, '立即登录'), findsOneWidget);
  });

  testWidgets('3. 验证注册页面渲染与表单组件', (WidgetTester tester) async {
    await tester.pumpWidget(
      GetMaterialApp(
        initialRoute: AppRoutes.register,
        getPages: AppPages.routes,
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byType(RegisterPage), findsOneWidget);
    expect(find.widgetWithText(TextFormField, '用户名'), findsOneWidget);
    expect(find.widgetWithText(TextFormField, '联系邮箱'), findsOneWidget);
    expect(find.widgetWithText(TextFormField, '设置密码'), findsOneWidget);
    expect(find.widgetWithText(TextFormField, '确认密码'), findsOneWidget);
    expect(find.widgetWithText(ElevatedButton, '同意协议并注册'), findsOneWidget);
  });

  testWidgets('4. 验证个人中心未登录与已登录状态渲染', (WidgetTester tester) async {
    final authController = Get.find<AuthController>();

    await tester.pumpWidget(
      GetMaterialApp(
        initialRoute: AppRoutes.profile,
        getPages: AppPages.routes,
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byType(ProfilePage), findsOneWidget);
    // 未登录状态
    expect(find.text('您尚未登录，请先登录账号'), findsOneWidget);

    // 模拟登录状态
    authController.isLoggedIn.value = true;
    authController.currentUser.value = UserProfileModel(
      username: 'test_student',
      nickname: '测试小明',
      role: 'USER',
      status: 'ACTIVE',
      verifyStatus: 'SUCCESS',
      schoolName: '清华大学',
      studentNumber: '2026010203',
      credit: UserCreditModel(
        creditScore: 100,
        tradeCount: 0,
        goodReviewCount: 0,
        badReviewCount: 0,
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('测试小明'), findsOneWidget);
    expect(find.text('已认证'), findsOneWidget);
    expect(find.text('认证高校: 清华大学'), findsOneWidget);
    expect(find.text('信用分: 100'), findsOneWidget);
    expect(find.text('退出登录'), findsOneWidget);
  });

  testWidgets('5. 验证校园认证页面组件', (WidgetTester tester) async {
    await tester.pumpWidget(
      GetMaterialApp(
        initialRoute: AppRoutes.studentVerify,
        getPages: AppPages.routes,
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byType(StudentVerifyPage), findsOneWidget);
    expect(find.text('高校学生实名认证'), findsOneWidget);
    expect(find.widgetWithText(TextFormField, '在读学号'), findsOneWidget);
    expect(find.widgetWithText(OutlinedButton, '获取邮箱验证码'), findsOneWidget);
    expect(find.widgetWithText(ElevatedButton, '完成认证'), findsOneWidget);
  });

  testWidgets('6. 验证商品列表页面渲染与分类搜索组件', (WidgetTester tester) async {
    await tester.pumpWidget(
      GetMaterialApp(
        initialRoute: AppRoutes.goodsList,
        getPages: AppPages.routes,
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('校园集市'), findsOneWidget);
    expect(find.text('搜索商品、教材、数码电子...'), findsOneWidget);
    expect(find.text('全部'), findsOneWidget);
    expect(find.text('发布闲置'), findsOneWidget);
  });

  testWidgets('7. 验证发布商品页面表单组件渲染', (WidgetTester tester) async {
    final authController = Get.find<AuthController>();
    authController.isLoggedIn.value = true;
    authController.currentUser.value = UserProfileModel(
      username: 'test_student',
      role: 'USER',
      status: 'ACTIVE',
      verifyStatus: 'SUCCESS',
      schoolName: '清华大学',
    );

    await tester.pumpWidget(
      GetMaterialApp(
        initialRoute: AppRoutes.goodsCreate,
        getPages: AppPages.routes,
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('发布闲置商品'), findsOneWidget);
    expect(find.text('商品图片 (最多9张)'), findsOneWidget);
    expect(find.text('添加图片'), findsOneWidget);
    expect(find.widgetWithText(TextFormField, '商品标题 *'), findsOneWidget);
    expect(find.widgetWithText(TextFormField, '出售价格 (¥) *'), findsOneWidget);
    expect(find.text('全新'), findsOneWidget);
    expect(find.text('95新'), findsOneWidget);
    expect(find.widgetWithText(ElevatedButton, '确认发布商品'), findsOneWidget);
  });

  testWidgets('8. 验证我的发布商品页面渲染', (WidgetTester tester) async {
    await tester.pumpWidget(
      GetMaterialApp(
        initialRoute: AppRoutes.goodsMy,
        getPages: AppPages.routes,
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('我的发布'), findsOneWidget);
    expect(find.text('新增发布'), findsOneWidget);
  });

  testWidgets('9. 验证发布页面 AI 助手三大功能入口与横幅', (WidgetTester tester) async {
    final authController = Get.find<AuthController>();
    authController.isLoggedIn.value = true;
    authController.currentUser.value = UserProfileModel(
      username: 'test_student',
      role: 'USER',
      status: 'ACTIVE',
      verifyStatus: 'SUCCESS',
      schoolName: '清华大学',
    );

    await tester.pumpWidget(
      GetMaterialApp(
        initialRoute: AppRoutes.goodsCreate,
        getPages: AppPages.routes,
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('已接入 DeepSeek AI 助手：支持帮写文案、智能分类与合理估价'), findsOneWidget);
    expect(find.text('AI 推荐分类'), findsOneWidget);
    expect(find.text('AI 智能估价'), findsOneWidget);
    expect(find.text('AI 帮写描述'), findsOneWidget);
  });

  testWidgets('10. 验证我的收藏页面渲染与空状态', (WidgetTester tester) async {
    // 真正的空态：接口成功返回空列表（而非请求失败）
    installMockApi(emptyPage);

    await tester.pumpWidget(
      GetMaterialApp(
        initialRoute: AppRoutes.favorite,
        getPages: AppPages.routes,
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('我的收藏'), findsOneWidget);
    expect(find.text('还没有收藏任何闲置商品'), findsOneWidget);
    expect(find.text('去首页逛逛'), findsOneWidget);
  });

  testWidgets('11. 验证浏览足迹页面渲染与空状态', (WidgetTester tester) async {
    // 真正的空态：接口成功返回空列表（而非请求失败）
    installMockApi(emptyPage);

    await tester.pumpWidget(
      GetMaterialApp(
        initialRoute: AppRoutes.history,
        getPages: AppPages.routes,
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('浏览足迹'), findsOneWidget);
    expect(find.text('最近还没有浏览过闲置商品'), findsOneWidget);
    expect(find.text('去首页逛逛'), findsOneWidget);
  });

  testWidgets('12. 验证个人中心包含我的收藏与浏览足迹入口', (WidgetTester tester) async {
    final authController = Get.find<AuthController>();
    authController.isLoggedIn.value = true;
    authController.currentUser.value = UserProfileModel(
      username: 'test_student',
      nickname: '测试小明',
      role: 'USER',
      status: 'ACTIVE',
      verifyStatus: 'SUCCESS',
      schoolName: '清华大学',
    );

    await tester.pumpWidget(
      GetMaterialApp(
        initialRoute: AppRoutes.profile,
        getPages: AppPages.routes,
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('我的收藏'), findsOneWidget);
    expect(find.text('浏览足迹'), findsOneWidget);
  });
}
