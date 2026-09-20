/// CampusTrade 客户端全局配置
class AppConfig {
  static const String appName = 'CampusTrade';

  /// 客户端版本号，与 `pubspec.yaml` 的 `version:` 保持一致。
  ///
  /// **改 pubspec.yaml 的 version 时请同步改这里。**
  ///
  /// 为什么不从运行期读取：Flutter 没有"读取自身 pubspec 版本"的内置能力，官方做法是引入
  /// `package_info_plus`（平台插件，需要在各平台注册通道）。为展示一个版本号引入原生插件，
  /// 会让 Web/测试环境多出一条平台依赖与失败面，收益不成正比；因此这里保持常量，
  /// 并由 `test/app_version_consistency_test.dart` 直接比对 pubspec.yaml，让"两处不一致"在
  /// `flutter test` 时就失败，而不是靠人记得同步。
  ///
  /// 格式与 pubspec 一致（`<version>+<build>`），不再拼接阶段号：
  /// 阶段号是开发过程信息，会随批次变化，放在用户可见的版本号里只会过期。
  static const String appVersion = '1.0.0+1';

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
