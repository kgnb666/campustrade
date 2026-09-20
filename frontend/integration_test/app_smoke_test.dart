import 'dart:io';
import 'dart:math';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:frontend/api/dio_client.dart';
import 'package:frontend/config/app_config.dart';
import 'package:frontend/controllers/auth_controller.dart';
import 'package:frontend/main.dart';
import 'package:frontend/services/storage_service.dart';
import 'package:get/get.dart' hide Response;
import 'package:integration_test/integration_test.dart';

/// 端到端冒烟（对**真实后端**与真实页面）：
///
///   注册一次性账号 → 登录 → 进入校园集市 → 发布商品 → 我的发布看到该商品 → 自清理
///
/// 运行前提
/// --------
/// 1. 后端已启动且 [AppConfig.apiBaseUrl] 指向它（默认 http://127.0.0.1:8080/api）；
/// 2. 本机有可用设备：
///    - Windows 桌面（需要 Visual Studio C++ 工具链 **且** 已开启"开发者模式"，
///      否则 Flutter 无法为插件创建 symlink，构建会直接失败）：
///        flutter test integration_test/app_smoke_test.dart -d windows
///    - Web（需要与本机 Chrome 版本匹配的 chromedriver 在 4444 端口）：
///        chromedriver --port=4444
///        flutter drive --driver=test_driver/integration_test.dart \
///               --target=integration_test/app_smoke_test.dart -d chrome
/// 3. 本地开发库容器可访问（用于写入/删除一条一次性校园认证记录，见下）。
///
/// 为什么需要写库：后端 `POST /api/goods` 要求当前用户存在
/// `student_verify.verify_status = 'SUCCESS'` 的记录，而验证码只能从真实校园邮箱收到，
/// 自动化流程无法完成该步骤。因此本用例通过 `docker exec ... psql` 直接写入一条
/// **一次性**认证记录（纯测试数据，不改后端代码、不复用任何已有账号），并在 tearDown 删除。
/// 所有凭据都是运行时随机生成的，不落任何字面量。
void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  // ---------------------------------------------------------------------------
  // 一次性账号（运行时随机，不写死任何凭据）
  // ---------------------------------------------------------------------------
  final Random random = Random.secure();
  final String runId = '${DateTime.now().millisecondsSinceEpoch.toRadixString(36)}'
      '${random.nextInt(1 << 30).toRadixString(36)}';
  final String username = 'e2e_$runId';
  // 后端策略：8~50 位且必须同时包含字母与数字
  final String password = 'E2e${runId}Aa1';
  final String email = 'e2e_$runId@example.com';
  final String goodsTitle = 'E2E 冒烟测试商品 $runId';

  /// 本地开发库容器名（docker-compose.yml 里固定）
  const String postgresContainer = 'campustrade-postgres';

  /// 一次性认证记录写入时用的学校邮箱（同样随机，避免触发 V12 的"同校邮箱唯一"约束）
  final String verifyEmail = 'e2e_$runId@e2e.local';

  Map<String, String>? envFile;
  String? userId;
  String? createdGoodsId;
  bool registered = false;

  /// 读取项目根目录的 .env（git-ignored 的唯一凭据来源），避免在测试里写死库口令。
  Map<String, String> readEnvFile() {
    if (envFile != null) return envFile!;
    final File f = File('../.env');
    final map = <String, String>{};
    if (f.existsSync()) {
      for (final line in f.readAsLinesSync()) {
        final trimmed = line.trim();
        if (trimmed.isEmpty || trimmed.startsWith('#')) continue;
        final idx = trimmed.indexOf('=');
        if (idx <= 0) continue;
        map[trimmed.substring(0, idx).trim()] = trimmed.substring(idx + 1).trim();
      }
    }
    envFile = map;
    return map;
  }

  /// 直接执行一条 SQL（只用于测试数据的一次性写入/删除）。
  Future<ProcessResult> psql(String sql) {
    final env = readEnvFile();
    return Process.run('docker', <String>[
      'exec',
      '-e',
      'PGPASSWORD=${env['POSTGRES_PASSWORD'] ?? ''}',
      postgresContainer,
      'psql',
      '-v',
      'ON_ERROR_STOP=1',
      '-U',
      env['POSTGRES_USER'] ?? '',
      '-d',
      env['POSTGRES_DB'] ?? '',
      '-c',
      sql,
    ]);
  }

  Future<void> seedCampusVerification() async {
    final env = readEnvFile();
    final String schema = env['SPRING_DATASOURCE_SCHEMA'] ?? 'campus_trade';

    // 1. 找到刚注册的一次性账号 ID
    final lookup = await psql(
      'SELECT id FROM $schema."user" WHERE username = \'$username\';',
    );
    expect(lookup.exitCode, 0,
        reason: '无法访问本地开发库（docker exec $postgresContainer psql 失败）：'
            '${lookup.stderr}');
    final RegExpMatch? idMatch =
        RegExp(r'\n\s*(\d+)\s*\n').firstMatch(lookup.stdout.toString());
    expect(idMatch, isNotNull,
        reason: '未在开发库里找到刚注册的账号 $username，无法写入校园认证记录');
    userId = idMatch!.group(1);

    // 2. 取一个真实存在的学校（前端 /school/list 也从这里来）
    final schoolRes = await DioClient().dio.get('/school/list');
    final schools = (schoolRes.data['data'] as List).cast<Map<String, dynamic>>();
    expect(schools, isNotEmpty, reason: '后端没有可用的高校数据，无法完成校园认证');
    final schoolId = schools.first['id'].toString();

    // 3. 写入一次性认证记录（id 由时间戳 + 随机数构造，避免依赖库侧生成器）
    final int recordId =
        DateTime.now().millisecondsSinceEpoch * 1000 + random.nextInt(1000);
    final insert = await psql(
      'INSERT INTO $schema.student_verify '
      '(id, user_id, school_id, student_number, school_email, verify_status, verify_time, created_time) '
      'VALUES ($recordId, $userId, $schoolId, \'E2E$runId\', \'$verifyEmail\', '
      '\'SUCCESS\', now(), now());',
    );
    expect(insert.exitCode, 0, reason: '写入校园认证记录失败：${insert.stderr}');
  }

  Future<void> cleanup() async {
    // 1. 删除用例发布的商品（走真实业务接口，保持库内一致）
    if (createdGoodsId != null) {
      try {
        await DioClient().dio.delete('/goods/$createdGoodsId');
      } catch (_) {
        // 已在断言后删除或已被级联清理时忽略
      }
      createdGoodsId = null;
    }

    // 2. 删除一次性校园认证记录
    if (userId != null) {
      try {
        await psql(
          'DELETE FROM ${readEnvFile()['SPRING_DATASOURCE_SCHEMA'] ?? 'campus_trade'}'
          '.student_verify WHERE user_id = $userId;',
        );
      } catch (_) {
        // 清理失败不掩盖真正的断言失败
      }
    }

    // 3. 清空本地凭据（一次性账号的 token 不留在设备上）
    if (Get.isRegistered<StorageService>()) {
      await Get.find<StorageService>().clearAll();
    }
  }

  setUpAll(() async {
    await Get.putAsync(() => StorageService().init());
    Get.put(AuthController());

    // 用真实接口注册一次性账号（注册不是本用例的断言目标，仅作为前置数据）
    final res = await DioClient().dio.post('/auth/register', data: {
      'username': username,
      'password': password,
      'email': email,
    });
    expect(res.statusCode, 200, reason: '注册一次性测试账号失败');
    expect(res.data['code'], 200, reason: '注册失败：${res.data['message']}');
    registered = true;

    await seedCampusVerification();
  });

  tearDownAll(() async {
    if (registered) await cleanup();
  });

  testWidgets('登录 → 进入校园集市 → 发布商品 → 我的发布看到该商品',
      (WidgetTester tester) async {
    // 1. 启动 App（与 main() 相同的引导流程）
    await tester.pumpWidget(const CampusTradeApp());
    await tester.pumpAndSettle();
    expect(find.text('CampusTrade · 校园二手交易平台'), findsOneWidget,
        reason: '首页应正常渲染（后端：${AppConfig.apiBaseUrl}）');

    // 2. 登录
    await tester.tap(find.widgetWithText(TextButton, '登录').first);
    await tester.pumpAndSettle();
    expect(find.text('用户登录'), findsOneWidget);

    await tester.enterText(find.byType(TextFormField).at(0), username);
    await tester.enterText(find.byType(TextFormField).at(1), password);
    await tester.tap(find.text('立即登录'));
    await tester.pumpAndSettle(const Duration(seconds: 2));

    expect(find.textContaining('欢迎回来，'), findsOneWidget,
        reason: '登录成功后应回到首页并显示欢迎语');

    // 3. 进入集市
    await tester.tap(find.text('进入校园集市'));
    await tester.pumpAndSettle(const Duration(seconds: 2));
    expect(find.text('校园集市'), findsOneWidget);

    // 4. 发布商品
    await tester.tap(find.text('发布闲置'));
    await tester.pumpAndSettle(const Duration(seconds: 2));
    expect(find.text('发布闲置商品'), findsOneWidget);

    await tester.enterText(find.byType(TextFormField).at(0), goodsTitle);
    await tester.enterText(find.byType(TextFormField).at(1), '66.60');
    await tester.enterText(find.byType(TextFormField).at(2), '100.00');
    await tester.pumpAndSettle();

    await tester.ensureVisible(find.text('确认发布商品'));
    await tester.tap(find.text('确认发布商品'));
    await tester.pumpAndSettle(const Duration(seconds: 3));

    // 发布成功后回到集市，并且列表里能看到刚发布的商品
    expect(find.text('校园集市'), findsOneWidget);
    await tester.pumpAndSettle(const Duration(seconds: 2));
    expect(find.text(goodsTitle), findsWidgets,
        reason: '集市列表应刷新并包含刚发布的商品');

    // 5. 我的发布：看到该商品
    final myGoods = await DioClient().dio.get('/goods/my');
    final List<dynamic> records = myGoods.data['data'] as List<dynamic>;
    final Map<String, dynamic> mine = records
        .cast<Map<String, dynamic>>()
        .firstWhere((g) => g['title'] == goodsTitle,
            orElse: () => <String, dynamic>{});
    expect(mine, isNotEmpty, reason: '后端 /goods/my 应包含刚发布的商品');
    createdGoodsId = mine['id'].toString();

    await tester.tap(find.byTooltip('个人中心'));
    await tester.pumpAndSettle(const Duration(seconds: 2));
    expect(find.text('个人中心'), findsWidgets);

    await tester.tap(find.text('我的发布').first);
    await tester.pumpAndSettle(const Duration(seconds: 2));

    expect(find.text(goodsTitle), findsOneWidget,
        reason: '"我的发布"里应能看到刚发布的商品');
  });
}
