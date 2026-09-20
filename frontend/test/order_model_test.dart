import 'package:flutter_test/flutter_test.dart';
import 'package:frontend/models/order.dart';

void main() {
  group('OrderStatus Enum Tests', () {
    test('OrderStatus fromCode should accurately map known enum codes', () {
      expect(OrderStatus.fromCode('WAIT_SELLER_CONFIRM'),
          equals(OrderStatus.waitSellerConfirm));
      expect(OrderStatus.fromCode('WAIT_MEET'), equals(OrderStatus.waitMeet));
      expect(OrderStatus.fromCode('COMPLETED'), equals(OrderStatus.completed));
      expect(OrderStatus.fromCode('CANCELLED'), equals(OrderStatus.cancelled));
    });

    test('OrderStatus fromCode should be case-insensitive and handle whitespace',
        () {
      expect(OrderStatus.fromCode(' wait_seller_confirm '),
          equals(OrderStatus.waitSellerConfirm));
      expect(OrderStatus.fromCode('wait_meet'), equals(OrderStatus.waitMeet));
      expect(OrderStatus.fromCode('Completed'), equals(OrderStatus.completed));
      expect(OrderStatus.fromCode('Cancelled'), equals(OrderStatus.cancelled));
    });

    test('OrderStatus fromCode falls back to unknown (never to a known status)',
        () {
      // 未知/缺失状态绝不能回落成"待卖家确认"：那会让 UI 显示错误文案，
      // 还会给出「确认接单」「取消订单」等可点击的卖家操作。
      expect(OrderStatus.fromCode(null), equals(OrderStatus.unknown));
      expect(OrderStatus.fromCode(''), equals(OrderStatus.unknown));
      expect(OrderStatus.fromCode('   '), equals(OrderStatus.unknown));
      expect(OrderStatus.fromCode('UNKNOWN_STATUS'),
          equals(OrderStatus.unknown));
      expect(OrderStatus.fromCode('REFUNDING'), equals(OrderStatus.unknown));
      expect(OrderStatus.unknown.isKnown, isFalse);
      expect(OrderStatus.unknown.isReadOnly, isTrue);
      expect(OrderStatus.waitSellerConfirm.isKnown, isTrue);
      expect(OrderStatus.waitSellerConfirm.isReadOnly, isFalse);
    });

    test('OrderVO exposes unknown status read-only with the server raw code', () {
      final order = OrderVO.fromJson({
        'id': 1,
        'orderNo': 'ORD_UNKNOWN',
        'goodsId': 2,
        'goodsTitleSnapshot': '未知状态订单',
        'goodsPriceSnapshot': 10.0,
        'orderStatus': 'REFUNDING',
        'statusDesc': '退款处理中',
      });

      expect(order.orderStatus, equals(OrderStatus.unknown));
      expect(order.isKnownStatus, isFalse);
      expect(order.isUnknownStatus, isTrue);
      expect(order.statusCode, equals('REFUNDING'));
      expect(order.statusText, equals('退款处理中'));

      // 未知状态不提供任何操作入口
      expect(order.canConfirm, isFalse);
      expect(order.canCancel, isFalse);
      expect(order.canComplete, isFalse);
      expect(order.isWaitSellerConfirm, isFalse);
      expect(order.isWaitMeet, isFalse);

      // 序列化必须保留服务端原始状态码，不能被退化成空串
      expect(order.toJson()['orderStatus'], equals('REFUNDING'));
      expect(order.copyWith().statusCode, equals('REFUNDING'));
    });

    test('OrderVO falls back to the raw status code when no description is sent',
        () {
      final order = OrderVO.fromJson({
        'id': 1,
        'orderNo': 'ORD_RAW',
        'goodsId': 2,
        'goodsTitleSnapshot': '无描述未知订单',
        'goodsPriceSnapshot': 10.0,
        'orderStatus': 'DISPUTED',
      });

      expect(order.orderStatus, equals(OrderStatus.unknown));
      expect(order.statusText, equals('DISPUTED'),
          reason: '未知状态没有服务端描述时，展示原文而不是"未知状态"这类占位词');
    });

    test('OrderStatus labels should match business specifications', () {
      expect(OrderStatus.waitSellerConfirm.label, equals('待卖家确认'));
      expect(OrderStatus.waitMeet.label, equals('待面交'));
      expect(OrderStatus.completed.label, equals('已完成'));
      expect(OrderStatus.cancelled.label, equals('已取消'));
    });
  });

  group('OrderUserInfo Tests', () {
    test('OrderUserInfo.fromJson should parse valid map correctly', () {
      final json = {
        'id': 1001,
        'username': 'buyer_test',
        'nickname': '买家昵称',
        'avatar': 'https://example.com/avatar.png',
      };
      final user = OrderUserInfo.fromJson(json);

      expect(user.id, equals('1001'));
      expect(user.username, equals('buyer_test'));
      expect(user.nickname, equals('买家昵称'));
      expect(user.avatar, equals('https://example.com/avatar.png'));
    });

    test('OrderUserInfo.fromJson should handle string id and null optional fields',
        () {
      final json = {
        'id': '2002',
        'username': 'seller_test',
      };
      final user = OrderUserInfo.fromJson(json);

      expect(user.id, equals('2002'));
      expect(user.username, equals('seller_test'));
      expect(user.nickname, isNull);
      expect(user.avatar, isNull);
    });

    test('OrderUserInfo.toJson should serialize correctly', () {
      final user = OrderUserInfo(
        id: '3003',
        username: 'user3003',
        nickname: '昵称',
      );
      final json = user.toJson();

      expect(json['id'], equals('3003'));
      expect(json['username'], equals('user3003'));
      expect(json['nickname'], equals('昵称'));
      expect(json.containsKey('avatar'), isFalse);
    });
  });

  group('OrderVO Model Tests', () {
    test('OrderVO.fromJson should parse full backend VO correctly', () {
      final json = {
        'id': 100,
        'orderNo': 'ORD202609171200001234',
        'goodsId': 201,
        'goodsTitleSnapshot': '高等数学第七版上册',
        'goodsPriceSnapshot': 25.50,
        'goodsImageSnapshot': 'https://example.com/math.jpg',
        'meetLocation': '学生活动中心南门',
        'buyerMessage': '麻烦带笔迹，谢谢！',
        'sellerReply': null,
        'buyerId': 1001,
        'buyerUsername': 'buyer_stu',
        'buyerNickname': '张三同学',
        'buyerAvatar': 'https://example.com/b.png',
        'sellerId': 1002,
        'sellerUsername': 'seller_stu',
        'sellerNickname': '李四同学',
        'sellerAvatar': 'https://example.com/s.png',
        'buyer': {
          'id': 1001,
          'username': 'buyer_stu',
          'nickname': '张三同学',
          'avatar': 'https://example.com/b.png',
        },
        'seller': {
          'id': 1002,
          'username': 'seller_stu',
          'nickname': '李四同学',
          'avatar': 'https://example.com/s.png',
        },
        'orderStatus': 'WAIT_SELLER_CONFIRM',
        'statusDesc': '待卖家确认',
        'cancelReason': null,
        'cancelledBy': null,
        'confirmedTime': null,
        'completedTime': null,
        'cancelledTime': null,
        'createdTime': '2026-09-17 12:00:00',
        'updatedTime': '2026-09-17 12:00:00',
      };

      final order = OrderVO.fromJson(json);

      expect(order.id, equals('100'));
      expect(order.orderNo, equals('ORD202609171200001234'));
      expect(order.goodsId, equals('201'));
      expect(order.goodsTitleSnapshot, equals('高等数学第七版上册'));
      expect(order.goodsPriceSnapshot, equals(25.50));
      expect(order.goodsImageSnapshot, equals('https://example.com/math.jpg'));
      expect(order.meetLocation, equals('学生活动中心南门'));
      expect(order.buyerMessage, equals('麻烦带笔迹，谢谢！'));
      expect(order.orderStatus, equals(OrderStatus.waitSellerConfirm));
      expect(order.statusDescription, equals('待卖家确认'));
      expect(order.buyer?.id, equals('1001'));
      expect(order.seller?.id, equals('1002'));
      expect(order.isWaitSellerConfirm, isTrue);
      expect(order.canConfirm, isTrue);
      expect(order.canCancel, isTrue);
      expect(order.canComplete, isFalse);
    });

    test('OrderVO should support flat buyer/seller info if nested objects omitted',
        () {
      final json = {
        'id': 101,
        'orderNo': 'ORD101',
        'goodsId': 202,
        'goodsTitleSnapshot': '二手教材',
        'goodsPriceSnapshot': '15.00',
        'buyerId': 501,
        'buyerUsername': 'b501',
        'buyerNickname': '五零一',
        'sellerId': 601,
        'sellerUsername': 's601',
        'orderStatus': 'WAIT_MEET',
        'statusDescription': '待面交',
      };

      final order = OrderVO.fromJson(json);

      expect(order.buyer?.id, equals('501'));
      expect(order.buyer?.nickname, equals('五零一'));
      expect(order.seller?.id, equals('601'));
      expect(order.isWaitMeet, isTrue);
      expect(order.canConfirm, isFalse);
      expect(order.canCancel, isTrue);
      expect(order.canComplete, isTrue);
    });

    test('OrderVO state predicates for COMPLETED and CANCELLED', () {
      final completedOrder = OrderVO(
        id: '1',
        orderNo: 'ORD_COMP',
        goodsId: '10',
        goodsTitleSnapshot: '已完成商品',
        goodsPriceSnapshot: 99.0,
        orderStatus: OrderStatus.completed,
        statusDescription: '已完成',
      );
      expect(completedOrder.isCompleted, isTrue);
      expect(completedOrder.canCancel, isFalse);
      expect(completedOrder.canConfirm, isFalse);
      expect(completedOrder.canComplete, isFalse);

      final cancelledOrder = OrderVO(
        id: '2',
        orderNo: 'ORD_CANC',
        goodsId: '10',
        goodsTitleSnapshot: '已取消商品',
        goodsPriceSnapshot: 99.0,
        orderStatus: OrderStatus.cancelled,
        statusDescription: '已取消',
        cancelReason: '买家不想买了',
      );
      expect(cancelledOrder.isCancelled, isTrue);
      expect(cancelledOrder.canCancel, isFalse);
      expect(cancelledOrder.canConfirm, isFalse);
      expect(cancelledOrder.canComplete, isFalse);
      expect(cancelledOrder.cancelReason, equals('买家不想买了'));
    });

    test('OrderVO.copyWith should create new instance with updated properties',
        () {
      final original = OrderVO(
        id: '1',
        orderNo: 'ORD_001',
        goodsId: '10',
        goodsTitleSnapshot: '测试商品',
        goodsPriceSnapshot: 50.0,
        orderStatus: OrderStatus.waitSellerConfirm,
        statusDescription: '待卖家确认',
      );

      final confirmed = original.copyWith(
        orderStatus: OrderStatus.waitMeet,
        statusDescription: '待面交',
        confirmedTime: '2026-09-17 12:30:00',
      );

      expect(confirmed.id, equals('1'));
      expect(confirmed.orderStatus, equals(OrderStatus.waitMeet));
      expect(confirmed.statusDescription, equals('待面交'));
      expect(confirmed.confirmedTime, equals('2026-09-17 12:30:00'));
      expect(original.orderStatus, equals(OrderStatus.waitSellerConfirm));
    });

    test('OrderVO.toJson should serialize accurately', () {
      final order = OrderVO(
        id: '1',
        orderNo: 'ORD_001',
        goodsId: '10',
        goodsTitleSnapshot: '测试商品',
        goodsPriceSnapshot: 50.0,
        orderStatus: OrderStatus.waitMeet,
        statusDescription: '待面交',
      );

      final json = order.toJson();
      expect(json['id'], equals('1'));
      expect(json['orderNo'], equals('ORD_001'));
      expect(json['orderStatus'], equals('WAIT_MEET'));
      expect(json['statusDescription'], equals('待面交'));
      expect(json['goodsPriceSnapshot'], equals(50.0));
    });
  });

  group('OrderPageResult Tests', () {
    test('OrderPageResult.fromJson should parse records and pagination metadata',
        () {
      final json = {
        'records': [
          {
            'id': 1,
            'orderNo': 'ORD1',
            'goodsId': 101,
            'goodsTitleSnapshot': '商品1',
            'goodsPriceSnapshot': 12.0,
            'orderStatus': 'WAIT_MEET',
            'statusDesc': '待面交',
          },
          {
            'id': 2,
            'orderNo': 'ORD2',
            'goodsId': 102,
            'goodsTitleSnapshot': '商品2',
            'goodsPriceSnapshot': 34.0,
            'orderStatus': 'COMPLETED',
            'statusDesc': '已完成',
          }
        ],
        'total': 2,
        'current': 1,
        'size': 10,
        'pages': 1,
      };

      final page = OrderPageResult.fromJson(json);

      expect(page.total, equals(2));
      expect(page.current, equals(1));
      expect(page.size, equals(10));
      expect(page.pages, equals(1));
      expect(page.records.length, equals(2));
      expect(page.records[0].orderNo, equals('ORD1'));
      expect(page.records[1].orderStatus, equals(OrderStatus.completed));
    });
  });

  group('CreateOrderRequest & CancelOrderRequest Tests', () {
    test('CreateOrderRequest.toJson should produce expected payload', () {
      final req = CreateOrderRequest(
        goodsId: '88',
        meetLocation: '宿舍楼下',
        buyerMessage: '明天下午交货',
      );
      final json = req.toJson();

      expect(json['goodsId'], equals('88'));
      expect(json['meetLocation'], equals('宿舍楼下'));
      expect(json['buyerMessage'], equals('明天下午交货'));
    });

    test('CancelOrderRequest.toJson should produce expected payload', () {
      final req = CancelOrderRequest(cancelReason: '不想买了');
      final json = req.toJson();

      expect(json['cancelReason'], equals('不想买了'));
    });
  });
}
