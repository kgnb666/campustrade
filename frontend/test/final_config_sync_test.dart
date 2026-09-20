import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:frontend/config/app_config.dart';

/// 收尾批次：**配置与用户可见元数据不得过期**。
///
/// 两件事在代码评审里最容易漏、用户却直接看得见：
/// 1. `AppConfig.appVersion` 与 `pubspec.yaml` 的 `version` 不同步
///    （此前是 `1.0.0 (Stage 0)`，与 pubspec 的 `1.0.0+1` 不一致，且阶段号早已过期）；
/// 2. 页面文案里硬编码 "Stage N" 阶段号（项目已到 Stage 8，首页却仍写着 Stage 1/2）。
///
/// 这里把它们变成会失败的断言：`flutter test` 一跑就能发现，无需靠人记得同步。
void main() {
  group('AppConfig.appVersion 与 pubspec.yaml 同步', () {
    test('1. pubspec.yaml 的 version 必须与 AppConfig.appVersion 完全一致', () {
      final File pubspec = File('pubspec.yaml');
      expect(pubspec.existsSync(), isTrue,
          reason: 'flutter test 的工作目录应为包根目录（frontend/），未找到 pubspec.yaml');

      final String content = pubspec.readAsStringSync();
      final RegExpMatch? match =
          RegExp(r'^version:\s*(\S+)\s*$', multiLine: true).firstMatch(content);
      expect(match, isNotNull, reason: 'pubspec.yaml 缺少 version: 声明');

      final String pubspecVersion = match!.group(1)!;
      expect(
        AppConfig.appVersion,
        pubspecVersion,
        reason: 'AppConfig.appVersion(=${AppConfig.appVersion}) 与 pubspec.yaml '
            'version(=$pubspecVersion) 不一致：改一处必须同步另一处。'
            '（刻意不引入 package_info_plus：为一个展示用版本号拉入平台插件不划算，'
            '改用这条测试保证同步。）',
      );
    });

    test('2. 版本号里不得再携带阶段号（阶段号会过期）', () {
      expect(AppConfig.appVersion.toLowerCase().contains('stage'), isFalse,
          reason: '版本号不应包含 "Stage N"：阶段号属于开发过程信息，会随批次过期');
      expect(RegExp(r'\d+\.\d+\.\d+').hasMatch(AppConfig.appVersion), isTrue,
          reason: '版本号应为 <major>.<minor>.<patch>[+build] 形式');
    });
  });

  group('用户可见文案不得硬编码阶段号', () {
    test('3. lib/ 下不存在含 "Stage <数字>" 的字符串字面量（注释除外）', () {
      final RegExp stageInStringLiteral = RegExp(
        // 引号开头 -> 中间出现 Stage+数字 -> 引号结尾，确保匹配的是**字符串字面量**
        // 而不是行尾注释（例如 `Text('x'), // Stage 2` 不会被误判）
        '''['"][^'"]*Stage\\s*\\d[^'"]*['"]''',
      );

      final List<String> offenders = <String>[];
      final Directory libDir = Directory('lib');
      expect(libDir.existsSync(), isTrue, reason: '未找到 lib/ 目录');

      for (final FileSystemEntity entity in libDir.listSync(recursive: true)) {
        if (entity is! File || !entity.path.endsWith('.dart')) {
          continue;
        }
        final List<String> lines = entity.readAsLinesSync();
        for (int i = 0; i < lines.length; i++) {
          // 注释里保留历史阶段名（例如 "// Stage 7: 索引优化"）是允许的：
          // 那是给开发看的溯源信息，不是用户可见文案。
          if (lines[i].trimLeft().startsWith('//')) {
            continue;
          }
          if (stageInStringLiteral.hasMatch(lines[i])) {
            offenders.add('${entity.path}:${i + 1}: ${lines[i].trim()}');
          }
        }
      }

      expect(offenders, isEmpty,
          reason: '以下用户可见文案里硬编码了阶段号，请改为描述**能力**'
              '（阶段号会随批次过期，用户看到的应当是"有什么能力"而不是"开发到第几步"）：\n'
              '${offenders.join('\n')}');
    });
  });
}
