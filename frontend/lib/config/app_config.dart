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
}
