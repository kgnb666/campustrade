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

/// 回归测试：发布商品的**反馈必须与真实结果一致**。
///
/// 背景（线上真实事故）：后端每次都发布成功（边缘日志 9 次 POST /api/goods 全 200，
/// 数据库里也真的多了 9 件商品），但页面弹的是"发布失败"，用户于是反复点，
/// 结果一件商品被发成了 9 件。
///
/// 根因：发布页把 `Get.back(result: true)` 写在了 try 里。当页面本身是**首个路由**
/// （用户直接打开 `/#/goods/create`，或在编辑/发布页刷新过浏览器）时，"返回上一页"
/// 这一步会失败并抛异常，于是紧跟着的 catch 把**已经成功**的发布报成失败。
void main() {
  late List<({String title, String message, Duration duration})> toasts;
  late SnackbarDispatcher originalDispatcher;
  late void Function() originalCloser;

  setUp(() async {
    Get.reset();
    FlutterSecureStorage.setMockInitialValues({});
    await Get.putAsync(() => StorageService().init());
    final auth = Get.put(AuthController());
    auth.isLoggedIn.value = true;
    auth.currentUser.value = UserProfileModel(
      id: '1',
      username: 'seller_student',
      nickname: '卖家同学',
      role: 'USER',
      status: 'ACTIVE',
      verifyStatus: 'SUCCESS',
      schoolName: '广西民族师范学院',
    );

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
    Get.reset();
  });

  void installMock() {
    final mock = InterceptorsWrapper(
      onRequest: (options, handler) {
        if (options.method == 'GET' && options.path.contains('/category')) {
          return handler.resolve(Response(
            requestOptions: options,
            statusCode: 200,
            data: {
              'code': 200,
              'message': 'ok',
              'data': [
                {
                  'id': '1',
                  'parentId': '0',
                  'name': '电子产品',
                  'sort': 1,
                  'children': [
                    {'id': '101', 'parentId': '1', 'name': '手机', 'sort': 1, 'children': <dynamic>[]}
                  ],
                }
              ],
            },
          ));
        }
        // 「我的发布」列表：成功发布后若没有上一页可返回，页面会跳到这里
        if (options.method == 'GET' && options.path.contains('/goods/my')) {
          return handler.resolve(Response(
            requestOptions: options,
            statusCode: 200,
            data: {
              'code': 200,
              'message': 'ok',
              // /goods/my 返回的是**数组**（不是分页对象），按真实形态给出
              'data': <dynamic>[],
            },
          ));
        }
        // 发布成功：注意 data 是**字符串**（后端 long → string），这也是真实形态
        return handler.resolve(Response(
          requestOptions: options,
          statusCode: 200,
          data: {'code': 200, 'message': '商品发布成功', 'data': '11', 'timestamp': '1790141000000'},
        ));
      },
    );
    DioClient().dio.interceptors.insert(0, mock);
    addTearDown(() => DioClient().dio.interceptors.remove(mock));
  }

  Future<void> fillMinimalForm(WidgetTester tester) async {
    await tester.enterText(find.byType(TextFormField).at(0), '手机');
    await tester.enterText(find.byType(TextFormField).at(1), '1000');
    await tester.pumpAndSettle();
  }

  Future<void> pumpPage(WidgetTester tester, {required bool asFirstRoute}) async {
    tester.view.physicalSize = const Size(1080, 2400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);

    await tester.pumpWidget(GetMaterialApp(
      initialRoute: asFirstRoute ? AppRoutes.goodsCreate : AppRoutes.login,
      getPages: AppPages.routes,
    ));
    await tester.pumpAndSettle();
    if (!asFirstRoute) {
      Get.toNamed(AppRoutes.goodsCreate);
      await tester.pumpAndSettle();
    }
  }

  testWidgets('正常进入（从上一页跳转）发布：给出"发布成功"', (tester) async {
    installMock();
    await pumpPage(tester, asFirstRoute: false);
    await fillMinimalForm(tester);

    await tester.tap(find.text('确认发布商品'));
    await tester.pumpAndSettle();

    expect(toasts.map((t) => t.title), contains('发布成功'));
    expect(toasts.map((t) => t.title), isNot(contains('发布失败')));
  });

  testWidgets('直接打开/刷新后发布（页面是首个路由）：仍必须报"发布成功"，不得误报失败', (tester) async {
    installMock();
    await pumpPage(tester, asFirstRoute: true);
    await fillMinimalForm(tester);

    await tester.tap(find.text('确认发布商品'));
    await tester.pumpAndSettle();

    expect(toasts.map((t) => t.title), contains('发布成功'),
        reason: '后端已经发布成功，页面必须如实告知');
    expect(toasts.map((t) => t.title), isNot(contains('发布失败')),
        reason: '"返回上一页"失败不应被当成发布失败——线上因此把一件商品发成了 9 件');
    // 没有上一页可返回时，页面必须跳到"我的发布"，否则页面毫无变化会被误认为失败
    expect(find.text('确认发布商品'), findsNothing,
        reason: '成功后必须离开发布页，让用户看到结果');
  });
}
