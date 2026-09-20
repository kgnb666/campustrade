/// 商品分类数据模型
///
/// ID 统一用 String 承载：后端将 Long 型雪花 ID 序列化为字符串（19 位超出 JS 精度上限），
/// 若在 Web 上落到 int 会掉尾数，导致按 ID 取数据的请求 404。
class CategoryModel {
  final String id;
  final String parentId;
  final String name;
  final String? icon;
  final int sort;
  final List<CategoryModel> children;

  CategoryModel({
    required this.id,
    required this.parentId,
    required this.name,
    this.icon,
    this.sort = 0,
    this.children = const [],
  });

  factory CategoryModel.fromJson(Map<String, dynamic> json) {
    var childrenJson = json['children'] as List<dynamic>? ?? [];
    List<CategoryModel> childList = childrenJson
        .map((e) => CategoryModel.fromJson(e as Map<String, dynamic>))
        .toList();

    return CategoryModel(
      id: json['id']?.toString() ?? '',
      parentId: json['parentId']?.toString() ?? '0',
      name: json['name'] ?? '',
      icon: json['icon'],
      sort: json['sort'] ?? 0,
      children: childList,
    );
  }

  Map<String, dynamic> toJson() => {
        'id': id,
        'parentId': parentId,
        'name': name,
        'icon': icon,
        'sort': sort,
        'children': children.map((e) => e.toJson()).toList(),
      };
}
