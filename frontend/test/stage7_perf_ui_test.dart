import 'package:flutter/material.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:frontend/controllers/auth_controller.dart';
import 'package:frontend/controllers/goods_controller.dart';
import 'package:frontend/models/goods_model.dart';
import 'package:frontend/pages/goods/goods_list_page.dart';
import 'package:frontend/services/storage_service.dart';
import 'package:frontend/widgets/goods_thumbnail.dart';
import 'package:get/get.dart' hide Response;

/// 阶段 7：前端性能与体验回归测试
///
/// 覆盖两项"肉眼可见但此前没有任何自动断言"的行为：
/// 1. [GoodsThumbnail] 必须按显示尺寸解码（cacheWidth/cacheHeight = 逻辑尺寸 × DPR），
///    否则列表缩略图会以原图分辨率进入图片缓存（内存爆掉的主因）；空 URL 时不得发起请求；
/// 2. 集市列表在"已有数据时刷新"不得整屏 loading（否则下拉刷新/发布后回刷会整屏闪一下），
///    只有首次加载（列表为空）才允许整屏菊花。
void main() {
  Map<String, dynamic> goodsRecord() => {
        'id': '88001',
        'sellerId': '77001',
        'schoolId': '1',
        'schoolName': '清华大学',
        'categoryId': '1',
        'categoryName': '电子产品',
        'title': '阶段7缩略图夹具商品',
        'coverImage': 'http://127.0.0.1:9000/campustrade/goods/fixture.jpg',
        'price': 199.0,
        'conditionLevel': '95新',
        'status': 'ON_SALE',
        'viewCount': 3,
        'createdTime': '2026-09-20T10:00:00',
      };

  setUp(() async {
    Get.reset();
    FlutterSecureStorage.setMockInitialValues({});
    await Get.putAsync(() => StorageService().init());
    Get.put(AuthController());
  });

  tearDown(() {
    Get.reset();
  });

  group('阶段7 图片解码尺寸', () {
    testWidgets('1. 缩略图按显示尺寸 × DPR 解码，避免原图分辨率进入图片缓存', (tester) async {
      final double dpr = tester.view.devicePixelRatio;

      await tester.pumpWidget(MaterialApp(
        home: Center(
          child: SizedBox(
            width: 90,
            height: 90,
            child: GoodsThumbnail(
              imageUrl: 'http://127.0.0.1:9000/campustrade/goods/fixture.jpg',
              width: 90,
              height: 90,
            ),
          ),
        ),
      ));
      await tester.pump();

      final Image image = tester.widget<Image>(find.byType(Image));
      // Image 会把 cacheWidth/cacheHeight 包成 ResizeImage，因此断言解码尺寸要看 imageProvider
      expect(image.image, isA<ResizeImage>(),
          reason: '必须指定 cacheWidth/cacheHeight（否则按原图分辨率解码）');
      final ResizeImage resized = image.image as ResizeImage;
      expect(resized.width, equals((90 * dpr).ceil()),
          reason: '解码宽度必须等于显示逻辑宽度 × devicePixelRatio');
      expect(resized.height, equals((90 * dpr).ceil()),
          reason: '解码高度必须等于显示逻辑高度 × devicePixelRatio');
      expect(find.byType(Image), findsOneWidget);
      expect(image.fit, equals(BoxFit.cover));
    });

    testWidgets('2. 无图（空 URL）时只渲染占位块，不发起任何图片请求', (tester) async {
      await tester.pumpWidget(const MaterialApp(
        home: Center(
          child: SizedBox(
            width: 76,
            height: 76,
            child: GoodsThumbnail(imageUrl: null, width: 76, height: 76),
          ),
        ),
      ));
      await tester.pump();

      expect(find.byType(Image), findsNothing, reason: '空 URL 不得构造 Image（否则会白跑一次请求）');
      expect(find.byIcon(Icons.image_outlined), findsOneWidget, reason: '必须展示占位图标');
    });
  });

  group('阶段7 列表刷新体验', () {
    testWidgets('3. 已有数据时刷新不整屏 loading，列表内容始终可见', (tester) async {
      // 只用内存状态驱动页面：widget 测试跑在 FakeAsync 时钟里，
      // 真实网络/定时器不会自动前进，因此这里刻意不发起任何请求。
      final controller = Get.put(GoodsController());
      controller.goodsList.assignAll([GoodsItemModel.fromJson(goodsRecord())]);
      controller.isLoading.value = true; // 模拟"下拉刷新 / 发布后回刷 / 切分类"中的加载态

      await tester.pumpWidget(const GetMaterialApp(home: GoodsListPage()));
      await tester.pump();

      expect(find.text('阶段7缩略图夹具商品'), findsOneWidget,
          reason: '刷新期间必须保留既有列表（不得整屏替换成菊花）');
      expect(find.byType(GoodsThumbnail), findsWidgets,
          reason: '刷新期间缩略图必须仍然在树上');

      // 收尾：让 onInit 触发的在途请求（分类/热搜/搜索历史）走完，
      // 否则测试结束时会因 pending timer 报错（请求被测试用 HttpClient 立即以 400 结束）。
      for (int i = 0; i < 5; i++) {
        await tester.pump(const Duration(milliseconds: 50));
      }
    });
  });
}
