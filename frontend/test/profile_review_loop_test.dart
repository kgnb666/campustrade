import 'package:dio/dio.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:frontend/api/dio_client.dart';
import 'package:frontend/controllers/auth_controller.dart';
import 'package:frontend/pages/profile/profile_page.dart';
import 'package:frontend/models/user_model.dart';
import 'package:frontend/services/storage_service.dart';
import 'package:get/get.dart' hide Response;

/// 回归测试：个人中心对「确实没有评价」的用户会陷入无限请求 + 无限重建（页面持续闪烁）。
///
/// 原实现以「列表为空且不在加载中」作为触发条件，而拉取完成后空列表依然为空，
/// 于是每帧都会再次触发 post-frame 拉取：请求不断、loading 反复 true/false、页面一直在闪。
/// 修复后按用户记录「是否已发起过加载」，每个用户只自动拉取一次。
void main() {
  Interceptor? mockInterceptor;
  int userReviewRequests = 0;

  void installMockApi() {
    if (mockInterceptor != null) {
      DioClient().dio.interceptors.remove(mockInterceptor);
    }
    mockInterceptor = InterceptorsWrapper(
      onRequest: (options, handler) {
        if (options.path.contains('/reviews/user/')) {
          userReviewRequests++;
          return handler.resolve(Response(
            requestOptions: options,
            statusCode: 200,
            data: {
              'code': 200,
              'message': 'ok',
              // 该用户没有任何评价 —— 正是触发原 bug 的场景
              'data': {
                'records': <dynamic>[],
                'total': 0,
                'current': 1,
                'size': 10,
                'pages': 0,
              },
            },
          ));
        }
        return handler.resolve(Response(
          requestOptions: options,
          statusCode: 200,
          data: {'code': 200, 'message': 'ok', 'data': null},
        ));
      },
    );
    DioClient().dio.interceptors.insert(0, mockInterceptor!);
  }

  setUp(() async {
    Get.reset();
    userReviewRequests = 0;
    FlutterSecureStorage.setMockInitialValues({});
    await Get.putAsync(() => StorageService().init());

    final auth = Get.put(AuthController());
    auth.isLoggedIn.value = true;
    auth.currentUser.value = UserProfileModel(
      id: '1001',
      username: 'no_review_user',
      nickname: '没有评价的同学',
      email: 'no_review@campus.edu',
      role: 'USER',
      status: 'ACTIVE',
      verifyStatus: 'NONE',
      credit: UserCreditModel(
        creditScore: 100,
        tradeCount: 0,
        goodReviewCount: 0,
        badReviewCount: 0,
      ),
    );

    installMockApi();
  });

  tearDown(() {
    if (mockInterceptor != null) {
      DioClient().dio.interceptors.remove(mockInterceptor);
      mockInterceptor = null;
    }
    Get.reset();
  });

  testWidgets('评价为空时只自动拉取一次，且帧循环能停下来', (tester) async {
    await tester.pumpWidget(const GetMaterialApp(home: ProfilePage()));

    // 修复前：请求 → 空列表 → 再次请求 … 帧永远不停止，pumpAndSettle 会超时失败
    await tester.pumpAndSettle(const Duration(milliseconds: 50));

    expect(userReviewRequests, 1, reason: '评价为空时不应反复触发拉取');
    expect(find.text('收到的评价'), findsOneWidget);

    // 再多泵几帧，确认没有任何后续请求
    await tester.pump(const Duration(milliseconds: 500));
    await tester.pump(const Duration(milliseconds: 500));
    expect(userReviewRequests, 1, reason: '多次重建后仍只应请求一次');
  });
}
