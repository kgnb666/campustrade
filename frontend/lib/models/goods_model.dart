/// 商品列表简要模型
class GoodsItemModel {
  final String id;
  final String sellerId;
  final String schoolId;
  final String schoolName;
  final String categoryId;
  final String categoryName;
  final String title;
  final String? coverImage;
  final double price;
  final double? originalPrice;
  final String conditionLevel;
  final String status;
  final String? location;
  final int viewCount;
  final String? createdTime;

  GoodsItemModel({
    required this.id,
    required this.sellerId,
    required this.schoolId,
    required this.schoolName,
    required this.categoryId,
    required this.categoryName,
    required this.title,
    this.coverImage,
    required this.price,
    this.originalPrice,
    required this.conditionLevel,
    required this.status,
    this.location,
    this.viewCount = 0,
    this.createdTime,
  });

  factory GoodsItemModel.fromJson(Map<String, dynamic> json) {
    return GoodsItemModel(
      id: json['id']?.toString() ?? '',
      sellerId: json['sellerId']?.toString() ?? '',
      schoolId: json['schoolId']?.toString() ?? '',
      schoolName: json['schoolName'] ?? '高校',
      categoryId: json['categoryId']?.toString() ?? '',
      categoryName: json['categoryName'] ?? '',
      title: json['title'] ?? '',
      coverImage: json['coverImage'],
      price: json['price'] != null
          ? double.tryParse(json['price'].toString()) ?? 0.0
          : 0.0,
      originalPrice: json['originalPrice'] != null
          ? double.tryParse(json['originalPrice'].toString())
          : null,
      conditionLevel: json['conditionLevel'] ?? '9成新',
      status: json['status'] ?? 'ON_SALE',
      location: json['location'],
      viewCount: json['viewCount'] ?? 0,
      createdTime: json['createdTime'],
    );
  }
}

/// 商品详情完整模型
class GoodsDetailModel {
  final String id;
  final String sellerId;
  final String schoolId;
  final String schoolName;
  final String categoryId;
  final String categoryName;
  final String title;
  final String description;
  final double price;
  final double? originalPrice;
  final String conditionLevel;
  final String status;
  final String? location;
  final int viewCount;
  final String? createdTime;
  final String? updatedTime;
  final List<String> images;
  final List<String> tags;

  // 卖家与信用信息
  final String sellerUsername;
  final String sellerNickname;
  final String? sellerAvatar;
  final bool sellerVerified;
  final String? sellerSchoolName;
  final int sellerCreditScore;
  final int sellerTradeCount;
  final int sellerGoodReviewCount;

  GoodsDetailModel({
    required this.id,
    required this.sellerId,
    required this.schoolId,
    required this.schoolName,
    required this.categoryId,
    required this.categoryName,
    required this.title,
    required this.description,
    required this.price,
    this.originalPrice,
    required this.conditionLevel,
    required this.status,
    this.location,
    this.viewCount = 0,
    this.createdTime,
    this.updatedTime,
    this.images = const [],
    this.tags = const [],
    required this.sellerUsername,
    required this.sellerNickname,
    this.sellerAvatar,
    this.sellerVerified = false,
    this.sellerSchoolName,
    this.sellerCreditScore = 100,
    this.sellerTradeCount = 0,
    this.sellerGoodReviewCount = 0,
    this.favoriteCount = 0,
    this.isFavorite = false,
  });

  final int favoriteCount;
  final bool isFavorite;

  factory GoodsDetailModel.fromJson(Map<String, dynamic> json) {
    var imagesList = (json['images'] as List<dynamic>?)
            ?.map((e) => e.toString())
            .toList() ??
        [];
    var tagsList = (json['tags'] as List<dynamic>?)
            ?.map((e) => e.toString())
            .toList() ??
        [];

    return GoodsDetailModel(
      id: json['id']?.toString() ?? '',
      sellerId: json['sellerId']?.toString() ?? '',
      schoolId: json['schoolId']?.toString() ?? '',
      schoolName: json['schoolName'] ?? '高校',
      categoryId: json['categoryId']?.toString() ?? '',
      categoryName: json['categoryName'] ?? '',
      title: json['title'] ?? '',
      description: json['description'] ?? '',
      price: json['price'] != null
          ? double.tryParse(json['price'].toString()) ?? 0.0
          : 0.0,
      originalPrice: json['originalPrice'] != null
          ? double.tryParse(json['originalPrice'].toString())
          : null,
      conditionLevel: json['conditionLevel'] ?? '9成新',
      status: json['status'] ?? 'ON_SALE',
      location: json['location'],
      viewCount: json['viewCount'] ?? 0,
      createdTime: json['createdTime'],
      updatedTime: json['updatedTime'],
      images: imagesList,
      tags: tagsList,
      sellerUsername: json['sellerUsername'] ?? '',
      sellerNickname: json['sellerNickname'] ?? '',
      sellerAvatar: json['sellerAvatar'],
      sellerVerified: json['sellerVerified'] ?? false,
      sellerSchoolName: json['sellerSchoolName'],
      sellerCreditScore: json['sellerCreditScore'] ?? 100,
      sellerTradeCount: json['sellerTradeCount'] ?? 0,
      sellerGoodReviewCount: json['sellerGoodReviewCount'] ?? 0,
      favoriteCount: json['favoriteCount'] != null
          ? (json['favoriteCount'] is int ? json['favoriteCount'] : int.tryParse(json['favoriteCount'].toString()) ?? 0)
          : 0,
      isFavorite: json['isFavorite'] == true,
    );
  }
}
