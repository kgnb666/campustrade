import 'dart:async';
import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/services.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:frontend/api/dio_client.dart';
import 'package:frontend/controllers/auth_controller.dart';
import 'package:frontend/controllers/favorite_controller.dart';
import 'package:frontend/controllers/goods_controller.dart';
import 'package:frontend/controllers/history_controller.dart';
import 'package:frontend/controllers/order_controller.dart';
import 'package:frontend/routes/app_pages.dart';
import 'package:frontend/routes/app_routes.dart';
import 'package:frontend/services/storage_service.dart';
import 'package:frontend/utils/name_utils.dart';
import 'package:get/get.dart' hide Response;

/// 阶段 6：前端稳定性回归测试
///
/// 覆盖三类此前会静默出错的场景：
/// 1. 昵称为空串时的首字提取（RangeError -> 白屏）；
/// 2. 分页状态机（失败回滚页码 + 刷新/加载更多并发的请求序号作废）；
/// 3. 会话过期只处理一次（同一进程内第二次过期必须仍能清理并跳转登录）。
void main() {
  Interceptor? mockInterceptor;

  /// 安装异步接口桩：responder 可返回 Future，便于精确控制并发时序。
  void installMockApi(Future<Response> Function(RequestOptions options) responder) {
    if (mockInterceptor != null) {
      DioClient().dio.interceptors.remove(mockInterceptor);
    }
    mockInterceptor = InterceptorsWrapper(
      onRequest: (options, handler) async {
        try {
          final resp = await responder(options);
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

  Response okList(RequestOptions options, List<Map<String, dynamic>> records,
      {int pages = 1, int current = 1, int total = 0}) {
    return Response(
      requestOptions: options,
      statusCode: 200,
      data: {
        'code': 200,
        'message': 'success',
        'data': {
          'records': records,
          'total': total == 0 ? records.length : total,
          'size': 10,
          'current': current,
          'pages': pages,
        },
      },
    );
  }

  Map<String, dynamic> goodsJson(String id, {int page = 1}) => {
        'id': id,
        'sellerId': 2001,
        'schoolId': 1,
        'schoolName': '清华大学',
        'categoryId': 101,
        'categoryName': '教材书籍',
        'title': '商品 $id',
        'price': 10.5,
        'conditionLevel': '9成新',
        'status': 'ON_SALE',
        'page': page,
      };

  late HttpClientAdapter defaultAdapter;

  setUp(() async {
    Get.reset();
    FlutterSecureStorage.setMockInitialValues({});
    await Get.putAsync(() => StorageService().init());
    defaultAdapter = DioClient().dio.httpClientAdapter;
    DioClient().resetSessionState();
  });

  tearDown(() {
    if (mockInterceptor != null) {
      DioClient().dio.interceptors.remove(mockInterceptor);
      mockInterceptor = null;
    }
    DioClient().dio.httpClientAdapter = defaultAdapter;
    DioClient().resetSessionState();
    Get.reset();
  });

  // ==========================================================================
  // 一、昵称/用户名首字提取
  // ==========================================================================

  group('Stage 6-1: initialOf / displayNameOf 空值与边界', () {
    test('1. 昵称为空串时回退到用户名，不再抛 RangeError', () {
      // 修复前：'' ?? username 仍是 ''，substring(0,1) 直接抛 RangeError
      expect(initialOf('', 'test_student'), equals('T'));
      expect(initialOf(null, 'test_student'), equals('T'));
      expect(initialOf('   ', 'test_student'), equals('T'));
    });

    test('2. 中文昵称取首字；两者皆空时给出兜底字符', () {
      expect(initialOf('小明', 'test_student'), equals('小'));
      expect(initialOf('  小明  ', 'test_student'), equals('小'));
      expect(initialOf('', ''), equals('?'));
      expect(initialOf(null, null), equals('?'));
    });

    test('3. emoji 等代理对字符不会被截成半个字符', () {
      final initial = initialOf('😀同学', 'test_student');
      // substring(0,1) 会得到孤立代理项（渲染为乱码方块）；这里必须是完整字符
      expect(initial.runes.length, equals(1));
      expect(initial, equals(String.fromCharCode(0x1F600)));
    });

    test('4. displayNameOf：昵称空白时回退用户名', () {
      expect(displayNameOf('', 'test_student'), equals('test_student'));
      expect(displayNameOf('  ', 'test_student'), equals('test_student'));
      expect(displayNameOf('小明', 'test_student'), equals('小明'));
      expect(displayNameOf(null, null), equals('未设置昵称'));
    });
  });

  // ==========================================================================
  // 二、分页状态机
  // ==========================================================================

  group('Stage 6-4: 商品列表分页状态机', () {
    test('5. 第 2 页失败后回滚页码，重试仍请求第 2 页', () async {
      final requestedPages = <int>[];
      int page2Attempts = 0;

      installMockApi((options) async {
        if (options.path.contains('/goods/list')) {
          final page = int.parse(options.queryParameters['page'].toString());
          requestedPages.add(page);
          if (page == 2 && page2Attempts == 0) {
            page2Attempts++;
            throw DioException(
              requestOptions: options,
              type: DioExceptionType.connectionError,
              message: 'connection refused',
            );
          }
          return okList(
            options,
            [goodsJson('p$page-1', page: page)],
            pages: 3,
            current: page,
            total: 30,
          );
        }
        return okList(options, <Map<String, dynamic>>[]);
      });

      final controller = GoodsController();
      await controller.loadGoods(refresh: true);
      expect(controller.currentPage.value, equals(1));
      expect(controller.goodsList.length, equals(1));
      expect(controller.hasMore.value, isTrue);

      // 上拉加载第 2 页 -> 失败
      await controller.loadMore();
      expect(controller.errorMessage.value, isNotEmpty);
      expect(controller.currentPage.value, equals(1), reason: '失败必须回滚页码，否则会永久跳过一页');
      expect(controller.goodsList.length, equals(1), reason: '失败不得追加任何数据');
      expect(controller.hasMore.value, isTrue, reason: '失败后仍应允许重试该页');

      // 再次上拉 -> 必须重新请求第 2 页
      await controller.loadMore();
      expect(requestedPages, equals([1, 2, 2]));
      expect(controller.currentPage.value, equals(2));
      expect(controller.goodsList.map((e) => e.id).toList(),
          equals(['p1-1', 'p2-1']));
      expect(controller.errorMessage.value, isEmpty, reason: '重试成功后错误态必须清除');
    });

    test('6. 刷新与加载更多并发 20 轮：列表无重复、无错序、不追加上一页数据', () async {
      for (var round = 0; round < 20; round++) {
        final tag = 'r$round';
        final page2Gate = Completer<Response>();

        installMockApi((options) async {
          if (options.path.contains('/goods/list')) {
            final page = int.parse(options.queryParameters['page'].toString());
            if (page == 1) {
              return okList(
                options,
                [goodsJson('$tag-p1-1'), goodsJson('$tag-p1-2')],
                pages: 3,
                current: 1,
                total: 30,
              );
            }
            // 第 2 页挂起：模拟"上拉请求还在途时用户下拉刷新"
            return page2Gate.future;
          }
          return okList(options, <Map<String, dynamic>>[]);
        });

        final controller = GoodsController();
        await controller.loadGoods(refresh: true);
        expect(controller.goodsList.length, equals(2));

        // 上拉加载第 2 页（在途，被 gate 挂起）
        final moreFuture = controller.loadMore();
        await Future<void>.delayed(Duration.zero);

        // 下拉刷新（自增 requestId，作废在途的第 2 页响应）
        await controller.loadGoods(refresh: true);

        // 放行陈旧的第 2 页响应
        page2Gate.complete(okList(
          RequestOptions(path: '/goods/list'),
          [goodsJson('$tag-p2-1')],
          pages: 3,
          current: 2,
          total: 30,
        ));
        await moreFuture;

        final ids = controller.goodsList.map((e) => e.id).toList();
        expect(ids, equals(['$tag-p1-1', '$tag-p1-2']),
            reason: '陈旧的第 2 页响应必须被整份丢弃（第 $round 轮）');
        expect(ids.toSet().length, equals(ids.length), reason: '列表不得出现重复项');
        expect(controller.currentPage.value, equals(1), reason: '刷新后页码应停在第 1 页');
        expect(controller.isLoading.value, isFalse);
        expect(controller.isMoreLoading.value, isFalse);
      }
    });

    test('7. 收藏列表：失败回滚页码 + 刷新作废在途加载更多', () async {
      final requested = <int>[];
      int failures = 0;

      installMockApi((options) async {
        if (options.path.contains('/favorite/list')) {
          final page = int.parse(options.queryParameters['page'].toString());
          requested.add(page);
          if (page == 2 && failures == 0) {
            failures++;
            throw DioException(
              requestOptions: options,
              type: DioExceptionType.receiveTimeout,
              message: 'timeout',
            );
          }
          return okList(
            options,
            [
              {
                'id': page,
                'goodsId': 100 + page,
                'title': '收藏 $page',
                'price': 9.9,
                'conditionLevel': '95新',
                'status': 'ON_SALE',
              }
            ],
            pages: 3,
            current: page,
            total: 30,
          );
        }
        return okList(options, <Map<String, dynamic>>[]);
      });

      final controller = FavoriteController();
      await controller.loadFavorites(refresh: true);
      expect(controller.favoriteList.length, equals(1));

      await controller.loadMore();
      expect(controller.errorMessage.value, isNotEmpty);
      expect(controller.currentPage.value, equals(1), reason: '收藏列表失败必须回滚页码');

      await controller.loadMore();
      expect(requested, equals([1, 2, 2]));
      expect(controller.favoriteList.length, equals(2));
      expect(controller.errorMessage.value, isEmpty);
    });

    test('8. 足迹列表：失败回滚页码，重试仍取同一页', () async {
      final requested = <int>[];
      int failures = 0;

      installMockApi((options) async {
        if (options.path.contains('/history/list')) {
          final page = int.parse(options.queryParameters['page'].toString());
          requested.add(page);
          if (page == 2 && failures == 0) {
            failures++;
            throw DioException(
              requestOptions: options,
              type: DioExceptionType.connectionError,
              message: 'offline',
            );
          }
          return okList(
            options,
            [
              {
                'id': page,
                'goodsId': 200 + page,
                'title': '足迹 $page',
                'price': 5.0,
                'conditionLevel': '全新',
                'status': 'ON_SALE',
              }
            ],
            pages: 2,
            current: page,
            total: 20,
          );
        }
        return okList(options, <Map<String, dynamic>>[]);
      });

      final controller = HistoryController();
      await controller.loadHistory(refresh: true);
      await controller.loadMore();
      expect(controller.currentPage.value, equals(1));
      expect(controller.errorMessage.value, isNotEmpty);

      await controller.loadMore();
      expect(requested, equals([1, 2, 2]));
      expect(controller.historyList.length, equals(2));
    });

    test('9. 订单列表：加载更多失败回滚页码，重试仍取第 2 页', () async {
      final requested = <int>[];
      int failures = 0;

      installMockApi((options) async {
        if (options.path.contains('/orders/my')) {
          final page = int.parse(options.queryParameters['page'].toString());
          requested.add(page);
          if (page == 2 && failures == 0) {
            failures++;
            throw DioException(
              requestOptions: options,
              type: DioExceptionType.connectionError,
              message: 'offline',
            );
          }
          return okList(
            options,
            [
              {
                'id': page,
                'orderNo': 'ORD-$page',
                'goodsId': 300 + page,
                'goodsPriceSnapshot': 12.0,
                'buyerId': 1001,
                'sellerId': 2002,
                'orderStatus': 'WAIT_MEET',
                'statusDesc': '待面交',
              }
            ],
            pages: 3,
            current: page,
            total: 30,
          );
        }
        return okList(options, <Map<String, dynamic>>[]);
      });

      final controller = OrderController();
      await controller.fetchMyOrders(refresh: true);
      expect(controller.orders.length, equals(1));

      await controller.loadMoreOrders();
      expect(controller.currentPage.value, equals(1), reason: '订单列表失败必须回滚页码');
      expect(controller.errorMessage.value, isNotEmpty);

      await controller.loadMoreOrders();
      expect(requested, equals([1, 2, 2]));
      expect(controller.orders.map((o) => o.id).toList(), equals(['1', '2']));
      expect(controller.errorMessage.value, isEmpty);
    });

    test('10. 订单列表：刷新作废在途的加载更多响应', () async {
      final page2Gate = Completer<Response>();

      installMockApi((options) async {
        if (options.path.contains('/orders/my')) {
          final page = int.parse(options.queryParameters['page'].toString());
          Response build(String id) => okList(
                options,
                [
                  {
                    'id': id,
                    'orderNo': 'ORD-$id',
                    'goodsId': 301,
                    'goodsPriceSnapshot': 12.0,
                    'buyerId': 1001,
                    'sellerId': 2002,
                    'orderStatus': 'WAIT_MEET',
                    'statusDesc': '待面交',
                  }
                ],
                pages: 3,
                current: page,
                total: 30,
              );
          if (page == 1) return build('new-1');
          return page2Gate.future;
        }
        return okList(options, <Map<String, dynamic>>[]);
      });

      final controller = OrderController();
      await controller.fetchMyOrders(refresh: true);

      final moreFuture = controller.loadMoreOrders();
      await Future<void>.delayed(Duration.zero);
      await controller.fetchMyOrders(refresh: true);

      page2Gate.complete(okList(
        RequestOptions(path: '/orders/my'),
        [
          {
            'id': 'stale-2',
            'orderNo': 'ORD-stale-2',
            'goodsId': 302,
            'goodsPriceSnapshot': 12.0,
            'buyerId': 1001,
            'sellerId': 2002,
            'orderStatus': 'WAIT_MEET',
            'statusDesc': '待面交',
          }
        ],
        pages: 3,
        current: 2,
        total: 30,
      ));
      await moreFuture;

      expect(controller.orders.map((o) => o.id).toList(), equals(['new-1']),
          reason: '刷新后到达的陈旧第 2 页响应必须被丢弃');
    });
  });

  // ==========================================================================
  // 三、会话过期可重复处理
  // ==========================================================================

  group('Stage 6-2: 会话过期（可重复触发）', () {
    test('11. 被强制登出 -> 重新登录 -> 再次过期仍能清理凭据（同一进程内可重复触发）', () async {
      final dioClient = DioClient();
      final storage = Get.find<StorageService>();
      final auth = Get.put(AuthController());

      // 用 HTTP 适配器模拟真实的 401/200 响应（与 Stage Fix-3 相同的驱动方式）
      dioClient.dio.httpClientAdapter = _MockAdapter((options) async {
        if (options.path.contains('/auth/login')) {
          return ResponseBody.fromString(
            jsonEncode({
              'code': 200,
              'message': 'success',
              'data': {
                'accessToken': 'fresh_access_token',
                'refreshToken': 'fresh_refresh_token',
                'userInfo': {
                  'id': '1001',
                  'username': 'buyer_test',
                  'nickname': '买家测试',
                  'role': 'USER',
                  'status': 'ACTIVE',
                  'verifyStatus': 'SUCCESS',
                },
              },
            }),
            200,
            headers: {
              Headers.contentTypeHeader: [Headers.jsonContentType]
            },
          );
        }
        // 受保护接口：带过期 token 一律 401
        return ResponseBody.fromString(
          jsonEncode({'code': 401, 'message': 'Token expired'}),
          401,
          headers: {
            Headers.contentTypeHeader: [Headers.jsonContentType]
          },
        );
      });

      // 刷新接口同样失效（Refresh Token 已过期）
      final brokenRefreshDio = Dio();
      brokenRefreshDio.httpClientAdapter = _MockAdapter((options) async {
        return ResponseBody.fromString(
          '{"code":401,"message":"Refresh token expired"}',
          401,
          headers: {
            Headers.contentTypeHeader: [Headers.jsonContentType]
          },
        );
      });
      dioClient.customRefreshDio = brokenRefreshDio;

      Future<void> triggerExpiry() async {
        try {
          await dioClient.dio.get('/user/profile');
          fail('会话过期时请求应向调用方传递 DioException');
        } on DioException {
          // 预期路径：静默刷新失败 -> 强制登出（清理凭据）
        }
      }

      // ---- 第一次会话过期 ----
      await storage.saveToken('expired_token_1');
      await storage.saveRefreshToken('expired_refresh_1');
      auth.isLoggedIn.value = true;
      auth.token.value = 'expired_token_1';

      await triggerExpiry();

      expect(await storage.getToken(), isNull, reason: '第一次过期必须清空 Access Token');
      expect(await storage.getRefreshToken(), isNull);
      expect(auth.isLoggedIn.value, isFalse);
      expect(auth.token.value, isEmpty);

      // ---- 重新登录成功：必须复位"已处理过期"标记 ----
      final loggedIn = await auth.login('buyer_test', 'password');
      expect(loggedIn, isTrue, reason: '重新登录必须成功');
      expect(auth.isLoggedIn.value, isTrue);
      expect(await storage.getToken(), equals('fresh_access_token'));

      // ---- 第二次会话过期：修复前会被静默忽略（不清 storage、不跳登录、不提示）----
      await storage.saveToken('expired_token_2');
      await storage.saveRefreshToken('expired_refresh_2');
      auth.token.value = 'expired_token_2';

      await triggerExpiry();

      expect(await storage.getToken(), isNull,
          reason: '第二次过期同样必须清空 Access Token（这正是修复前会失败的断言）');
      expect(await storage.getRefreshToken(), isNull,
          reason: '第二次过期必须清空 Refresh Token');
      expect(auth.isLoggedIn.value, isFalse);
      expect(auth.currentUser.value, isNull);
    });

    testWidgets('12. 会话过期处理会清空状态并跳转登录页', (WidgetTester tester) async {
      final storage = Get.find<StorageService>();
      final auth = Get.put(AuthController());

      await storage.saveToken('expired_token');
      await storage.saveRefreshToken('expired_refresh');
      auth.isLoggedIn.value = true;
      auth.token.value = 'expired_token';

      await tester.pumpWidget(GetMaterialApp(
        initialRoute: AppRoutes.home,
        getPages: AppPages.routes,
      ));
      await tester.pumpAndSettle();

      // DioClient 在静默刷新彻底失败时调用的就是这个入口
      await auth.handleSessionExpired();
      await tester.pumpAndSettle();

      expect(await storage.getToken(), isNull);
      expect(await storage.getRefreshToken(), isNull);
      expect(auth.isLoggedIn.value, isFalse);
      expect(Get.currentRoute, equals(AppRoutes.LOGIN));

      // 清空 snackbar 定时器，避免 pending timer 断言
      await tester.pump(const Duration(seconds: 6));
      await tester.pumpAndSettle();
    });
  });

  // ==========================================================================
  // 四、安全存储不可用时的降级
  // ==========================================================================

  group('Stage 6-6: flutter_secure_storage 不可用时的降级', () {
    test('13. 所有读写都抛异常时，StorageService 不抛错并降级（Web 非安全上下文）', () async {
      // Web 在非安全上下文（HTTP 且非 localhost）下 flutter_secure_storage 会整体抛错。
      // 若异常逃逸，所有需要 Token 的请求都会失败，整个 App 不可用。
      final storage = StorageService();
      await storage.init(storage: _ThrowingSecureStorage());

      // 读：降级为 null（等价"本地没有凭据"，请求走未认证路径，由 401 流程兜底）
      expect(await storage.getToken(), isNull);
      expect(await storage.getRefreshToken(), isNull);
      expect(await storage.read('any_key'), isNull);

      // 写 / 删：不抛异常（内存态 Token 仍可用，本次会话功能不受影响）
      await storage.saveToken('token_value');
      await storage.saveRefreshToken('refresh_value');
      await storage.write('k', 'v');
      await storage.clearToken();
      await storage.clearRefreshToken();
      await storage.delete('k');
      await storage.clearAll();
    });
  });

}

/// 最小 HttpClientAdapter：直接返回给定响应，不产生真实网络请求。
class _MockAdapter implements HttpClientAdapter {
  _MockAdapter(this.handler);

  final Future<ResponseBody> Function(RequestOptions options) handler;

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<List<int>>? requestStream,
    Future<void>? cancelFuture,
  ) {
    return handler(options);
  }

  @override
  void close({bool force = false}) {}
}

/// 模拟"安全存储整体不可用"（Web 非安全上下文的真实表现）。
class _ThrowingSecureStorage extends FlutterSecureStorage {
  @override
  Future<String?> read({
    required String key,
    IOSOptions? iOptions,
    AndroidOptions? aOptions,
    LinuxOptions? lOptions,
    WebOptions? webOptions,
    MacOsOptions? mOptions,
    WindowsOptions? wOptions,
  }) async {
    throw PlatformException(
      code: 'SecureStorageUnavailable',
      message: 'secure storage is unavailable in this context',
    );
  }

  @override
  Future<void> write({
    required String key,
    required String? value,
    IOSOptions? iOptions,
    AndroidOptions? aOptions,
    LinuxOptions? lOptions,
    WebOptions? webOptions,
    MacOsOptions? mOptions,
    WindowsOptions? wOptions,
  }) async {
    throw PlatformException(code: 'SecureStorageUnavailable');
  }

  @override
  Future<void> delete({
    required String key,
    IOSOptions? iOptions,
    AndroidOptions? aOptions,
    LinuxOptions? lOptions,
    WebOptions? webOptions,
    MacOsOptions? mOptions,
    WindowsOptions? wOptions,
  }) async {
    throw PlatformException(code: 'SecureStorageUnavailable');
  }

  @override
  Future<Map<String, String>> readAll({
    IOSOptions? iOptions,
    AndroidOptions? aOptions,
    LinuxOptions? lOptions,
    WebOptions? webOptions,
    MacOsOptions? mOptions,
    WindowsOptions? wOptions,
  }) async {
    throw PlatformException(code: 'SecureStorageUnavailable');
  }

  @override
  Future<void> deleteAll({
    IOSOptions? iOptions,
    AndroidOptions? aOptions,
    LinuxOptions? lOptions,
    WebOptions? webOptions,
    MacOsOptions? mOptions,
    WindowsOptions? wOptions,
  }) async {
    throw PlatformException(code: 'SecureStorageUnavailable');
  }
}
