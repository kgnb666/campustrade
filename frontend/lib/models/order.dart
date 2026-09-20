/// 交易订单领域模型与枚举定义
/// 严格映射后端 OrderVO 与数据库 schema，禁止在业务逻辑中散落使用未经类型校验的状态字符串
library;

/// 订单生命周期状态枚举
///
/// 与后端 `OrderStatus`（V10 CHECK 约束：WAIT_SELLER_CONFIRM / WAIT_MEET /
/// COMPLETED / CANCELLED）一一对应。
enum OrderStatus {
  /// 待卖家确认接单
  waitSellerConfirm('WAIT_SELLER_CONFIRM', '待卖家确认'),

  /// 待面交交付 (双方已确认)
  waitMeet('WAIT_MEET', '待面交'),

  /// 交易完成
  completed('COMPLETED', '已完成'),

  /// 交易取消
  cancelled('CANCELLED', '已取消'),

  /// 后端新增/前端尚未识别的状态
  ///
  /// 刻意<b>不</b>回落成某个已知状态：把未知状态显示成「待卖家确认」不仅文案是错的，
  /// 还会因为 isWaitSellerConfirm 为真而给出「确认接单」「取消订单」等可点击操作，
  /// 让用户在服务端根本不接受该流转的订单上误操作。未知状态一律只读展示服务端原文。
  unknown('', '未知状态');

  final String code;
  final String label;

  const OrderStatus(this.code, this.label);

  /// 是否为前端已识别的状态。
  bool get isKnown => this != OrderStatus.unknown;

  /// 是否只读（未知状态不提供任何操作入口）。
  bool get isReadOnly => !isKnown;

  /// 根据字符串编码转换为枚举。
  ///
  /// 已知编码（大小写不敏感、容忍首尾空白）→ 对应枚举；
  /// null / 空串 / 未知编码 → [OrderStatus.unknown]（只读原文展示，不再静默回落）。
  static OrderStatus fromCode(String? code) {
    if (code == null) return OrderStatus.unknown;
    final upper = code.trim().toUpperCase();
    if (upper.isEmpty) return OrderStatus.unknown;
    for (final status in OrderStatus.values) {
      if (status.isKnown && status.code == upper) {
        return status;
      }
    }
    return OrderStatus.unknown;
  }
}

/// 订单买家/卖家简要脱敏信息模型
///
/// ID 统一用 String 承载：后端 Long 型雪花 ID 以字符串下发，Web 端用 int 会丢精度（请求会打到错误 ID）。
class OrderUserInfo {
  final String id;
  final String username;
  final String? nickname;
  final String? avatar;

  OrderUserInfo({
    required this.id,
    required this.username,
    this.nickname,
    this.avatar,
  });

  factory OrderUserInfo.fromJson(Map<String, dynamic> json) {
    return OrderUserInfo(
      id: json['id']?.toString() ?? '',
      username: json['username']?.toString() ?? '',
      nickname: json['nickname']?.toString(),
      avatar: json['avatar']?.toString(),
    );
  }

  Map<String, dynamic> toJson() => {
        'id': id,
        'username': username,
        if (nickname != null) 'nickname': nickname,
        if (avatar != null) 'avatar': avatar,
      };
}

/// 订单视图对象模型 (前端统一使用的领域模型)
class OrderVO {
  final String id;
  final String orderNo;
  final OrderUserInfo? buyer;
  final OrderUserInfo? seller;
  final String? buyerId;
  final String? sellerId;
  final String goodsId;
  final String goodsTitleSnapshot;
  final double goodsPriceSnapshot;
  final String? goodsImageSnapshot;
  final String? meetLocation;
  final String? buyerMessage;
  final String? sellerReply;
  final OrderStatus orderStatus;

  /// 服务端下发的原始状态码（未知状态时用于原文展示，保证提示不丢信息）。
  final String statusCode;

  final String statusDescription;
  final String? cancelReason;
  final String? cancelledBy;
  final String? createdTime;
  final String? confirmedTime;
  final String? completedTime;
  final String? cancelledTime;
  final String? updatedTime;

  OrderVO({
    required this.id,
    required this.orderNo,
    this.buyer,
    this.seller,
    this.buyerId,
    this.sellerId,
    required this.goodsId,
    required this.goodsTitleSnapshot,
    required this.goodsPriceSnapshot,
    this.goodsImageSnapshot,
    this.meetLocation,
    this.buyerMessage,
    this.sellerReply,
    required this.orderStatus,
    String? statusCode,
    required this.statusDescription,
    this.cancelReason,
    this.cancelledBy,
    this.createdTime,
    this.confirmedTime,
    this.completedTime,
    this.cancelledTime,
    this.updatedTime,
  }) : statusCode = statusCode ?? orderStatus.code;

  /// 状态快捷判断属性（未知状态对所有已知判定恒为 false）
  bool get isKnownStatus => orderStatus.isKnown;
  bool get isUnknownStatus => orderStatus.isReadOnly;
  bool get isWaitSellerConfirm => orderStatus == OrderStatus.waitSellerConfirm;
  bool get isWaitMeet => orderStatus == OrderStatus.waitMeet;
  bool get isCompleted => orderStatus == OrderStatus.completed;
  bool get isCancelled => orderStatus == OrderStatus.cancelled;

  /// 操作权限/流转可用性。
  ///
  /// 未知状态一律不可操作（三个 can* 全为 false），确保 UI 不会在无法识别的订单上渲染按钮。
  bool get canCancel => isKnownStatus && (isWaitSellerConfirm || isWaitMeet);
  bool get canConfirm => isKnownStatus && isWaitSellerConfirm;
  bool get canComplete => isKnownStatus && isWaitMeet;

  /// 用于展示的状态文案。
  ///
  /// 优先使用服务端下发的 `statusDescription`；缺失或为空时，已知状态用枚举 label，
  /// 未知状态<b>直接展示服务端原文</b>（而不是伪装成某个已知状态的文案）。
  String get statusText {
    final described = statusDescription.trim();
    if (described.isNotEmpty) {
      return described;
    }
    return defaultStatusLabel(orderStatus, statusCode);
  }

  /// 状态文案兜底：已知状态用枚举 label，未知状态用服务端原始状态码。
  static String defaultStatusLabel(OrderStatus status, String rawStatusCode) {
    if (status.isKnown) {
      return status.label;
    }
    final raw = rawStatusCode.trim();
    return raw.isEmpty ? status.label : raw;
  }

  factory OrderVO.fromJson(Map<String, dynamic> json) {
    final rawStatusCode = json['orderStatus']?.toString() ?? '';
    final status = OrderStatus.fromCode(rawStatusCode);

    // 解析买家信息 (优先嵌套 buyer 对象，次选平铺字段)
    OrderUserInfo? buyerObj;
    if (json['buyer'] is Map<String, dynamic>) {
      buyerObj =
          OrderUserInfo.fromJson(json['buyer'] as Map<String, dynamic>);
    } else if (json['buyerId'] != null) {
      buyerObj = OrderUserInfo(
        id: json['buyerId'].toString(),
        username: json['buyerUsername']?.toString() ?? '',
        nickname: json['buyerNickname']?.toString(),
        avatar: json['buyerAvatar']?.toString(),
      );
    }

    // 解析卖家信息 (优先嵌套 seller 对象，次选平铺字段)
    OrderUserInfo? sellerObj;
    if (json['seller'] is Map<String, dynamic>) {
      sellerObj =
          OrderUserInfo.fromJson(json['seller'] as Map<String, dynamic>);
    } else if (json['sellerId'] != null) {
      sellerObj = OrderUserInfo(
        id: json['sellerId'].toString(),
        username: json['sellerUsername']?.toString() ?? '',
        nickname: json['sellerNickname']?.toString(),
        avatar: json['sellerAvatar']?.toString(),
      );
    }

    final buyerIdVal = json['buyerId'] != null
        ? json['buyerId'].toString()
        : buyerObj?.id;

    final sellerIdVal = json['sellerId'] != null
        ? json['sellerId'].toString()
        : sellerObj?.id;

    return OrderVO(
      id: json['id']?.toString() ?? '',
      orderNo: json['orderNo']?.toString() ?? '',
      buyer: buyerObj,
      seller: sellerObj,
      buyerId: buyerIdVal,
      sellerId: sellerIdVal,
      goodsId: json['goodsId']?.toString() ?? '',
      goodsTitleSnapshot: json['goodsTitleSnapshot']?.toString() ?? '',
      goodsPriceSnapshot: json['goodsPriceSnapshot'] != null
          ? double.tryParse(json['goodsPriceSnapshot'].toString()) ?? 0.0
          : 0.0,
      goodsImageSnapshot: json['goodsImageSnapshot']?.toString(),
      meetLocation: json['meetLocation']?.toString(),
      buyerMessage: json['buyerMessage']?.toString(),
      sellerReply: json['sellerReply']?.toString(),
      orderStatus: status,
      // 服务端原始状态码：未知状态时它就是唯一可信的展示内容，不能被枚举的 label 覆盖
      statusCode: rawStatusCode.isNotEmpty ? rawStatusCode : status.code,
      statusDescription: json['statusDescription']?.toString() ??
          json['statusDesc']?.toString() ??
          defaultStatusLabel(status, rawStatusCode),
      cancelReason: json['cancelReason']?.toString(),
      cancelledBy: json['cancelledBy']?.toString(),
      createdTime: json['createdTime']?.toString(),
      confirmedTime: json['confirmedTime']?.toString(),
      completedTime: json['completedTime']?.toString(),
      cancelledTime: json['cancelledTime']?.toString(),
      updatedTime: json['updatedTime']?.toString(),
    );
  }

  Map<String, dynamic> toJson() => {
        'id': id,
        'orderNo': orderNo,
        if (buyer != null) 'buyer': buyer!.toJson(),
        if (seller != null) 'seller': seller!.toJson(),
        if (buyerId != null) 'buyerId': buyerId,
        if (sellerId != null) 'sellerId': sellerId,
        'goodsId': goodsId,
        'goodsTitleSnapshot': goodsTitleSnapshot,
        'goodsPriceSnapshot': goodsPriceSnapshot,
        if (goodsImageSnapshot != null)
          'goodsImageSnapshot': goodsImageSnapshot,
        if (meetLocation != null) 'meetLocation': meetLocation,
        if (buyerMessage != null) 'buyerMessage': buyerMessage,
        if (sellerReply != null) 'sellerReply': sellerReply,
        // 保留服务端原始状态码：未知状态经此序列化不会退化成空串
        'orderStatus': statusCode,
        'statusDescription': statusDescription,
        'statusDesc': statusDescription,
        if (cancelReason != null) 'cancelReason': cancelReason,
        if (cancelledBy != null) 'cancelledBy': cancelledBy,
        if (createdTime != null) 'createdTime': createdTime,
        if (confirmedTime != null) 'confirmedTime': confirmedTime,
        if (completedTime != null) 'completedTime': completedTime,
        if (cancelledTime != null) 'cancelledTime': cancelledTime,
        if (updatedTime != null) 'updatedTime': updatedTime,
      };

  OrderVO copyWith({
    String? id,
    String? orderNo,
    OrderUserInfo? buyer,
    OrderUserInfo? seller,
    String? buyerId,
    String? sellerId,
    String? goodsId,
    String? goodsTitleSnapshot,
    double? goodsPriceSnapshot,
    String? goodsImageSnapshot,
    String? meetLocation,
    String? buyerMessage,
    String? sellerReply,
    OrderStatus? orderStatus,
    String? statusCode,
    String? statusDescription,
    String? cancelReason,
    String? cancelledBy,
    String? createdTime,
    String? confirmedTime,
    String? completedTime,
    String? cancelledTime,
    String? updatedTime,
  }) {
    return OrderVO(
      id: id ?? this.id,
      orderNo: orderNo ?? this.orderNo,
      buyer: buyer ?? this.buyer,
      seller: seller ?? this.seller,
      buyerId: buyerId ?? this.buyerId,
      sellerId: sellerId ?? this.sellerId,
      goodsId: goodsId ?? this.goodsId,
      goodsTitleSnapshot: goodsTitleSnapshot ?? this.goodsTitleSnapshot,
      goodsPriceSnapshot: goodsPriceSnapshot ?? this.goodsPriceSnapshot,
      goodsImageSnapshot: goodsImageSnapshot ?? this.goodsImageSnapshot,
      meetLocation: meetLocation ?? this.meetLocation,
      buyerMessage: buyerMessage ?? this.buyerMessage,
      sellerReply: sellerReply ?? this.sellerReply,
      orderStatus: orderStatus ?? this.orderStatus,
      statusCode: statusCode ?? this.statusCode,
      statusDescription: statusDescription ?? this.statusDescription,
      cancelReason: cancelReason ?? this.cancelReason,
      cancelledBy: cancelledBy ?? this.cancelledBy,
      createdTime: createdTime ?? this.createdTime,
      confirmedTime: confirmedTime ?? this.confirmedTime,
      completedTime: completedTime ?? this.completedTime,
      cancelledTime: cancelledTime ?? this.cancelledTime,
      updatedTime: updatedTime ?? this.updatedTime,
    );
  }
}

/// 订单分页列表响应数据模型 (映射 MyBatis-Plus IPage)
class OrderPageResult {
  final List<OrderVO> records;
  final int total;
  final int current;
  final int size;
  final int pages;

  OrderPageResult({
    required this.records,
    required this.total,
    required this.current,
    required this.size,
    required this.pages,
  });

  factory OrderPageResult.fromJson(Map<String, dynamic> json) {
    final list = json['records'] as List<dynamic>? ?? [];
    return OrderPageResult(
      records: list
          .map((e) => OrderVO.fromJson(e as Map<String, dynamic>))
          .toList(),
      total: json['total'] is int
          ? json['total'] as int
          : int.tryParse(json['total']?.toString() ?? '0') ?? 0,
      current: json['current'] is int
          ? json['current'] as int
          : int.tryParse(json['current']?.toString() ?? '1') ?? 1,
      size: json['size'] is int
          ? json['size'] as int
          : int.tryParse(json['size']?.toString() ?? '10') ?? 10,
      pages: json['pages'] is int
          ? json['pages'] as int
          : int.tryParse(json['pages']?.toString() ?? '1') ?? 1,
    );
  }

  Map<String, dynamic> toJson() => {
        'records': records.map((e) => e.toJson()).toList(),
        'total': total,
        'current': current,
        'size': size,
        'pages': pages,
      };
}

/// 创建订单请求入参模型
class CreateOrderRequest {
  final String goodsId;
  final String? meetLocation;
  final String? buyerMessage;

  CreateOrderRequest({
    required this.goodsId,
    this.meetLocation,
    this.buyerMessage,
  });

  Map<String, dynamic> toJson() => {
        'goodsId': goodsId,
        if (meetLocation != null) 'meetLocation': meetLocation,
        if (buyerMessage != null) 'buyerMessage': buyerMessage,
      };
}

/// 取消订单请求入参模型
class CancelOrderRequest {
  final String cancelReason;

  CancelOrderRequest({required this.cancelReason});

  Map<String, dynamic> toJson() => {
        'cancelReason': cancelReason,
      };
}
