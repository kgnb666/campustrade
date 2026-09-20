import 'package:flutter/material.dart';
import 'package:get/get.dart';
import 'config/app_config.dart';
import 'controllers/auth_controller.dart';
import 'routes/app_pages.dart';
import 'routes/app_routes.dart';
import 'services/storage_service.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // 初始化本地安全存储服务
  await Get.putAsync(() => StorageService().init());
  // 初始化全局认证控制器
  Get.put(AuthController());

  runApp(const CampusTradeApp());
}

class CampusTradeApp extends StatelessWidget {
  const CampusTradeApp({super.key});

  @override
  Widget build(BuildContext context) {
    return GetMaterialApp(
      title: AppConfig.appName,
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF1E88E5),
          brightness: Brightness.light,
        ),
      ),
      initialRoute: AppRoutes.initial,
      getPages: AppPages.routes,
    );
  }
}
