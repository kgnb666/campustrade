/// 浏览足迹数据模型
///
/// ID 统一用 String 承载：后端 Long 型雪花 ID 以字符串下发，Web 端用 int 会丢精度。
class HistoryItemModel {
  final String id;
  final String goodsId;
  final String title;
  final double price;
  final String conditionLevel;
  final String status;
  final String? firstImageUrl;
  final String? location;
  final String? browseTime;

  HistoryItemModel({
    required this.id,
    required this.goodsId,
    required this.title,
    required this.price,
    required this.conditionLevel,
    required this.status,
    this.firstImageUrl,
    this.location,
    this.browseTime,
  });

  factory HistoryItemModel.fromJson(Map<String, dynamic> json) {
    return HistoryItemModel(
      id: json['id']?.toString() ?? '',
      goodsId: json['goodsId']?.toString() ?? '',
      title: json['title'] ?? '',
      price: json['price'] != null
          ? double.tryParse(json['price'].toString()) ?? 0.0
          : 0.0,
      conditionLevel: json['conditionLevel'] ?? '良好',
      status: json['status'] ?? 'ON_SALE',
      firstImageUrl: json['firstImageUrl'],
      location: json['location'],
      browseTime: json['browseTime'],
    );
  }
}
