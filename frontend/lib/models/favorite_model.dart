/// 收藏商品数据模型
///
/// ID 统一用 String 承载：后端 Long 型雪花 ID 以字符串下发，Web 端用 int 会丢精度。
class FavoriteItemModel {
  final String id;
  final String goodsId;
  final String title;
  final double price;
  final double? originalPrice;
  final String conditionLevel;
  final String status;
  final String? firstImageUrl;
  final String? location;
  final String? sellerId;
  final String? schoolName;
  final String? createdTime;

  FavoriteItemModel({
    required this.id,
    required this.goodsId,
    required this.title,
    required this.price,
    this.originalPrice,
    required this.conditionLevel,
    required this.status,
    this.firstImageUrl,
    this.location,
    this.sellerId,
    this.schoolName,
    this.createdTime,
  });

  factory FavoriteItemModel.fromJson(Map<String, dynamic> json) {
    return FavoriteItemModel(
      id: json['id']?.toString() ?? '',
      goodsId: json['goodsId']?.toString() ?? '',
      title: json['title'] ?? '',
      price: json['price'] != null
          ? double.tryParse(json['price'].toString()) ?? 0.0
          : 0.0,
      originalPrice: json['originalPrice'] != null
          ? double.tryParse(json['originalPrice'].toString())
          : null,
      conditionLevel: json['conditionLevel'] ?? '良好',
      status: json['status'] ?? 'ON_SALE',
      firstImageUrl: json['firstImageUrl'],
      location: json['location'],
      sellerId: json['sellerId']?.toString(),
      schoolName: json['schoolName'],
      createdTime: json['createdTime'],
    );
  }
}
