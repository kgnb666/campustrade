/// 前端状态枚举的唯一落点：商品状态与校园认证状态。
///
/// 为什么必须枚举化：页面里散落的 `status == 'ON_SALE'` / `verifyStatus == 'SUCCESS'`
/// 有三类问题——(1) 字面量拼错不会报错，只会让判断恒为 false；
/// (2) 判断 `!= 'ON_SALE'` 会把"已售出"和"已下架"混成同一类（用户看到"已下架"）；
/// (3) 后端新增取值时前端静默走 else 分支。枚举把这些取值一次性收敛。
library;

/// 商品状态。
///
/// 与后端 `GoodsStatus` / V10 CHECK 约束完全一致：
/// DRAFT / ON_SALE / LOCKED / SOLD / OFF_SHELF。
enum GoodsStatus {
  /// 草稿（后端已保留取值，应用侧当前不写入）
  draft('DRAFT', '草稿'),

  /// 在售
  onSale('ON_SALE', '在售中'),

  /// 交易中（已被买家下单锁定）
  locked('LOCKED', '交易中'),

  /// 已售出
  sold('SOLD', '已售出'),

  /// 已下架
  offShelf('OFF_SHELF', '已下架');

  const GoodsStatus(this.code, this.label);

  final String code;
  final String label;

  /// 按后端状态字面量解析；未知或缺失时返回 null（由调用方决定如何展示）。
  ///
  /// 刻意不提供一个"unknown 枚举成员"：未知取值不属于任何已知状态，
  /// 用 null 表达"没有匹配上"，可以强制调用方显式处理（而不是悄悄当成某个默认状态）。
  static GoodsStatus? fromCode(String? code) {
    if (code == null) return null;
    final normalized = code.trim().toUpperCase();
    if (normalized.isEmpty) return null;
    for (final status in GoodsStatus.values) {
      if (status.code == normalized) {
        return status;
      }
    }
    return null;
  }

  /// 展示文案：已知状态用枚举 label，未知状态回退为服务端原文。
  static String labelOf(String? code) {
    final status = fromCode(code);
    if (status != null) return status.label;
    final raw = code?.trim() ?? '';
    return raw.isEmpty ? '未知状态' : raw;
  }

  /// 是否处于"买家可下单"的状态。
  bool get isBuyable => this == GoodsStatus.onSale;

  /// 是否仍可被卖家编辑/上下架（交易中与已售出都被锁定）。
  bool get isEditableBySeller =>
      this == GoodsStatus.onSale || this == GoodsStatus.offShelf;

  /// 是否已不在售（供"不可购买"类 UI 使用，注意标签要用 [label] 区分售出/下架）。
  bool get isUnavailable => this != GoodsStatus.onSale;
}

/// 校园身份认证状态。
///
/// 与后端 `student_verify.verify_status` 的 CHECK 约束一致：
/// PENDING / SUCCESS；从未提交过认证时后端返回 `NONE`。
enum VerifyStatus {
  /// 未提交过认证
  none('NONE', '未认证'),

  /// 已提交，等待验证码核销
  pending('PENDING', '审核中'),

  /// 认证通过
  success('SUCCESS', '已认证'),

  /// 认证被驳回。
  ///
  /// 预留取值：后端 `student_verify.verify_status` 的 CHECK 约束当前只允许
  /// PENDING / SUCCESS（驳回路径尚未实现），因此本成员目前不会被匹配到；
  /// 保留它是为了让解析逻辑在后端开放该状态时无需改动调用方。
  rejected('REJECTED', '未通过');

  const VerifyStatus(this.code, this.label);

  final String code;
  final String label;

  /// 解析后端字面量；null / 空 / 未知一律回退为 [VerifyStatus.none]。
  static VerifyStatus fromCode(String? code) {
    if (code == null) return VerifyStatus.none;
    final normalized = code.trim().toUpperCase();
    if (normalized.isEmpty) return VerifyStatus.none;
    for (final status in VerifyStatus.values) {
      if (status.code == normalized) {
        return status;
      }
    }
    return VerifyStatus.none;
  }

  /// 是否已完成校园认证（发布商品等能力的准入条件）。
  bool get isVerified => this == VerifyStatus.success;
}
