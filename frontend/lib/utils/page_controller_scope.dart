import 'package:get/get.dart';

/// 页面级控制器作用域。
///
/// 背景（本次修复的真实缺陷）：页面此前用 `Get.put(XxxController())` 注册控制器，
/// 而 `Get.put` 对同一类型（或同一 tag）的 key 是"取回已存在的实例"，**不会**替换。
/// 于是同一个控制器被多个页面共用：
/// - `OrderController` 被"我的订单 / 订单详情 / 商品详情下单"三处共用，
///   商品详情下单失败写入的 `errorMessage` 会跟着实例回到"我的订单"，
///   列表页因此显示"创建订单失败"而不是空态；
/// - `GoodsController` 被集市页与"我的发布"共用，页面进入顺序会影响 `onInit` 时机。
///
/// 修复方式：由路由的 `binding` 用 `Get.lazyPut(..., fenix: false)`（非 permanent）
/// 注册**本页面专属**的控制器实例，不同页面用 [PageControllerScope] 传入各自的 tag
/// 区分；实例随路由销毁被 GetX 自动释放，因此每次进入页面都是干净的新实例。
///
/// 本类负责"取用"这一半：
/// - 优先复用路由 binding 注册的实例（**不拥有**，销毁交给 GetX 的路由生命周期）；
/// - 在没有 binding 的场景（直接用 widget 构造页面、单元/Widget 测试、页面被
///   非路由方式嵌入）退化为自己创建，并在页面 `dispose` 时通过 [PageControllerRef.release] 释放。
class PageControllerScope {
  const PageControllerScope._();

  /// 取用页面控制器。
  ///
  /// [create] 只在"当前没有任何实例"时调用；已注册的实例一律复用，
  /// 保证同一页面在重建（热重载、State 重建）时不会拿到两个实例。
  static PageControllerRef<T> acquire<T extends GetxController>(
    T Function() create, {
    String? tag,
  }) {
    if (Get.isRegistered<T>(tag: tag)) {
      return PageControllerRef<T>._(Get.find<T>(tag: tag), tag, false);
    }
    return PageControllerRef<T>._(
      Get.put<T>(create(), tag: tag, permanent: false),
      tag,
      true,
    );
  }
}

/// [PageControllerScope.acquire] 的返回值：控制器实例 + 是否由本页创建。
class PageControllerRef<T extends GetxController> {
  PageControllerRef._(this.controller, this._tag, this._owned);

  final T controller;
  final String? _tag;
  final bool _owned;

  /// 该实例是否由当前页面创建（true 时页面负责在 dispose 中释放）。
  bool get owned => _owned;

  /// 页面 `dispose` 时调用。
  ///
  /// 只释放"页面自己创建的实例"；由路由 binding 注册的实例交给 GetX 随路由销毁，
  /// 这里重复删除会打扰 GetX 的路由生命周期管理。
  void release() {
    if (!_owned) return;
    if (!Get.isRegistered<T>(tag: _tag)) return;
    Get.delete<T>(tag: _tag);
  }
}
