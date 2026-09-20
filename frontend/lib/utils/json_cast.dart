/// 后端数值容错解析工具
///
/// 约定：后端 `JacksonConfig` 把所有 `Long`/`long`/`BigInteger` 序列化为**字符串**
/// （为了不丢失 19 位雪花 ID 的精度），分页计数 `total/pages/current/size` 也都是 long，
/// 因此同样以字符串下发。前端所有从响应里取整数的位置都必须走这里的容错解析，
/// 直接 `as int` 会在真实响应上抛 TypeError（历史上"发布商品没反应""列表为空"皆源于此）。
library;

/// 把响应里的数值安全解析为 int。
///
/// 同时兼容 `"383"`（后端 Long→String）、`383`（Integer/long 直接下发）与 null。
int asInt(dynamic value, [int fallback = 0]) {
  if (value == null) return fallback;
  if (value is int) return value;
  if (value is num) return value.toInt();
  return int.tryParse(value.toString()) ?? fallback;
}

/// 把响应里的数值安全解析为 int?，无值或无法解析时返回 null。
int? asIntOrNull(dynamic value) {
  if (value == null) return null;
  if (value is int) return value;
  if (value is num) return value.toInt();
  return int.tryParse(value.toString());
}
