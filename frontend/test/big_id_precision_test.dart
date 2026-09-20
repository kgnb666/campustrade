import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:frontend/api/dio_client.dart';
import 'package:frontend/api/order_api.dart';
import 'package:frontend/models/goods_model.dart';
import 'package:frontend/services/favorite_service.dart';
import 'package:frontend/services/goods_service.dart';

/// 回归测试：与"后端 Long 型数值以字符串下发"相关的契约。
///
/// 后端 JacksonConfig 把所有 Long/long/BigInteger 序列化为字符串，涉及两类：
///   1. 雪花 ID（如商品 ID）—— 字符串进入请求路径必须原样保留；
///   2. 分页计数 total/pages/current/size（MyBatis-Plus 的 long）—— 解析时必须容错。
/// 历史上正因为前端对二者直接 `as int`，出现过"点击发布商品没反应"（响应解析抛异常被吞）、
/// "商品列表为空"等问题。
///
/// ⚠️ 环境边界：本文件跑在 Dart VM 上，VM 的 int 是 64 位精确的，
/// 因此"把 19 位 ID 转成 int 再转回字符串"这类**精度丢失**在 VM 上不会复现
/// —— 这正是该 bug 能躲过原有测试、只在浏览器里暴露的原因。
/// 本文件锁住的是类型与解析契约（回退成 int 会导致下游编译失败或断言失败）。
void main() {
  const bigId = '2101229014818570241';
  final capturedPaths = <String>[];
  Interceptor? mockInterceptor;
  late Response Function(RequestOptions) responder;

  setUp(() {
    capturedPaths.clear();
    responder = (options) => Response(
          requestOptions: options,
          statusCode: 200,
          data: {'code': 200, 'message': 'ok', 'data': null},
        );
    if (mockInterceptor != null) {
      DioClient().dio.interceptors.remove(mockInterceptor);
    }
    mockInterceptor = InterceptorsWrapper(
      onRequest: (options, handler) {
        capturedPaths.add(options.path);
        handler.resolve(responder(options));
      },
    );
    DioClient().dio.interceptors.insert(0, mockInterceptor!);
  });

  tearDown(() {
    if (mockInterceptor != null) {
      DioClient().dio.interceptors.remove(mockInterceptor);
      mockInterceptor = null;
    }
  });

  test('商品/收藏/订单详情请求路径均保留 19 位 ID 原样', () async {
    await GoodsService().getGoodsDetail(bigId);
    final favoriteService = FavoriteService();
    await favoriteService.addFavorite(bigId);
    await favoriteService.removeFavorite(bigId);
    await favoriteService.checkFavorite(bigId);
    await OrderApi().getOrderDetail(bigId);

    expect(capturedPaths, contains('/goods/$bigId'));
    expect(capturedPaths, contains('/favorite/$bigId'));
    expect(capturedPaths, contains('/favorite/check/$bigId'));
    expect(capturedPaths, contains('/orders/$bigId'));
    // 确认没有精度截断后的形态混进请求
    expect(capturedPaths.where((p) => p.contains('2101229014818570200')), isEmpty);
  });

  test('模型解析后 ID 原样透传，不经过 int', () {
    final goods = GoodsItemModel.fromJson(<String, dynamic>{
      'id': bigId,
      'sellerId': bigId,
      'schoolId': '1800000000000000001',
      'schoolName': '广西民族师范学院',
      'categoryId': '900000000000000001',
      'categoryName': '教材书籍',
      'title': '高等数学',
      'price': 25.5,
      'conditionLevel': '9成新',
      'status': 'ON_SALE',
    });

    expect(goods.id, bigId);
    expect(goods.sellerId, bigId);
    expect(goods.categoryId, '900000000000000001');

    // 数字形态与新字符串形态都能解析，但结果都是精确字符串
    final numeric = GoodsItemModel.fromJson(<String, dynamic>{
      'id': 100,
      'sellerId': 100,
      'schoolId': 1,
      'schoolName': '测试高校',
      'categoryId': 2,
      'categoryName': '教材书籍',
      'title': '高数',
      'price': 10.0,
      'conditionLevel': '9成新',
      'status': 'ON_SALE',
    });
    expect(numeric.id, '100');
    expect(numeric.categoryId, '2');
  });

  test('发布商品返回字符串 ID 时不再抛异常（回归：点击"确认发布商品"没反应）', () async {
    responder = (options) => Response(
          requestOptions: options,
          statusCode: 200,
          // 后端真实形态：新建商品的 ID 以字符串返回
          data: {'code': 200, 'message': '发布成功', 'data': bigId},
        );

    final newGoodsId = await GoodsService().createGoods(<String, dynamic>{
      'title': '手机',
      'categoryId': '900000000000000001',
      'price': 1000.0,
    });

    expect(newGoodsId, bigId);
  });

  test('列表分页字段为字符串时仍解析为 int（回归：首页商品列表为空）', () async {
    responder = (options) => Response(
          requestOptions: options,
          statusCode: 200,
          data: {
            'code': 200,
            'message': 'ok',
            // 后端真实形态：total/pages/current/size 是 long → 字符串
            'data': {
              'records': [
                {
                  'id': '1024',
                  'sellerId': '2101229014818570241',
                  'schoolId': '1',
                  'schoolName': '广西民族师范学院',
                  'categoryId': '900000000000000001',
                  'categoryName': '数码',
                  'title': '手机',
                  'price': 1000.0,
                  'conditionLevel': '95新',
                  'status': 'ON_SALE',
                }
              ],
              'total': '383',
              'pages': '39',
              'current': '1',
              'size': '10',
            },
          },
        );

    final res = await GoodsService().getGoodsList();
    final items = res['items'] as List<GoodsItemModel>;

    expect(items.length, 1);
    expect(items.first.id, '1024');
    // 这三个字段此前会让 `as int` 抛异常，进而整段被 catch 吞掉、列表显示为空
    expect(res['total'], 383);
    expect(res['pages'], 39);
    expect(res['current'], 1);
  });
}
