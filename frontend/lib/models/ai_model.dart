/// AI 帮写商品描述响应模型
class AiDescriptionModel {
  final String title;
  final String description;
  final List<String> highlights;
  final int? costMs;
  final bool degraded;

  AiDescriptionModel({
    required this.title,
    required this.description,
    required this.highlights,
    this.costMs,
    this.degraded = false,
  });

  factory AiDescriptionModel.fromJson(Map<String, dynamic> json) {
    var list = json['highlights'] ?? json['tags'];
    List<String> parsedHighlights = [];
    if (list is List) {
      parsedHighlights = list.map((e) => e.toString()).toList();
    }
    return AiDescriptionModel(
      title: json['title'] ?? '',
      description: json['generatedDescription'] ?? json['description'] ?? '',
      highlights: parsedHighlights,
      costMs: json['costMs'] is int ? json['costMs'] : int.tryParse(json['costMs']?.toString() ?? ''),
      degraded: json['degraded'] == true,
    );
  }
}

/// AI 智能分类推荐响应模型
class AiCategoryModel {
  final String? categoryId;
  final String categoryName;
  final String reason;
  final int? costMs;
  final bool degraded;

  AiCategoryModel({
    this.categoryId,
    required this.categoryName,
    required this.reason,
    this.costMs,
    this.degraded = false,
  });

  factory AiCategoryModel.fromJson(Map<String, dynamic> json) {
    return AiCategoryModel(
      categoryId: json['categoryId']?.toString(),
      categoryName: json['categoryName'] ?? '',
      reason: json['reason'] ?? '',
      costMs: json['costMs'] is int ? json['costMs'] : int.tryParse(json['costMs']?.toString() ?? ''),
      degraded: json['degraded'] == true,
    );
  }
}

/// AI 智能估价推荐响应模型
class AiPriceModel {
  final double suggestedPrice;
  final double minPrice;
  final double maxPrice;
  final String reason;
  final int? costMs;
  final bool degraded;

  AiPriceModel({
    required this.suggestedPrice,
    required this.minPrice,
    required this.maxPrice,
    required this.reason,
    this.costMs,
    this.degraded = false,
  });

  factory AiPriceModel.fromJson(Map<String, dynamic> json) {
    return AiPriceModel(
      suggestedPrice: json['suggestedPrice'] != null
          ? double.tryParse(json['suggestedPrice'].toString()) ?? 0.0
          : 0.0,
      minPrice: json['minPrice'] != null
          ? double.tryParse(json['minPrice'].toString()) ?? 0.0
          : 0.0,
      maxPrice: json['maxPrice'] != null
          ? double.tryParse(json['maxPrice'].toString()) ?? 0.0
          : 0.0,
      reason: json['reason'] ?? '',
      costMs: json['costMs'] is int ? json['costMs'] : int.tryParse(json['costMs']?.toString() ?? ''),
      degraded: json['degraded'] == true,
    );
  }
}
