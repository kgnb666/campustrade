// integration_test 的 Web 运行入口（flutter drive 需要它）：
//
//   chromedriver --port=4444
//   flutter drive --driver=test_driver/integration_test.dart \
//          --target=integration_test/app_smoke_test.dart -d chrome
//
// 桌面端（Windows）不需要本文件，直接：
//   flutter test integration_test/app_smoke_test.dart -d windows
import 'package:integration_test/integration_test_driver.dart';

Future<void> main() => integrationDriver();
