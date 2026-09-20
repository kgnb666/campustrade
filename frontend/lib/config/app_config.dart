/// CampusTrade 客户端全局配置
class AppConfig {
  static const String appName = 'CampusTrade';
  static const String appVersion = '1.0.0 (Stage 0)';

  /// API 基础请求路径 (可通过 dart-define 动态指定)
  static const String apiBaseUrl = String.fromEnvironment(
    'API_BASE_URL',
    defaultValue: 'http://127.0.0.1:8080/api',
  );

  /// 网络连接超时时间 (毫秒)
  static const int connectTimeout = 15000;

  /// 网络读取超时时间 (毫秒)
  static const int receiveTimeout = 15000;

  // ==========================================================================
  // 列表分页：每页条数（列表类接口的统一取值）
  //
  // 收敛理由：这些值此前散落在各控制器里（商品/收藏/订单是 10、足迹是 20），
  // 既看不出"为什么是 10"，也没有一处能统一调整。集中到这里后，
  // 调参与排查只需改一个地方，页面与控制器也不再各写各的魔法数字。
  //
  // 取值依据：商品/收藏/订单是双列卡片或长卡片，10 条约一屏到一屏半，
  // 首屏请求体小、滚动加载节奏自然；足迹是单行紧凑列表，一屏能放更多，故取 20。
  // ==========================================================================

  /// 商品列表 / 搜索结果的每页条数
  static const int goodsPageSize = 10;

  /// 我的收藏每页条数
  static const int favoritePageSize = 10;

  /// 我的订单每页条数
  static const int orderPageSize = 10;

  /// 浏览足迹每页条数
  static const int historyPageSize = 20;

  /// 商品/用户评价列表每页条数
  static const int reviewPageSize = 10;
}
