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
import 'package:get/get.dart' hide Response;

/// 回归测试：已发布商品的二次编辑。
///
/// 历史问题：商品详情页的"管理商品"只弹一句"请在'我的商品'中进行编辑"，
/// 而"我的商品"页根本没有编辑入口，导致已发布商品无法二次编辑。
/// 现在由发布页承担编辑模式：带商品 ID 进入 → 回填表单 → 提交走 PUT /goods/{id}。
void main() {
  const goodsId = '2101229014818570241';

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
  });

  tearDown(() => Get.reset());

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
                  'id': '100',
                  'parentId': '0',
                  'name': '数码',
                  'sort': 1,
                  'children': [
                    {
                      'id': '101',
                      'parentId': '100',
                      'name': '手机',
                      'sort': 1,
                      'children': <dynamic>[],
                    }
                  ],
                }
              ],
            },
          ));
        }
        if (options.method == 'GET' && options.path == '/goods/$goodsId') {
          return handler.resolve(Response(
            requestOptions: options,
            statusCode: 200,
            data: {
              'code': 200,
              'message': 'ok',
              'data': {
                'id': goodsId,
                'sellerId': '1',
                'schoolId': '1',
                'schoolName': '广西民族师范学院',
                'categoryId': '101',
                'categoryName': '手机',
                'title': '苹果18',
                'description': '9成新，配件齐全',
                'price': 60.0,
                'originalPrice': 120.0,
                'conditionLevel': '95新',
                'status': 'ON_SALE',
                'location': '图书馆西门',
                'viewCount': 2,
                'images': <String>[],
                'tags': <String>[],
                'sellerUsername': 'seller_student',
                'sellerNickname': '卖家同学',
                'sellerCreditScore': 100,
                'favoriteCount': 0,
                'isFavorite': false,
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
    DioClient().dio.interceptors.insert(0, mock);
    addTearDown(() => DioClient().dio.interceptors.remove(mock));
  }

  testWidgets('带商品 ID 进入发布页 → 切换为编辑模式并回填原数据', (tester) async {
    installMock();

    await tester.pumpWidget(GetMaterialApp(
      initialRoute: AppRoutes.login,
      getPages: AppPages.routes,
    ));
    await tester.pumpAndSettle();

    // 注意：这里不能 await —— Get.toNamed 的 Future 要等路由被弹出才完成，测试中会一直挂住
    Get.toNamed(AppRoutes.goodsCreate, arguments: goodsId);
    await tester.pumpAndSettle(
      const Duration(milliseconds: 100),
      EnginePhase.sendSemanticsUpdate,
      const Duration(seconds: 5),
    );

    // 标题、按钮文案切到编辑态
    expect(find.text('编辑闲置商品'), findsOneWidget);
    expect(find.widgetWithText(ElevatedButton, '保存修改'), findsOneWidget);
    // 原有数据已回填
    expect(find.text('苹果18'), findsOneWidget);
    expect(find.text('9成新，配件齐全'), findsOneWidget);
    expect(find.text('60'), findsOneWidget);
    expect(find.text('图书馆西门'), findsOneWidget);
  });

  testWidgets('不带参数进入 → 仍是新建模式', (tester) async {
    installMock();

    await tester.pumpWidget(GetMaterialApp(
      initialRoute: AppRoutes.login,
      getPages: AppPages.routes,
    ));
    await tester.pumpAndSettle();

    Get.toNamed(AppRoutes.goodsCreate);
    await tester.pumpAndSettle(
      const Duration(milliseconds: 100),
      EnginePhase.sendSemanticsUpdate,
      const Duration(seconds: 5),
    );

    expect(find.text('发布闲置商品'), findsOneWidget);
    expect(find.widgetWithText(ElevatedButton, '确认发布商品'), findsOneWidget);
    expect(find.text('苹果18'), findsNothing);
  });
}
