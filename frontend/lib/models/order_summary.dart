import '../utils/json_cast.dart';

/// 首页"我的待办"汇总模型（对应后端 `GET /api/orders/summary` 的 data）。
///
/// 为什么需要后端汇总而不是前端自己算：
/// - "待我确认 / 待面交"可以由订单状态数出来；
/// - "待评价"不能——订单是 COMPLETED 只说明交易结束，是否还需要**我**评价取决于
///   我有没有在这笔订单上提交过评价。前端逐单去查评价状态会退化成 N+1 请求。
///
/// 三个计数只统计**当前登录用户自己**的订单（后端 SQL 已按 buyer_id/seller_id = 我 收敛）。
class OrderTodoSummary {
  /// 待我确认（我是卖家，订单状态 WAIT_SELLER_CONFIRM）
  final int pendingSellerConfirm;

  /// 待面交（我是买家或卖家，订单状态 WAIT_MEET）
  final int waitMeet;

  /// 待评价（我参与、已完成、且我尚未评价）
  final int toReview;

  const OrderTodoSummary({
    this.pendingSellerConfirm = 0,
    this.waitMeet = 0,
    this.toReview = 0,
  });

  /// 全部为 0 的空汇总：请求还没回来 / 未登录时用它占位，避免页面上出现 null。
  static const OrderTodoSummary empty = OrderTodoSummary();

  /// 解析响应。
  ///
  /// 用 [asInt] 而不是 `as int`：后端统一把 64 位整型序列化成字符串，
  /// 计数类字段在不同版本里可能是数字也可能是字符串，直接 `as int` 会在真机上抛 TypeError。
  factory OrderTodoSummary.fromJson(Map<String, dynamic> json) {
    return OrderTodoSummary(
      pendingSellerConfirm: asInt(json['pendingSellerConfirm']),
      waitMeet: asInt(json['waitMeet']),
      toReview: asInt(json['toReview']),
    );
  }
}
