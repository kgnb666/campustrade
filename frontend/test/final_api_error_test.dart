import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:frontend/api/dio_client.dart';
import 'package:frontend/services/favorite_service.dart';
import 'package:frontend/services/goods_service.dart';
import 'package:frontend/services/history_service.dart';
import 'package:frontend/utils/api_error.dart';

/// 收尾批次：**"服务端 message 优先"只有一份实现**。
///
/// 此前 `GoodsService` / `FavoriteService` / `HistoryService` 各自写了一份
/// `_serverMessage(response, fallback)`，与 [api_error] 里的取文逻辑重复；
/// 现在统一为 [serverMessageOf] / [serverMessageOr]（[describeApiError] 也复用同一份解析）。
///
/// 本文件钉住两条不能退化的行为：
/// - 服务端返回了 message → 用户看到的就是服务端原文（业务提示是唯一文案来源）；
/// - 服务端没给 message（字段缺失/空白/响应体不是 Map）→ 回退到调用方给的中文兜底文案，
///   绝不把 `Response(data: ..., statusCode: ...)` 这种调试字符串或空串抛给用户。
void main() {
  Interceptor? mockInterceptor;

  /// 把 DioClient 的请求指向本地应答，避免真实网络。
  ///
  /// `responder` 可以抛 [DioException] 来模拟"非 2xx → Dio 抛异常"的真实错误通道
  /// （与仓库里其它 widget 测试的做法一致）。
  void installMockApi(Response<dynamic> Function(RequestOptions options) responder) {
    if (mockInterceptor != null) {
      DioClient().dio.interceptors.remove(mockInterceptor);
    }
    mockInterceptor = InterceptorsWrapper(
      onRequest: (options, handler) {
        try {
          return handler.resolve(responder(options));
        } on DioException catch (dioErr) {
          return handler.reject(dioErr);
        }
      },
    );
    DioClient().dio.interceptors.insert(0, mockInterceptor!);
  }

  tearDownAll(() {
    if (mockInterceptor != null) {
      DioClient().dio.interceptors.remove(mockInterceptor);
    }
  });

  Response<dynamic> jsonResponse(
    RequestOptions options, {
    required int statusCode,
    required Object? body,
  }) =>
      Response<dynamic>(
        requestOptions: options,
        statusCode: statusCode,
        data: body,
      );

  // ==========================================================================
  // 一、纯函数：取文与兜底
  // ==========================================================================

  group('serverMessageOf / serverMessageOr', () {
    test('1. 响应体里有 message → 返回服务端原文（去首尾空白）', () {
      expect(
        serverMessageOf({'code': 409, 'message': '  商品已被他人锁定  ', 'data': null}),
        '商品已被他人锁定',
      );
      final Response<dynamic> response = jsonResponse(
        RequestOptions(path: '/goods/list'),
        statusCode: 200,
        body: {'code': 409, 'message': '商品已被他人锁定', 'data': null},
      );
      expect(serverMessageOr(response, '商品列表加载失败'), '商品已被他人锁定');
    });

    test('2. 拿不到可用 message → serverMessageOf 返回 null，serverMessageOr 回退中文兜底', () {
      // 字段缺失 / 只有空白 / 不是 Map：三种都算"没有 message"
      expect(serverMessageOf({'code': 500, 'data': null}), isNull);
      expect(serverMessageOf({'code': 500, 'message': null}), isNull);
      expect(serverMessageOf({'code': 500, 'message': '   '}), isNull);
      expect(serverMessageOf('服务端返回了一段文本而不是 JSON'), isNull);
      expect(serverMessageOf(null), isNull);

      final Response<dynamic> noMessage = jsonResponse(
        RequestOptions(path: '/goods/list'),
        statusCode: 200,
        body: {'code': 500, 'message': '', 'data': null},
      );
      expect(serverMessageOr(noMessage, '商品列表加载失败'), '商品列表加载失败');
    });
  });

  group('describeApiError：错误通道同样"服务端 message 优先"', () {
    test('3. DioException 携带响应体 message → 用服务端原文，而不是状态码兜底文案', () {
      final RequestOptions options = RequestOptions(path: '/goods/1');
      final DioException error = DioException(
        requestOptions: options,
        type: DioExceptionType.badResponse,
        response: jsonResponse(options,
            statusCode: 409,
            body: {'code': 409, 'message': '该商品已被他人下单锁定', 'data': null}),
      );
      expect(describeApiError(error, fallback: '下单失败'), '该商品已被他人下单锁定');
    });

    test('4. DioException 没有响应体 message → 回退兜底文案（状态码语义明确时带可读解释）', () {
      final RequestOptions options = RequestOptions(path: '/goods/1');
      final DioException conflict = DioException(
        requestOptions: options,
        type: DioExceptionType.badResponse,
        response: jsonResponse(options, statusCode: 409, body: {'code': 409}),
      );
      expect(describeApiError(conflict, fallback: '下单失败'),
          '下单失败（与当前状态冲突，可能已被他人占用，请刷新后重试）');

      final DioException notFound = DioException(
        requestOptions: options,
        type: DioExceptionType.badResponse,
        response: jsonResponse(options, statusCode: 404, body: {'code': 404}),
      );
      expect(describeApiError(notFound, fallback: '商品详情加载失败'), '商品详情加载失败（HTTP 404）');
    });
  });

  // ==========================================================================
  // 二、端到端：三个服务类都走同一份实现（改造后不再各写一份 _serverMessage）
  // ==========================================================================

  group('服务层端到端：服务端 message 优先 / 缺省回退各自的中文兜底', () {
    test('5. GoodsService：HTTP 200 + 业务码非 200 时，message 优先、缺省回退"商品列表加载失败"', () async {
      installMockApi((options) => jsonResponse(options,
          statusCode: 200,
          body: {'code': 500, 'message': '服务端：搜索服务暂时不可用', 'data': null}));
      Object? preferred;
      try {
        await GoodsService().getGoodsList();
      } catch (e) {
        preferred = e;
      }
      expect(preferred, isA<ApiException>());
      expect((preferred as ApiException).message, '服务端：搜索服务暂时不可用');

      installMockApi((options) => jsonResponse(options,
          statusCode: 200, body: {'code': 500, 'data': null}));
      Object? fallback;
      try {
        await GoodsService().getGoodsList();
      } catch (e) {
        fallback = e;
      }
      expect(fallback, isA<ApiException>());
      expect((fallback as ApiException).message, '商品列表加载失败');
    });

    test('6. FavoriteService：缺省 message 时回退"收藏失败"', () async {
      installMockApi((options) => jsonResponse(options,
          statusCode: 200, body: {'code': 409, 'message': '已经收藏过该商品', 'data': null}));
      Object? preferred;
      try {
        await FavoriteService().addFavorite('1');
      } catch (e) {
        preferred = e;
      }
      expect((preferred as ApiException).message, '已经收藏过该商品');

      installMockApi((options) => jsonResponse(options,
          statusCode: 200, body: {'code': 500, 'data': null}));
      Object? fallback;
      try {
        await FavoriteService().addFavorite('1');
      } catch (e) {
        fallback = e;
      }
      expect((fallback as ApiException).message, '收藏失败');
    });

    test('7. HistoryService：缺省 message 时回退"浏览足迹加载失败"', () async {
      installMockApi((options) => jsonResponse(options,
          statusCode: 200, body: {'code': 500, 'data': null}));
      Object? fallback;
      try {
        await HistoryService().getHistoryList();
      } catch (e) {
        fallback = e;
      }
      expect((fallback as ApiException).message, '浏览足迹加载失败');
    });

    test('8. 真实错误通道（Dio 非 2xx 抛异常）下服务端 message 依然优先', () async {
      installMockApi((options) {
        final Response<dynamic> response = jsonResponse(options,
            statusCode: 429,
            body: {'code': 429, 'message': '操作过于频繁，请稍后再试', 'data': null});
        throw DioException(
          requestOptions: options,
          type: DioExceptionType.badResponse,
          response: response,
        );
      });
      Object? error;
      try {
        await GoodsService().getGoodsDetail('1');
      } catch (e) {
        error = e;
      }
      expect(error, isA<ApiException>());
      expect((error as ApiException).message, '操作过于频繁，请稍后再试');
      expect(error.statusCode, 429, reason: '状态码必须保留，供上层做 401/404 分支判断');
    });
  });
}
