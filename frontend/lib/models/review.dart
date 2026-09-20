library;

/// 评价数据模型与请求入参定义
/// 对应后端 Stage 5-D ReviewController / ReviewVO / OrderReviewStatusVO / CreateReviewRequest

/// 单条交易评价数据模型
///
/// ID 统一用 String 承载：后端 Long 型雪花 ID 以字符串下发，Web 端用 int 会丢精度。
class ReviewModel {
  final String id;
  final String orderId;
  final String goodsId;
  final String? goodsTitle;
  final String? reviewerId;
  final String? reviewerNickname;
  final String? reviewerAvatar;
  final String reviewedUserId;
  final int score;
  final String? content;
  final List<String> tags;
  final bool isAnonymous;
  final String? status;
  final String? createdTime;

  ReviewModel({
    required this.id,
    required this.orderId,
    required this.goodsId,
    this.goodsTitle,
    this.reviewerId,
    this.reviewerNickname,
    this.reviewerAvatar,
    required this.reviewedUserId,
    required this.score,
    this.content,
    this.tags = const [],
    this.isAnonymous = false,
    this.status,
    this.createdTime,
  });

  /// 格式化星级文字 (例如 5 星 -> "⭐⭐⭐⭐⭐")
  String get starString => '⭐' * (score.clamp(1, 5));

  /// 脱敏后的安全展示昵称 (匿名评价严格展示 "校友***")
  String get displayNickname {
    if (isAnonymous) {
      if (reviewerNickname != null && reviewerNickname!.isNotEmpty) {
        return reviewerNickname!;
      }
      return '校友***';
    }
    return (reviewerNickname != null && reviewerNickname!.isNotEmpty)
        ? reviewerNickname!
        : '校友用户';
  }

  /// 脱敏后的安全展示头像 (匿名评价必须返回 null，禁止泄漏真实头像)
  String? get displayAvatar {
    if (isAnonymous) {
      return null;
    }
    return (reviewerAvatar != null && reviewerAvatar!.isNotEmpty)
        ? reviewerAvatar
        : null;
  }

  factory ReviewModel.fromJson(Map<String, dynamic> json) {
    // 标签解析 (支持 List<dynamic> 或逗号分隔字符串)
    List<String> parsedTags = [];
    if (json['tags'] is List) {
      parsedTags = (json['tags'] as List)
          .map((e) => e.toString().trim())
          .where((e) => e.isNotEmpty)
          .toList();
    } else if (json['tags'] is String && (json['tags'] as String).isNotEmpty) {
      parsedTags = (json['tags'] as String)
          .split(',')
          .map((e) => e.trim())
          .where((e) => e.isNotEmpty)
          .toList();
    }

    final isAnon = json['isAnonymous'] == true || json['anonymous'] == true;

    return ReviewModel(
      id: json['id']?.toString() ?? '',
      orderId: json['orderId']?.toString() ?? '',
      goodsId: json['goodsId']?.toString() ?? '',
      goodsTitle: json['goodsTitle']?.toString(),
      reviewerId: json['reviewerId']?.toString(),
      reviewerNickname: json['reviewerNickname']?.toString(),
      reviewerAvatar: json['reviewerAvatar']?.toString(),
      reviewedUserId: json['reviewedUserId']?.toString() ?? '',
      score: json['score'] is int
          ? json['score'] as int
          : (int.tryParse(json['score']?.toString() ?? '5') ?? 5),
      content: json['content']?.toString(),
      tags: parsedTags,
      isAnonymous: isAnon,
      status: json['status']?.toString() ?? 'VISIBLE',
      createdTime: json['createdTime']?.toString(),
    );
  }

  Map<String, dynamic> toJson() => {
        'id': id,
        'orderId': orderId,
        'goodsId': goodsId,
        if (goodsTitle != null) 'goodsTitle': goodsTitle,
        if (reviewerId != null) 'reviewerId': reviewerId,
        if (reviewerNickname != null) 'reviewerNickname': reviewerNickname,
        if (reviewerAvatar != null) 'reviewerAvatar': reviewerAvatar,
        'reviewedUserId': reviewedUserId,
        'score': score,
        if (content != null) 'content': content,
        'tags': tags,
        'isAnonymous': isAnonymous,
        if (status != null) 'status': status,
        if (createdTime != null) 'createdTime': createdTime,
      };
}

/// 订单双向评价状态视图模型
/// 对应后端 OrderReviewStatusVO
class OrderReviewStatusModel {
  final String orderId;
  final bool isBuyer;
  final bool canReview;
  final String? reasonIfNotEligible;
  final ReviewModel? myReview;
  final ReviewModel? peerReview;

  OrderReviewStatusModel({
    required this.orderId,
    this.isBuyer = true,
    this.canReview = false,
    this.reasonIfNotEligible,
    this.myReview,
    this.peerReview,
  });

  /// 当前登录用户是否已经评价
  bool get currentUserReviewed => myReview != null;

  /// 对方用户是否已经评价
  bool get peerReviewed => peerReview != null;

  /// 双方是否均已评价
  bool get bothReviewed => currentUserReviewed && peerReviewed;

  factory OrderReviewStatusModel.fromJson(Map<String, dynamic> json) {
    return OrderReviewStatusModel(
      orderId: json['orderId']?.toString() ?? '',
      isBuyer: json['isBuyer'] as bool? ?? true,
      canReview: json['canReview'] as bool? ?? false,
      reasonIfNotEligible: json['reasonIfNotEligible']?.toString(),
      myReview: json['myReview'] != null
          ? ReviewModel.fromJson(json['myReview'] as Map<String, dynamic>)
          : null,
      peerReview: json['peerReview'] != null
          ? ReviewModel.fromJson(json['peerReview'] as Map<String, dynamic>)
          : null,
    );
  }

  Map<String, dynamic> toJson() => {
        'orderId': orderId,
        'isBuyer': isBuyer,
        'canReview': canReview,
        if (reasonIfNotEligible != null)
          'reasonIfNotEligible': reasonIfNotEligible,
        if (myReview != null) 'myReview': myReview!.toJson(),
        if (peerReview != null) 'peerReview': peerReview!.toJson(),
      };
}

/// 创建评价入参模型
/// 对应后端 CreateReviewRequest
class CreateReviewRequest {
  final String orderId;
  final int score;
  final String? content;
  final List<String> tags;
  final bool isAnonymous;

  CreateReviewRequest({
    required this.orderId,
    required this.score,
    this.content,
    this.tags = const [],
    this.isAnonymous = false,
  });

  Map<String, dynamic> toJson() => {
        'orderId': orderId,
        'score': score,
        if (content != null && content!.isNotEmpty) 'content': content,
        if (tags.isNotEmpty) 'tags': tags,
        'isAnonymous': isAnonymous,
        'anonymous': isAnonymous,
      };
}

/// 评价分页结果模型
/// 对应后端 `Result<IPage<ReviewVO>>`
class ReviewPageResult {
  final List<ReviewModel> records;
  final int total;
  final int current;
  final int size;
  final int pages;

  ReviewPageResult({
    required this.records,
    required this.total,
    required this.current,
    required this.size,
    required this.pages,
  });

  factory ReviewPageResult.fromJson(Map<String, dynamic> json) {
    final list = (json['records'] ?? json['list']) as List<dynamic>? ?? [];
    return ReviewPageResult(
      records: list
          .map((e) => ReviewModel.fromJson(e as Map<String, dynamic>))
          .toList(),
      total: json['total'] is int
          ? json['total'] as int
          : (int.tryParse(json['total']?.toString() ?? '0') ?? 0),
      current: json['current'] is int
          ? json['current'] as int
          : (int.tryParse(
                  (json['current'] ?? json['page'])?.toString() ?? '1') ??
              1),
      size: json['size'] is int
          ? json['size'] as int
          : (int.tryParse(json['size']?.toString() ?? '10') ?? 10),
      pages: json['pages'] is int
          ? json['pages'] as int
          : (int.tryParse(json['pages']?.toString() ?? '1') ?? 1),
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
