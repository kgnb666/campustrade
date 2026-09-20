/// 昵称/用户名展示的统一工具。
///
/// 为什么必须有它：昵称是可清空的字段（编辑资料保存空昵称后端会落库为空串），
/// 而 `user.nickname ?? user.username` 这种写法只挡 `null`，挡不住空串——
/// 空串再 `.substring(0, 1)` 会直接抛 RangeError，整个页面白屏。
/// 因此凡是"取首字/展示名字"的位置统一走这里，空值与空白一律回退。
library;

/// 取展示用的首字（头像占位字符）。
///
/// 依次尝试 [name]、[fallback]；两者都为空或全为空白时返回 `?`（保证调用方永远拿到 1 个字符）。
/// 用 [String.runes] 而不是 `substring(0, 1)`：后者会把 emoji 等代理对字符截成半个字符，
/// 渲染成乱码方块（也是同一类"输入看起来正常、渲染却坏掉"的坑）。
String initialOf(String? name, [String? fallback = '']) {
  for (final candidate in <String?>[name, fallback]) {
    final trimmed = candidate?.trim() ?? '';
    if (trimmed.isNotEmpty) {
      return String.fromCharCode(trimmed.runes.first).toUpperCase();
    }
  }
  return '?';
}

/// 取展示用的完整名字：昵称优先，昵称为空/空白时回退 [fallback]。
///
/// 与 [initialOf] 配对使用，避免出现"头像有首字、名字却是空白"的不一致展示。
String displayNameOf(String? nickname, [String? fallback = '']) {
  final trimmed = nickname?.trim() ?? '';
  if (trimmed.isNotEmpty) return trimmed;
  final fallbackTrimmed = fallback?.trim() ?? '';
  return fallbackTrimmed.isEmpty ? '未设置昵称' : fallbackTrimmed;
}
