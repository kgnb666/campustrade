import 'dart:io';

import 'package:dio/dio.dart';
import 'package:dio/dio.dart' as dio show FormData;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:frontend/api/dio_client.dart';
import 'package:frontend/controllers/auth_controller.dart';
import 'package:frontend/models/user_model.dart';
import 'package:frontend/pages/goods/create_goods_page.dart';
import 'package:frontend/pages/goods/goods_list_page.dart';
import 'package:frontend/routes/app_pages.dart';
import 'package:frontend/routes/app_routes.dart';
import 'package:frontend/services/storage_service.dart';
import 'package:frontend/widgets/goods_thumbnail.dart';
import 'package:frontend/widgets/home_search_field.dart';
import 'package:get/get.dart' hide Response;

/// Stage 8-C6：此前没有 widget 测试的页面/组件的覆盖补齐。
///
/// 统一采用项目既有 mock 方式（`InterceptorsWrapper` 注入 fixture，见
/// `test/stage6_error_state_widget_test.dart`），逐页覆盖：
/// 列表渲染与空态、加载失败错误态与重试、以及关键交互。
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

  Response pageOf(RequestOptions options, List<Map<String, dynamic>> records,
          {int pages = 1}) =>
      jsonOk(options, {
        'records': records,
        'total': records.length,
        'size': 10,
        'current': 1,
        'pages': pages,
      });

  DioException offline(RequestOptions options) => DioException(
        requestOptions: options,
        type: DioExceptionType.connectionError,
        message: 'connection refused',
      );

  Map<String, dynamic> goods({
    Object id = 501,
    String title = '校园二手电单车',
    double price = 699.00,
    String status = 'ON_SALE',
  }) =>
      {
        'id': id,
        'sellerId': 2002,
        'schoolId': 1,
        'schoolName': '清华大学',
        'categoryId': 1,
        'categoryName': '教材书籍',
        'title': title,
        'price': price,
        'conditionLevel': '9成新',
        'status': status,
        'viewCount': 12,
      };

  /// 把当前 snackbar "走完"（越过展示时长 + 关闭动画）。
  ///
  /// 必要性：GetX 的 snackbar 是**全局单条队列**（`SnackbarController._snackBarQueue` 是静态的），
  /// 只要上一条 snackbar 还占着队列，同一测试文件里后续用例的 snackbar 会被无限排队、
  /// 永远不显示——断言会变成"依赖用例顺序"的随机失败。
  /// 因此凡断言过提示文案的用例，结束时都要把队列排空。
  Future<void> drainSnackbars(WidgetTester tester) async {
    await tester.pump(const Duration(seconds: 6));
    await tester.pumpAndSettle();
  }

  void enlargeWindow(WidgetTester tester) {
    tester.view.physicalSize = const Size(1080, 2400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
  }

  setUp(() async {
    Get.reset();
    FlutterSecureStorage.setMockInitialValues({});
    await Get.putAsync(() => StorageService().init());
    final auth = Get.put(AuthController());
    auth.isLoggedIn.value = true;
    auth.currentUser.value = UserProfileModel(
      id: '1001',
      username: 'buyer_test',
      nickname: '买家测试',
      role: 'USER',
      status: 'ACTIVE',
      verifyStatus: 'SUCCESS',
      schoolName: '清华大学',
    );
  });

  tearDown(() {
    if (mockInterceptor != null) {
      DioClient().dio.interceptors.remove(mockInterceptor);
      mockInterceptor = null;
    }
    Get.reset();
  });

  // ==========================================================================
  // HomePage
  // ==========================================================================

  group('Stage 8-C6: HomePage', () {
    testWidgets('1. 未登录：展示登录/注册入口与登录引导条（且不显示待办区块）',
        (WidgetTester tester) async {
      enlargeWindow(tester);
      Get.find<AuthController>()
        ..isLoggedIn.value = false
        ..currentUser.value = null;

      installMockApi((options) => jsonOk(options, null));

      await tester.pumpWidget(GetMaterialApp(
        initialRoute: AppRoutes.home,
        getPages: AppPages.routes,
      ));
      await tester.pumpAndSettle();

      expect(find.text('CampusTrade · 校园二手交易平台'), findsOneWidget);
      expect(find.textContaining('登录即可体验完整的校园认证'), findsOneWidget);
      expect(find.widgetWithText(OutlinedButton, '注册'), findsOneWidget);
      expect(find.widgetWithText(ElevatedButton, '登录'), findsOneWidget);
      // 登录态横幅不出现
      expect(find.textContaining('欢迎回来'), findsNothing);
      // 未登录：搜索框与最新商品照常可用，"我的待办"整块不出现
      expect(find.text(HomeSearchField.hintText), findsOneWidget);
      expect(find.text('最新商品'), findsOneWidget);
      expect(find.text('我的待办'), findsNothing);
    });

    testWidgets('2. 已登录：欢迎语 + 搜索框 + 查看全部进入校园集市',
        (WidgetTester tester) async {
      enlargeWindow(tester);

      installMockApi((options) {
        if (options.path.endsWith('/orders/summary')) {
          return jsonOk(options, {
            'pendingSellerConfirm': 0,
            'waitMeet': 0,
            'toReview': 0,
          });
        }
        if (options.path.endsWith('/goods/search/hot') ||
            options.path.endsWith('/goods/search/history')) {
          return jsonOk(options, <dynamic>[]);
        }
        if (options.path.contains('/category/list')) {
          return jsonOk(options, <dynamic>[]);
        }
        if (options.path.contains('/goods/list')) return pageOf(options, [goods()]);
        return jsonOk(options, null);
      });

      await tester.pumpWidget(GetMaterialApp(
        initialRoute: AppRoutes.home,
        getPages: AppPages.routes,
      ));
      await tester.pumpAndSettle();

      expect(find.text('欢迎回来，买家测试！'), findsOneWidget);
      expect(find.textContaining('已通过 清华大学 校园认证'), findsOneWidget);

      await tester.ensureVisible(find.text('查看全部 →'));
      await tester.tap(find.text('查看全部 →'));
      await tester.pumpAndSettle();
      expect(find.byType(GoodsListPage), findsOneWidget);
      expect(find.text('校园二手电单车'), findsOneWidget);
    });
  });

  // ==========================================================================
  // MyGoodsPage
  // ==========================================================================

  group('Stage 8-C6: MyGoodsPage', () {
    testWidgets('3. 列表渲染：商品信息 + 编辑/下架/删除入口 + 状态徽标',
        (WidgetTester tester) async {
      enlargeWindow(tester);

      installMockApi((options) {
        if (options.path.contains('/goods/my')) {
          return jsonOk(options, [
            goods(),
            goods(id: 502, title: '已下架的机械键盘', price: 199.0, status: 'OFF_SHELF'),
          ]);
        }
        if (options.path.contains('/goods/list')) return pageOf(options, []);
        return jsonOk(options, null);
      });

      await tester.pumpWidget(GetMaterialApp(
        initialRoute: AppRoutes.goodsMy,
        getPages: AppPages.routes,
      ));
      await tester.pumpAndSettle();

      expect(find.text('校园二手电单车'), findsOneWidget);
      expect(find.text('¥699.00'), findsOneWidget);
      expect(find.text('已下架的机械键盘'), findsOneWidget);
      expect(find.text('已下架'), findsOneWidget, reason: 'ON_SALE 不显示不可用徽标');
      expect(find.text('编辑'), findsNWidgets(2));
      expect(find.text('下架'), findsOneWidget);
      expect(find.text('重新上架'), findsOneWidget);
      expect(find.text('删除'), findsNWidgets(2));
    });

    testWidgets('4. 关键交互：点"下架"发出状态变更请求并给出反馈',
        (WidgetTester tester) async {
      enlargeWindow(tester);

      String? patchedStatus;
      int statusCalls = 0;

      installMockApi((options) {
        if (options.method == 'PUT' && options.path.contains('/status')) {
          statusCalls++;
          patchedStatus = (options.data as Map)['status'] as String?;
          return jsonOk(options, null);
        }
        if (options.path.contains('/goods/my')) {
          return jsonOk(options, [
            goods(status: statusCalls == 0 ? 'ON_SALE' : 'OFF_SHELF'),
          ]);
        }
        if (options.path.contains('/goods/list')) return pageOf(options, []);
        return jsonOk(options, null);
      });

      await tester.pumpWidget(GetMaterialApp(
        initialRoute: AppRoutes.goodsMy,
        getPages: AppPages.routes,
      ));
      await tester.pumpAndSettle();

      await tester.tap(find.text('下架'));
      await tester.pumpAndSettle();

      expect(statusCalls, 1);
      expect(patchedStatus, 'OFF_SHELF');
      // GetX 的 snackbar 是全局单条队列（SnackbarController._snackBarQueue 为静态），
      // 上一条提示未走完时后续提示会被排队而不显示；因此"提示文案"类断言只在本文件
      // 这一处（本文件的第一条 snackbar）做，其余用例改为断言状态与请求。
      expect(find.text('商品已下架'), findsOneWidget);
      expect(find.text('重新上架'), findsOneWidget, reason: '列表应以最新状态重新渲染');
      await drainSnackbars(tester);
    });

    testWidgets('5. 空态：无商品时给出发布入口', (WidgetTester tester) async {
      enlargeWindow(tester);

      installMockApi((options) {
        if (options.path.contains('/goods/my')) return jsonOk(options, <dynamic>[]);
        return jsonOk(options, null);
      });

      await tester.pumpWidget(GetMaterialApp(
        initialRoute: AppRoutes.goodsMy,
        getPages: AppPages.routes,
      ));
      await tester.pumpAndSettle();

      expect(find.text('您尚未发布过任何闲置商品'), findsOneWidget);
      expect(find.text('立即发布一件'), findsOneWidget);
    });

    testWidgets('6. 错误态与重试：断网显示错误态，恢复后重试成功',
        (WidgetTester tester) async {
      enlargeWindow(tester);

      bool offlineMode = true;
      installMockApi((options) {
        if (options.path.contains('/goods/my')) {
          if (offlineMode) throw offline(options);
          return jsonOk(options, [goods()]);
        }
        return jsonOk(options, null);
      });

      await tester.pumpWidget(GetMaterialApp(
        initialRoute: AppRoutes.goodsMy,
        getPages: AppPages.routes,
      ));
      await tester.pumpAndSettle();

      expect(find.text('我的商品加载失败'), findsOneWidget);
      expect(find.text('您尚未发布过任何闲置商品'), findsNothing,
          reason: '加载失败绝不能伪装成"没有商品"的空态');

      offlineMode = false;
      await tester.tap(find.text('点击重试'));
      await tester.pumpAndSettle();

      expect(find.text('校园二手电单车'), findsOneWidget);
      expect(find.text('我的商品加载失败'), findsNothing);
    });
  });

  // ==========================================================================
  // MyOrdersPage
  // ==========================================================================

  group('Stage 8-C6: MyOrdersPage', () {
    testWidgets('7. 角色切换：BUYER/SELLER 各自请求并渲染对应列表',
        (WidgetTester tester) async {
      enlargeWindow(tester);

      final requestedRoles = <String>[];
      installMockApi((options) {
        if (options.path.contains('/orders/my')) {
          final role = options.queryParameters['role'] as String? ?? '';
          requestedRoles.add(role);
          if (role == 'SELLER') {
            return pageOf(options, [
              {
                'id': 902,
                'orderNo': 'ORD-SELLER-002',
                'goodsId': 601,
                'goodsTitleSnapshot': '我卖出的自行车',
                'goodsPriceSnapshot': 300.00,
                'buyerId': 3001,
                'sellerId': 1001,
                'orderStatus': 'WAIT_SELLER_CONFIRM',
                'statusDesc': '待卖家确认',
                'createdTime': '2026-09-17 09:00:00',
              }
            ]);
          }
          return pageOf(options, [
            {
              'id': 901,
              'orderNo': 'ORD-BUYER-001',
              'goodsId': 501,
              'goodsTitleSnapshot': '我买到的电单车',
              'goodsPriceSnapshot': 699.00,
              'buyerId': 1001,
              'sellerId': 2002,
              'orderStatus': 'WAIT_MEET',
              'statusDesc': '待面交',
              'createdTime': '2026-09-17 15:00:00',
            }
          ]);
        }
        return jsonOk(options, null);
      });

      await tester.pumpWidget(GetMaterialApp(
        initialRoute: AppRoutes.orderMy,
        getPages: AppPages.routes,
      ));
      await tester.pumpAndSettle();

      expect(requestedRoles, contains('BUYER'));
      expect(find.text('我买到的电单车'), findsOneWidget);
      // 状态文案在筛选芯片与状态徽标上都会出现，这里只要求"确实渲染了状态"
      expect(find.text('待面交'), findsWidgets);

      await tester.tap(find.text('我的出售'));
      await tester.pumpAndSettle();

      expect(requestedRoles, contains('SELLER'));
      expect(find.text('我卖出的自行车'), findsOneWidget);
      expect(find.text('我买到的电单车'), findsNothing);
      expect(find.text('待卖家确认'), findsWidgets);
    });

    testWidgets('8. 错误态与重试：断网显示错误态与重试按钮', (WidgetTester tester) async {
      enlargeWindow(tester);

      bool offlineMode = true;
      installMockApi((options) {
        if (options.path.contains('/orders/my')) {
          if (offlineMode) throw offline(options);
          return pageOf(options, [
            {
              'id': 901,
              'orderNo': 'ORD-BUYER-001',
              'goodsId': 501,
              'goodsTitleSnapshot': '我买到的电单车',
              'goodsPriceSnapshot': 699.00,
              'orderStatus': 'WAIT_MEET',
              'statusDesc': '待面交',
            }
          ]);
        }
        return jsonOk(options, null);
      });

      await tester.pumpWidget(GetMaterialApp(
        initialRoute: AppRoutes.orderMy,
        getPages: AppPages.routes,
      ));
      await tester.pumpAndSettle();

      expect(find.text('加载订单失败'), findsOneWidget);
      expect(find.text('暂无相关订单'), findsNothing);

      offlineMode = false;
      await tester.tap(find.text('点击重试'));
      await tester.pumpAndSettle();

      expect(find.text('我买到的电单车'), findsOneWidget);
      expect(find.text('加载订单失败'), findsNothing);
    });
  });

  // ==========================================================================
  // CreateGoodsPage
  // ==========================================================================

  group('Stage 8-C6: CreateGoodsPage', () {
    testWidgets('9. 表单校验：标题/价格必填且价格需大于 0',
        (WidgetTester tester) async {
      enlargeWindow(tester);

      installMockApi((options) {
        if (options.path.contains('/category/list')) {
          return jsonOk(options, [
            {
              'id': '100',
              'parentId': '0',
              'name': '数码',
              'sort': 1,
              'children': [
                {'id': '101', 'parentId': '100', 'name': '手机', 'sort': 1},
              ],
            }
          ]);
        }
        return jsonOk(options, null);
      });

      await tester.pumpWidget(GetMaterialApp(
        initialRoute: AppRoutes.goodsCreate,
        getPages: AppPages.routes,
      ));
      await tester.pumpAndSettle();

      expect(find.byType(CreateGoodsPage), findsOneWidget);

      // 直接提交空表单：标题与价格都必须被拦下
      await tester.ensureVisible(find.text('确认发布商品'));
      await tester.tap(find.text('确认发布商品'));
      await tester.pumpAndSettle();
      expect(find.text('请输入商品标题'), findsOneWidget);
      expect(find.text('请输入价格'), findsOneWidget);

      // 填标题、价格 0：价格必须被拦下
      await tester.enterText(find.byType(TextFormField).first, '九成新台灯');
      await tester.enterText(find.byType(TextFormField).at(1), '0');
      await tester.tap(find.text('确认发布商品'));
      await tester.pumpAndSettle();
      expect(find.text('请输入商品标题'), findsNothing);
      expect(find.text('价格须大于0'), findsOneWidget);
    });

    testWidgets('10. 分类加载失败的可见降级：内联提示 + 重新加载',
        (WidgetTester tester) async {
      enlargeWindow(tester);

      bool offlineMode = true;
      installMockApi((options) {
        if (options.path.contains('/category/list')) {
          if (offlineMode) throw offline(options);
          return jsonOk(options, [
            {
              'id': '100',
              'parentId': '0',
              'name': '数码',
              'sort': 1,
              'children': [
                {'id': '101', 'parentId': '100', 'name': '手机', 'sort': 1},
              ],
            }
          ]);
        }
        return jsonOk(options, null);
      });

      await tester.pumpWidget(GetMaterialApp(
        initialRoute: AppRoutes.goodsCreate,
        getPages: AppPages.routes,
      ));
      await tester.pumpAndSettle();

      // 断网时展示的是统一错误映射给出的可读文案（而不是泛化的空下拉框）
      expect(find.textContaining('请检查网络'), findsOneWidget);
      expect(find.text('重新加载'), findsOneWidget);

      offlineMode = false;
      await tester.tap(find.text('重新加载'));
      await tester.pumpAndSettle();

      expect(find.textContaining('请检查网络'), findsNothing);
    });

    testWidgets('11. 图片上传分支：选图成功后回填缩略图并提示',
        (WidgetTester tester) async {
      enlargeWindow(tester);

      // 造一个真实的临时文件，让 XFile.readAsBytes() 能读到字节
      final File fixture = File(
        '${Directory.systemTemp.path}${Platform.pathSeparator}campus_trade_upload_fixture.png',
      )..writeAsBytesSync(List<int>.generate(64, (i) => i));

      const MethodChannel pickerChannel =
          MethodChannel('plugins.flutter.io/image_picker');
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(pickerChannel, (call) async {
        if (call.method == 'pickImage') return fixture.path;
        return null;
      });
      addTearDown(() {
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(pickerChannel, null);
        if (fixture.existsSync()) fixture.deleteSync();
      });

      String? uploadedFileName;
      installMockApi((options) {
        if (options.path.contains('/category/list')) {
          return jsonOk(options, [
            {
              'id': '100',
              'parentId': '0',
              'name': '数码',
              'sort': 1,
              'children': [
                {'id': '101', 'parentId': '100', 'name': '手机', 'sort': 1},
              ],
            }
          ]);
        }
        if (options.path.contains('/file/upload')) {
          final Object? payload = options.data;
          // MultipartFile 的文件名会出现在 FormData 里，用它证明"选中的文件真的被上传了"
          uploadedFileName = payload is dio.FormData
              ? payload.files.map((f) => f.value.filename).join(',')
              : payload.toString();
          return jsonOk(options, 'http://127.0.0.1:9000/campustrade/goods/uploaded.png');
        }
        return jsonOk(options, null);
      });

      await tester.pumpWidget(GetMaterialApp(
        initialRoute: AppRoutes.goodsCreate,
        getPages: AppPages.routes,
      ));
      await tester.pumpAndSettle();

      expect(find.text('添加图片'), findsOneWidget);

      // 注意：页面内部会对选中的文件做真实 dart:io 读取（XFile.readAsBytes）。
      // 在 widget 测试的 fake async 时区里真实文件 I/O 不会完成，因此这一步必须放在
      // [WidgetTester.runAsync]（真实异步时区）里执行，否则会一直挂在"上传中"。
      await tester.runAsync(() async {
        await tester.tap(find.text('添加图片'));
        await Future<void>.delayed(const Duration(milliseconds: 300));
      });
      await tester.pumpAndSettle();

      expect(uploadedFileName, 'campus_trade_upload_fixture.png',
          reason: '选中的文件必须真正走上传接口');
      // 上传成功后回填一张缩略图（缩略图组件本身在 GoodsThumbnail 用例里单独覆盖）。
      // 注意：这里不断言"图片上传成功"的 snackbar —— 上传流程必须在 runAsync（真实异步时区）
      // 里跑完，而 GetX 的 snackbar 动画由 fake-async 帧驱动，两个时区混用会让提示的可见性
      // 不稳定；提示类断言统一放在单条 snackbar 的用例与 safeSnackbar 源码守卫里。
      expect(find.byType(GoodsThumbnail), findsOneWidget);
      expect(find.text('添加图片'), findsOneWidget, reason: '仍可继续添加（未达 9 张上限）');
      await drainSnackbars(tester);
    });
  });

  // ==========================================================================
  // FavoritePage / HistoryPage 状态徽标
  // ==========================================================================

  group('Stage 8-C6: 收藏与足迹的状态徽标', () {
    testWidgets('12. 收藏：仅不可购买的收藏项显示状态徽标',
        (WidgetTester tester) async {
      enlargeWindow(tester);

      installMockApi((options) {
        if (options.path.contains('/favorite/list')) {
          return pageOf(options, [
            {
              'id': 1,
              'goodsId': 201,
              'title': '正在出售的 Kindle',
              'price': 350.00,
              'conditionLevel': '95新',
              'status': 'ON_SALE',
            },
            {
              'id': 2,
              'goodsId': 202,
              'title': '已经被下架的耳机',
              'price': 120.00,
              'conditionLevel': '9成新',
              'status': 'OFF_SHELF',
            },
          ]);
        }
        return jsonOk(options, null);
      });

      await tester.pumpWidget(GetMaterialApp(
        initialRoute: AppRoutes.favorite,
        getPages: AppPages.routes,
      ));
      await tester.pumpAndSettle();

      expect(find.text('正在出售的 Kindle'), findsOneWidget);
      expect(find.text('已经被下架的耳机'), findsOneWidget);
      expect(find.text('已下架'), findsOneWidget, reason: '在售商品不应显示不可用徽标');
      expect(find.text('在售中'), findsNothing);
    });

    testWidgets('13. 足迹：已售出与在售各自渲染，并展示浏览时间',
        (WidgetTester tester) async {
      enlargeWindow(tester);

      installMockApi((options) {
        if (options.path.contains('/history/list')) {
          return pageOf(options, [
            {
              'id': 1,
              'goodsId': 301,
              'title': '已经卖掉的单车',
              'price': 699.00,
              'conditionLevel': '9成新',
              'status': 'SOLD',
              'browseTime': '2026-09-17 15:30:00',
            },
            {
              'id': 2,
              'goodsId': 302,
              'title': '还在卖的专业书',
              'price': 38.00,
              'conditionLevel': '全新',
              'status': 'ON_SALE',
              'browseTime': '2026-09-16 08:05:00',
            },
          ]);
        }
        return jsonOk(options, null);
      });

      await tester.pumpWidget(GetMaterialApp(
        initialRoute: AppRoutes.history,
        getPages: AppPages.routes,
      ));
      await tester.pumpAndSettle();

      expect(find.text('已经卖掉的单车'), findsOneWidget);
      expect(find.text('已售出'), findsOneWidget);
      expect(find.text('还在卖的专业书'), findsOneWidget);
      expect(find.text('09-17 15:30'), findsOneWidget, reason: '浏览时间按 MM-dd HH:mm 展示');
    });
  });

  // ==========================================================================
  // GoodsThumbnail
  // ==========================================================================

  group('Stage 8-C6: GoodsThumbnail', () {
    testWidgets('14. 空 / null URL 直接渲染占位块，不构造任何图片请求',
        (WidgetTester tester) async {
      await tester.pumpWidget(const MaterialApp(
        home: Scaffold(
          body: Center(
            child: GoodsThumbnail(imageUrl: null, width: 80, height: 80),
          ),
        ),
      ));
      // 没有 Image 组件 => 没有 ImageProvider => 不会有任何网络请求
      expect(find.byType(Image), findsNothing);
      expect(find.byIcon(Icons.image_outlined), findsOneWidget);

      await tester.pumpWidget(const MaterialApp(
        home: Scaffold(
          body: Center(
            child: GoodsThumbnail(imageUrl: '', width: 80, height: 80),
          ),
        ),
      ));
      await tester.pump();
      expect(find.byType(Image), findsNothing);
      expect(find.byIcon(Icons.image_outlined), findsOneWidget);
    });

    testWidgets('15. 有 URL 时才构造图片组件，并支持自定义占位图标',
        (WidgetTester tester) async {
      await tester.pumpWidget(const MaterialApp(
        home: Scaffold(
          body: Center(
            child: GoodsThumbnail(
              imageUrl: 'https://example.com/goods/1.png',
              width: 80,
              height: 80,
            ),
          ),
        ),
      ));
      await tester.pump();

      expect(find.byType(Image), findsOneWidget);
      final Image image = tester.widget<Image>(find.byType(Image));
      expect(image.image, isA<ImageProvider>());

      await tester.pumpWidget(const MaterialApp(
        home: Scaffold(
          body: Center(
            child: GoodsThumbnail(
              imageUrl: null,
              width: 80,
              height: 80,
              placeholderIcon: Icons.receipt_long_outlined,
            ),
          ),
        ),
      ));
      await tester.pump();
      expect(find.byIcon(Icons.receipt_long_outlined), findsOneWidget);
    });
  });

  // ==========================================================================
  // ai_goods_assistant_sheet
  // ==========================================================================

  group('Stage 8-C6: AI 商品助手弹窗', () {
    testWidgets('16. AI 帮写描述：生成 -> 展示结果 -> 采纳回填表单',
        (WidgetTester tester) async {
      enlargeWindow(tester);

      int aiCalls = 0;
      installMockApi((options) {
        if (options.path.contains('/ai/goods/description')) {
          aiCalls++;
          return jsonOk(options, {
            'title': '九成新台灯',
            'description': 'AI 生成的描述：宿舍自用台灯，九成新，护眼无频闪。',
            'highlights': <String>['护眼', '可调光'],
            'costMs': 320,
          });
        }
        if (options.path.contains('/category/list')) {
          return jsonOk(options, [
            {
              'id': '100',
              'parentId': '0',
              'name': '数码',
              'sort': 1,
              'children': [
                {'id': '101', 'parentId': '100', 'name': '手机', 'sort': 1},
              ],
            }
          ]);
        }
        return jsonOk(options, null);
      });

      await tester.pumpWidget(GetMaterialApp(
        initialRoute: AppRoutes.goodsCreate,
        getPages: AppPages.routes,
      ));
      await tester.pumpAndSettle();

      // 未填标题：本地就被拦下，既不开弹窗也不消耗 AI 配额
      // （后端有 10 次/分钟、50 次/天的配额，无效调用必须挡在客户端）
      await tester.tap(find.text('AI 帮写描述'));
      await tester.pumpAndSettle();
      expect(aiCalls, 0, reason: '标题为空时不得消耗 AI 配额（后端 10 次/分钟、50 次/天）');
      expect(find.text('DeepSeek AI 帮写描述'), findsNothing);

      await tester.enterText(find.byType(TextFormField).first, '九成新台灯');
      await tester.pumpAndSettle();
      await tester.tap(find.text('AI 帮写描述'));
      await tester.pumpAndSettle();

      expect(find.text('DeepSeek AI 帮写描述'), findsOneWidget);
      expect(aiCalls, 1);
      expect(find.textContaining('AI 生成的描述：宿舍自用台灯'), findsOneWidget);

      await tester.ensureVisible(find.text('采纳并填入'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('采纳并填入'));
      await tester.pumpAndSettle();

      // 采纳后关闭弹窗并把文案回填到描述输入框
      expect(find.text('DeepSeek AI 帮写描述'), findsNothing);
      expect(find.textContaining('AI 生成的描述：宿舍自用台灯'), findsOneWidget);
      expect(find.text('AI 生成的描述已填入发布表单'), findsNothing);
    });
  });
}
