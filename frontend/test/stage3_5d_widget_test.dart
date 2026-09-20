import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:frontend/api/dio_client.dart';
import 'package:frontend/controllers/auth_controller.dart';
import 'package:frontend/controllers/goods_controller.dart';
import 'package:frontend/models/user_model.dart';
import 'package:frontend/pages/goods/goods_detail_page.dart';
import 'package:frontend/routes/app_pages.dart';
import 'package:frontend/routes/app_routes.dart';
import 'package:frontend/services/storage_service.dart';
import 'package:get/get.dart' hide Response;

void main() {
  Interceptor? mockInterceptor;

  setUp(() async {
    Get.reset();
    FlutterSecureStorage.setMockInitialValues({});
    await Get.putAsync(() => StorageService().init());
    final auth = Get.put(AuthController());
    auth.isLoggedIn.value = true;
    auth.currentUser.value = UserProfileModel(
      id: '1001',
      username: 'test_student',
      nickname: '测试同学',
      role: 'USER',
      status: 'ACTIVE',
      verifyStatus: 'SUCCESS',
      schoolName: '清华大学',
      studentNumber: '2026001',
    );

    if (mockInterceptor != null) {
      DioClient().dio.interceptors.remove(mockInterceptor);
      mockInterceptor = null;
    }
  });

  tearDown(() {
    if (mockInterceptor != null) {
      DioClient().dio.interceptors.remove(mockInterceptor);
      mockInterceptor = null;
    }
    Get.reset();
  });

  void installMockApi(Response Function(RequestOptions options) responder) {
    mockInterceptor = InterceptorsWrapper(
      onRequest: (options, handler) {
        try {
          final resp = responder(options);
          return handler.resolve(resp);
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

  // =========================================================================
  // 一、AI 二次确认机制与表单保护测试
  // =========================================================================

  testWidgets('1. AI 帮写描述 - 未采纳不会修改表单，采纳后才更新描述', (WidgetTester tester) async {
    tester.view.physicalSize = const Size(1080, 2400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);

    installMockApi((options) {
      if (options.path.contains('/category/list')) {
        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {
            'code': 200,
            'message': 'success',
            'data': [
              {'id': 101, 'name': '数码科技', 'parentId': null, 'sort': 1, 'status': 1, 'children': []}
            ],
          },
        );
      }
      if (options.path.contains('/ai/goods/description')) {
        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {
            'code': 200,
            'message': 'success',
            'data': {
              'generatedDescription': '【AI精选】2025最新考研英语真题解析，字迹清晰工整。',
              'tags': ['考研必备', '九五新', '附赠笔记'],
              'degraded': false,
              'costMs': 120,
            },
          },
        );
      }
      return Response(requestOptions: options, statusCode: 404);
    });

    await tester.pumpWidget(
      GetMaterialApp(
        initialRoute: AppRoutes.goodsCreate,
        getPages: AppPages.routes,
      ),
    );
    await tester.pumpAndSettle();

    // 1. 用户手动输入标题与原始手写描述
    final titleFinder = find.widgetWithText(TextFormField, '商品标题 *');
    final descFinder = find.widgetWithText(TextFormField, '规格、购买时间、转让原因等');
    await tester.enterText(titleFinder, '考研英语红宝书');
    await tester.enterText(descFinder, '原用户手写的草稿描述，请勿覆盖');
    await tester.pumpAndSettle();

    // 2. 点击【AI 帮写描述】
    final aiDescBtn = find.text('AI 帮写描述');
    await tester.ensureVisible(aiDescBtn);
    await tester.tap(aiDescBtn);
    await tester.pumpAndSettle();

    // 3. 验证 AI 弹窗正常渲染出 AI 结果与卖点标签
    expect(find.text('DeepSeek AI 帮写描述'), findsOneWidget);
    expect(find.text('考研必备'), findsOneWidget);
    expect(find.text('【AI精选】2025最新考研英语真题解析，字迹清晰工整。'), findsOneWidget);

    // 4. 用户点击【放弃】，弹窗关闭
    final abandonBtn = find.widgetWithText(OutlinedButton, '放弃');
    expect(abandonBtn, findsOneWidget);
    await tester.tap(abandonBtn);
    await tester.pumpAndSettle();

    // 5. 核心断言：未采纳时，原描述必须保持不变！
    expect(find.text('原用户手写的草稿描述，请勿覆盖'), findsOneWidget);
    expect(find.text('【AI精选】2025最新考研英语真题解析，字迹清晰工整。'), findsNothing);

    // 6. 再次点击【AI 帮写描述】并点击【采纳并填入】
    await tester.tap(aiDescBtn);
    await tester.pumpAndSettle();
    final adoptBtn = find.widgetWithText(ElevatedButton, '采纳并填入');
    expect(adoptBtn, findsOneWidget);
    await tester.tap(adoptBtn);
    await tester.pumpAndSettle();

    // 7. 核心断言：采纳后才将 AI 结果写入表单描述
    expect(find.text('【AI精选】2025最新考研英语真题解析，字迹清晰工整。'), findsOneWidget);
    expect(find.text('原用户手写的草稿描述，请勿覆盖'), findsNothing);

    // 清理 snackbar 延迟定时器与退出动画
    await tester.pump(const Duration(seconds: 5));
    await tester.pumpAndSettle();
  });

  testWidgets('2. AI 智能分类 - 未采纳分类不改变 Dropdown，采纳后才改变分类', (WidgetTester tester) async {
    tester.view.physicalSize = const Size(1080, 2400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);

    installMockApi((options) {
      if (options.path.contains('/category/list')) {
        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {
            'code': 200,
            'message': 'success',
            'data': [
              {'id': 1, 'name': '图书教材', 'parentId': null, 'sort': 1, 'status': 1, 'children': []},
              {'id': 101, 'name': '数码科技', 'parentId': null, 'sort': 2, 'status': 1, 'children': []}
            ],
          },
        );
      }
      if (options.path.contains('/ai/goods/category')) {
        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {
            'code': 200,
            'message': 'success',
            'data': {
              'categoryId': 101,
              'categoryName': '数码科技',
              'reason': '标题中含有 iPhone，归类为数码电子科技',
              'degraded': false,
              'costMs': 80,
            },
          },
        );
      }
      return Response(requestOptions: options, statusCode: 404);
    });

    await tester.pumpWidget(
      GetMaterialApp(
        initialRoute: AppRoutes.goodsCreate,
        getPages: AppPages.routes,
      ),
    );
    await tester.pumpAndSettle();

    // 初始分类为图书教材 (列表中第一个)
    expect(find.text('图书教材'), findsOneWidget);

    // 输入手机标题
    final titleFinder = find.widgetWithText(TextFormField, '商品标题 *');
    await tester.enterText(titleFinder, 'iPhone 15 Pro 256G 原色');
    await tester.pumpAndSettle();

    // 点击【AI 推荐分类】
    final aiCatBtn = find.text('AI 推荐分类');
    await tester.ensureVisible(aiCatBtn);
    await tester.tap(aiCatBtn);
    await tester.pumpAndSettle();

    // 弹窗展示推荐分类为数码科技与推荐理由
    expect(find.text('DeepSeek AI 智能分类'), findsOneWidget);
    expect(find.textContaining('标题中含有 iPhone'), findsOneWidget);

    // 用户点击【放弃】
    await tester.tap(find.widgetWithText(OutlinedButton, '放弃'));
    await tester.pumpAndSettle();

    // 分类依然是原分类 图书教材，未被改变
    expect(find.text('图书教材'), findsOneWidget);

    // 再次打开并点击【采纳分类】
    await tester.tap(aiCatBtn);
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(ElevatedButton, '采纳分类'));
    await tester.pumpAndSettle();

    // 分类已成功采纳为 数码科技
    expect(find.text('数码科技'), findsOneWidget);

    await tester.pump(const Duration(seconds: 5));
    await tester.pumpAndSettle();
  });

  testWidgets('3. AI 智能估价 - 未采纳保持原价格，采纳后才写入建议价格', (WidgetTester tester) async {
    tester.view.physicalSize = const Size(1080, 2400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);

    installMockApi((options) {
      if (options.path.contains('/category/list')) {
        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {
            'code': 200,
            'message': 'success',
            'data': [
              {'id': 101, 'name': '数码科技', 'parentId': null, 'sort': 1, 'status': 1, 'children': []}
            ],
          },
        );
      }
      if (options.path.contains('/ai/goods/price')) {
        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {
            'code': 200,
            'message': 'success',
            'data': {
              'suggestedPrice': 888.00,
              'minPrice': 750.00,
              'maxPrice': 980.00,
              'reason': '根据95新成色与近期校园二手 iPad 行情综合评估',
              'degraded': false,
              'costMs': 95,
            },
          },
        );
      }
      return Response(requestOptions: options, statusCode: 404);
    });

    await tester.pumpWidget(
      GetMaterialApp(
        initialRoute: AppRoutes.goodsCreate,
        getPages: AppPages.routes,
      ),
    );
    await tester.pumpAndSettle();

    // 1. 输入标题与原价格
    await tester.enterText(find.widgetWithText(TextFormField, '商品标题 *'), 'iPad 9 64G');
    final priceFinder = find.widgetWithText(TextFormField, '出售价格 (¥) *');
    await tester.enterText(priceFinder, '600.00');
    await tester.pumpAndSettle();

    // 2. 点击【AI 智能估价】
    final aiPriceBtn = find.text('AI 智能估价');
    await tester.ensureVisible(aiPriceBtn);
    await tester.tap(aiPriceBtn);
    await tester.pumpAndSettle();

    // 3. 验证 AI 估价弹窗结果展示
    expect(find.text('DeepSeek AI 价格建议'), findsOneWidget);
    expect(find.text('¥888.00'), findsOneWidget);
    expect(find.textContaining('区间: ¥750 - ¥980'), findsOneWidget);

    // 4. 用户点击【放弃】
    await tester.tap(find.widgetWithText(OutlinedButton, '放弃'));
    await tester.pumpAndSettle();

    // 5. 核心断言：价格输入框严格保持用户原值 600.00
    expect(find.text('600.00'), findsOneWidget);
    expect(find.text('888.00'), findsNothing);

    // 6. 再次点击并点击【采纳建议价】
    await tester.tap(aiPriceBtn);
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(ElevatedButton, '采纳建议价'));
    await tester.pumpAndSettle();

    // 7. 核心断言：采纳后才更新为 888.00
    expect(find.text('888.00'), findsOneWidget);

    await tester.pump(const Duration(seconds: 5));
    await tester.pumpAndSettle();
  });

  testWidgets('4. 表单保护 - AI 操作失败或网络超时，原表单各项数据绝对不丢失', (WidgetTester tester) async {
    tester.view.physicalSize = const Size(1080, 2400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);

    installMockApi((options) {
      if (options.path.contains('/category/list')) {
        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {
            'code': 200,
            'message': 'success',
            'data': [
              {'id': 101, 'name': '数码科技', 'parentId': null, 'sort': 1, 'status': 1, 'children': []}
            ],
          },
        );
      }
      // AI 接口模拟超时异常
      if (options.path.contains('/ai/goods/')) {
        throw DioException(
          requestOptions: options,
          type: DioExceptionType.connectionTimeout,
          message: 'Connection timeout',
        );
      }
      return Response(requestOptions: options, statusCode: 404);
    });

    await tester.pumpWidget(
      GetMaterialApp(
        initialRoute: AppRoutes.goodsCreate,
        getPages: AppPages.routes,
      ),
    );
    await tester.pumpAndSettle();

    // 用户填写完整表单
    await tester.enterText(find.widgetWithText(TextFormField, '商品标题 *'), '微积分第七版教材');
    await tester.enterText(find.widgetWithText(TextFormField, '出售价格 (¥) *'), '25.50');
    await tester.enterText(find.widgetWithText(TextFormField, '入手原价 (¥ 选填)'), '68.00');
    await tester.enterText(find.widgetWithText(TextFormField, '校园面交地点'), '学二食堂门口');
    await tester.enterText(find.widgetWithText(TextFormField, '规格、购买时间、转让原因等'), '九成新无笔记');
    await tester.pumpAndSettle();

    // 触发 AI 操作失败
    final aiDescBtn = find.text('AI 帮写描述');
    await tester.ensureVisible(aiDescBtn);
    await tester.tap(aiDescBtn);
    await tester.pumpAndSettle();

    // 弹窗展示错误与重试按钮
    expect(find.textContaining('生成失败'), findsOneWidget);
    expect(find.text('重试'), findsOneWidget);

    // 关闭弹窗
    await tester.tap(find.byIcon(Icons.close));
    await tester.pumpAndSettle();

    // 核心断言：表单所有字段完好无损，绝对不丢失任何数据！
    expect(find.text('微积分第七版教材'), findsOneWidget);
    expect(find.text('25.50'), findsOneWidget);
    expect(find.text('68.00'), findsOneWidget);
    expect(find.text('学二食堂门口'), findsOneWidget);
    expect(find.text('九成新无笔记'), findsOneWidget);
  });

  testWidgets('5. AI 规则兜底 (Fallback) 标识与内容正常展示', (WidgetTester tester) async {
    tester.view.physicalSize = const Size(1080, 2400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);

    installMockApi((options) {
      if (options.path.contains('/category/list')) {
        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {
            'code': 200,
            'message': 'success',
            'data': [
              {'id': 101, 'name': '数码科技', 'parentId': null, 'sort': 1, 'status': 1, 'children': []}
            ],
          },
        );
      }
      if (options.path.contains('/ai/goods/description')) {
        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {
            'code': 200,
            'message': 'success',
            'data': {
              'generatedDescription': '二手商品成色良好，欢迎自提。',
              'tags': ['个人闲置', '自提优先'],
              'degraded': true, // 触发规则兜底
              'costMs': 3,
            },
          },
        );
      }
      return Response(requestOptions: options, statusCode: 404);
    });

    await tester.pumpWidget(
      GetMaterialApp(
        initialRoute: AppRoutes.goodsCreate,
        getPages: AppPages.routes,
      ),
    );
    await tester.pumpAndSettle();

    await tester.enterText(find.widgetWithText(TextFormField, '商品标题 *'), '高数教材');
    await tester.pumpAndSettle();

    final aiDescBtn = find.text('AI 帮写描述');
    await tester.ensureVisible(aiDescBtn);
    await tester.tap(aiDescBtn);
    await tester.pumpAndSettle();

    // 核心断言：识别到 degraded=true，展示规则兜底提示横幅
    expect(find.text('规则兜底生成：请核对内容'), findsOneWidget);
    expect(find.text('二手商品成色良好，欢迎自提。'), findsOneWidget);

    await tester.tap(find.byIcon(Icons.close));
    await tester.pumpAndSettle();
  });

  // =========================================================================
  // 二、Favorite 状态切换与失败回滚测试
  // =========================================================================

  testWidgets('6. Favorite UI - 收藏与取消收藏状态切换 (乐观更新成功)', (WidgetTester tester) async {
    tester.view.physicalSize = const Size(1080, 2400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);

    int favCount = 5;
    bool isFav = false;

    installMockApi((options) {
      if (options.path.contains('/goods/99')) {
        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {
            'code': 200,
            'message': 'success',
            'data': {
              'id': 99,
              'sellerId': 2002,
              'schoolId': 1,
              'schoolName': '清华大学',
              'categoryId': 101,
              'categoryName': '数码科技',
              'title': 'MacBook Pro M2 16G',
              'description': '自用电脑成色充新',
              'price': 8500.00,
              'conditionLevel': '95新',
              'status': 'ON_SALE',
              'location': '清华学堂',
              'viewCount': 42,
              'favoriteCount': favCount,
              'isFavorite': isFav,
              'sellerUsername': 'mac_seller',
              'sellerNickname': '麦克同学',
              'sellerVerified': true,
              'sellerCreditScore': 100,
              'images': [],
              'tags': [],
            },
          },
        );
      }
      if (options.method == 'POST' && options.path.contains('/favorite/99')) {
        isFav = true;
        favCount++;
        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {'code': 200, 'message': '收藏成功', 'data': null},
        );
      }
      if (options.method == 'DELETE' && options.path.contains('/favorite/99')) {
        isFav = false;
        favCount--;
        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {'code': 200, 'message': '取消收藏成功', 'data': null},
        );
      }
      return Response(requestOptions: options, statusCode: 404);
    });

    await tester.pumpWidget(
      GetMaterialApp(
        initialRoute: '/init',
        getPages: [
          GetPage(name: '/init', page: () => const Scaffold(body: Text('Init'))),
          GetPage(
            name: AppRoutes.goodsDetail,
            page: () => const GoodsDetailPage(),
          ),
        ],
      ),
    );
    Get.toNamed(AppRoutes.goodsDetail, arguments: 99);
    await tester.pumpAndSettle();

    // 初始状态：未收藏，展示【收藏】按钮，收藏数 5
    expect(find.text('收藏'), findsOneWidget);
    expect(find.textContaining('5 人收藏'), findsOneWidget);

    // 1. 点击底部【收藏】按钮
    await tester.tap(find.text('收藏'));
    await tester.pumpAndSettle();

    // 验证变为已收藏：按钮文字变为【已收藏】，出现 Icons.favorite，收藏数变为 6
    expect(find.text('已收藏'), findsOneWidget);
    expect(find.byIcon(Icons.favorite), findsWidgets);
    expect(find.textContaining('6 人收藏'), findsOneWidget);

    // 2. 再次点击【已收藏】取消收藏
    await tester.tap(find.text('已收藏'));
    await tester.pumpAndSettle();

    // 验证恢复为未收藏：按钮文字恢复为【收藏】，收藏数恢复为 5
    expect(find.text('收藏'), findsOneWidget);
    expect(find.textContaining('5 人收藏'), findsOneWidget);

    await tester.pump(const Duration(seconds: 5));
    await tester.pumpAndSettle();
  });

  testWidgets('7. Favorite UI - 乐观更新失败时能够精准回滚状态与计数', (WidgetTester tester) async {
    tester.view.physicalSize = const Size(1080, 2400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);

    installMockApi((options) {
      if (options.path.contains('/goods/100')) {
        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {
            'code': 200,
            'message': 'success',
            'data': {
              'id': 100,
              'sellerId': 2002,
              'schoolId': 1,
              'schoolName': '清华大学',
              'categoryId': 101,
              'categoryName': '代步工具',
              'title': '单车 捷安特',
              'price': 300.00,
              'conditionLevel': '8成新',
              'status': 'ON_SALE',
              'viewCount': 10,
              'favoriteCount': 2,
              'isFavorite': false,
              'sellerUsername': 'bike_seller',
              'sellerNickname': '单车同学',
              'sellerVerified': true,
              'images': [],
              'tags': [],
            },
          },
        );
      }
      // 收藏接口网络请求失败
      if (options.path.contains('/favorite/100')) {
        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {'code': 400, 'message': '商品不存在或网络异常', 'data': null},
        );
      }
      return Response(requestOptions: options, statusCode: 404);
    });

    await tester.pumpWidget(
      GetMaterialApp(
        initialRoute: '/init',
        getPages: [
          GetPage(name: '/init', page: () => const Scaffold(body: Text('Init'))),
          GetPage(
            name: AppRoutes.goodsDetail,
            page: () => const GoodsDetailPage(),
          ),
        ],
      ),
    );
    Get.toNamed(AppRoutes.goodsDetail, arguments: 100);
    await tester.pumpAndSettle();

    expect(find.text('收藏'), findsOneWidget);
    expect(find.textContaining('2 人收藏'), findsOneWidget);

    // 点击收藏触发乐观更新并发生失败
    await tester.tap(find.text('收藏'));
    await tester.pumpAndSettle();

    // 核心断言：回滚至未收藏状态【收藏】，计数准确回滚为 2！
    expect(find.text('收藏'), findsOneWidget);
    expect(find.textContaining('2 人收藏'), findsOneWidget);

    await tester.pump(const Duration(seconds: 5));
    await tester.pumpAndSettle();
  });

  // =========================================================================
  // 三、Hot Search 容错与交互测试
  // =========================================================================

  testWidgets('8. Hot Search - 热搜接口失败不影响商品列表正常渲染', (WidgetTester tester) async {
    tester.view.physicalSize = const Size(1080, 2400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);

    installMockApi((options) {
      if (options.path.contains('/category/list')) {
        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {'code': 200, 'data': []},
        );
      }
      // 热搜接口直接报错
      if (options.path.endsWith('/goods/search/hot')) {
        throw DioException(
          requestOptions: options,
          type: DioExceptionType.badResponse,
          response: Response(requestOptions: options, statusCode: 500),
        );
      }
      if (options.path.endsWith('/goods/search/history')) {
        return Response(requestOptions: options, statusCode: 200, data: {'code': 200, 'data': []});
      }
      // 商品列表正常返回
      if (options.path.contains('/goods/list')) {
        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {
            'code': 200,
            'message': 'success',
            'data': {
              'records': [
                {
                  'id': 101,
                  'sellerId': 2001,
                  'schoolId': 1,
                  'categoryId': 101,
                  'title': '校园二手电单车',
                  'price': 699.00,
                  'conditionLevel': '9成新',
                  'status': 'ON_SALE',
                  'schoolName': '清华大学',
                  'categoryName': '代步工具',
                  'viewCount': 10,
                }
              ],
              'total': 1,
              'size': 10,
              'current': 1,
              'pages': 1,
            },
          },
        );
      }
      return Response(requestOptions: options, statusCode: 200, data: {'code': 200, 'data': []});
    });

    await tester.pumpWidget(
      GetMaterialApp(
        initialRoute: AppRoutes.goodsList,
        getPages: AppPages.routes,
      ),
    );
    await tester.pumpAndSettle();

    // 核心断言：尽管热搜接口挂掉，商品列表依然正常展示！
    expect(find.text('校园二手电单车'), findsOneWidget);
    expect(find.text('699.00'), findsOneWidget);
  });

  testWidgets('9. Hot Search - 点击热搜关键词自动填入搜索框并触发搜索', (WidgetTester tester) async {
    tester.view.physicalSize = const Size(1080, 2400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);

    installMockApi((options) {
      if (options.path.contains('/category/list')) {
        return Response(requestOptions: options, statusCode: 200, data: {'code': 200, 'data': []});
      }
      if (options.path.endsWith('/goods/search/hot')) {
        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {
            'code': 200,
            'message': 'success',
            'data': ['iPad', '山地车', '高数教材'],
          },
        );
      }
      if (options.path.endsWith('/goods/search/history')) {
        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {
            'code': 200,
            'message': 'success',
            'data': <String>[],
          },
        );
      }
      if (options.path.contains('/goods/list') || options.path.contains('/goods/search')) {
        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {
            'code': 200,
            'data': {
              'records': [],
              'total': 0,
              'size': 10,
              'current': 1,
              'pages': 0,
            },
          },
        );
      }
      return Response(requestOptions: options, statusCode: 200, data: {'code': 200, 'data': []});
    });

    await tester.pumpWidget(
      GetMaterialApp(
        initialRoute: AppRoutes.goodsList,
        getPages: AppPages.routes,
      ),
    );
    await tester.pumpAndSettle();

    // 验证热搜栏渲染
    expect(find.text('热搜'), findsOneWidget);
    expect(find.text('iPad'), findsOneWidget);

    // 点击热搜词 iPad
    await tester.tap(find.text('iPad'));
    await tester.pumpAndSettle();

    // 核心断言：搜索框中自动填入了 'iPad'
    final goodsCtrl = Get.find<GoodsController>();
    expect(goodsCtrl.searchKeyword.value, 'iPad');
  });

  // =========================================================================
  // 四、History / Favorite 页面状态与下架标识测试
  // =========================================================================

  testWidgets('10. FavoritePage - 收藏列表与已下架状态徽标展示', (WidgetTester tester) async {
    tester.view.physicalSize = const Size(1080, 2400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);

    installMockApi((options) {
      if (options.path.contains('/favorite/list')) {
        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {
            'code': 200,
            'message': 'success',
            'data': {
              'records': [
                {
                  'id': 1,
                  'goodsId': 201,
                  'title': '正在出售的 Kindle',
                  'price': 350.00,
                  'conditionLevel': '95新',
                  'status': 'ON_SALE',
                  'schoolName': '清华大学',
                  'createdTime': '2026-09-17 12:00:00',
                },
                {
                  'id': 2,
                  'goodsId': 202,
                  'title': '已经被下架的耳机',
                  'price': 120.00,
                  'conditionLevel': '8成新',
                  'status': 'OFF_SHELF',
                  'schoolName': '北京大学',
                  'createdTime': '2026-09-16 10:00:00',
                }
              ],
              'total': 2,
              'size': 10,
              'current': 1,
              'pages': 1,
            },
          },
        );
      }
      return Response(requestOptions: options, statusCode: 200, data: {'code': 200, 'data': []});
    });

    await tester.pumpWidget(
      GetMaterialApp(
        initialRoute: AppRoutes.favorite,
        getPages: AppPages.routes,
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('正在出售的 Kindle'), findsOneWidget);
    expect(find.text('已经被下架的耳机'), findsOneWidget);
    // 核心断言：已下架的商品必须正确渲染出【已下架】徽标
    expect(find.text('已下架'), findsOneWidget);
  });

  testWidgets('11. HistoryPage - 浏览足迹列表与浏览时间格式化展示', (WidgetTester tester) async {
    tester.view.physicalSize = const Size(1080, 2400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);

    installMockApi((options) {
      if (options.path.contains('/history/list')) {
        return Response(
          requestOptions: options,
          statusCode: 200,
          data: {
            'code': 200,
            'message': 'success',
            'data': {
              'records': [
                {
                  'id': 1,
                  'goodsId': 301,
                  'title': '考研政治核心考案',
                  'price': 18.00,
                  'conditionLevel': '全新',
                  'status': 'ON_SALE',
                  'browseTime': '2026-09-17 15:30:00',
                }
              ],
              'total': 1,
              'size': 20,
              'current': 1,
              'pages': 1,
            },
          },
        );
      }
      return Response(requestOptions: options, statusCode: 200, data: {'code': 200, 'data': []});
    });

    await tester.pumpWidget(
      GetMaterialApp(
        initialRoute: AppRoutes.history,
        getPages: AppPages.routes,
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('考研政治核心考案'), findsOneWidget);
    expect(find.text('18.00'), findsOneWidget);
    expect(find.text('09-17 15:30'), findsOneWidget);
  });
}
