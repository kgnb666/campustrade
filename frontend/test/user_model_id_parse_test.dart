import 'package:flutter_test/flutter_test.dart';
import 'package:frontend/models/user_model.dart';

/// 回归测试：后端 Long 型 ID 统一序列化为字符串（雪花 ID 超出 JS 精度），
/// 前端模型必须同时兼容字符串与数字形式。
///
/// 背景：UserProfileModel 曾写成 `json['id'] as int?`，遇到字符串 ID 会抛 TypeError；
/// 该异常不是 DioException，登录流程捕获不到，表现为"点击登录没有任何反应"
/// （登录请求其实已成功，只是解析用户信息时崩在半路）。
void main() {
  group('UserProfileModel 兼容字符串 ID', () {
    test('后端返回字符串 id 时不抛异常，且字段解析正确', () {
      final json = <String, dynamic>{
        'id': '2101210235183071233',
        'username': '234',
        'nickname': '234',
        'avatar': null,
        'phone': null,
        'email': '2376648372@qq.com',
        'role': 'USER',
        'status': 'ACTIVE',
        'verifyStatus': 'NONE',
        'schoolName': null,
        'studentNumber': null,
        'credit': {
          'creditScore': 100,
          'tradeCount': 0,
          'goodReviewCount': 0,
          'badReviewCount': 0,
        },
      };

      final model = UserProfileModel.fromJson(json);

      expect(model.username, '234');
      expect(model.email, '2376648372@qq.com');
      expect(model.id, isNotNull);
      expect(model.credit?.creditScore, 100);
    });

    test('后端返回数字 id 时同样正常', () {
      final model = UserProfileModel.fromJson(<String, dynamic>{
        'id': 123456,
        'username': 'tester',
        'role': 'USER',
        'status': 'ACTIVE',
        'verifyStatus': 'NONE',
      });

      expect(model.id, '123456');
    });

    test('缺少 id 字段时返回 null 而不抛异常', () {
      final model = UserProfileModel.fromJson(<String, dynamic>{
        'username': 'tester',
        'role': 'USER',
        'status': 'ACTIVE',
        'verifyStatus': 'NONE',
      });

      expect(model.id, isNull);
    });
  });

  group('SchoolModel 兼容字符串 ID', () {
    test('字符串 id 可解析', () {
      final model = SchoolModel.fromJson(<String, dynamic>{
        'id': '1800000000000000001',
        'schoolName': '测试大学',
        'schoolCode': 'TEST',
        'emailSuffix': 'test.edu.cn',
      });

      expect(model.schoolName, '测试大学');
      // 19 位雪花 ID 必须原样保留（转 int 会在 Web 上丢尾数）
      expect(model.id, '1800000000000000001');
    });

    test('数字 id 可解析', () {
      final model = SchoolModel.fromJson(<String, dynamic>{
        'id': 1001,
        'schoolName': '测试大学',
        'schoolCode': 'TEST',
        'emailSuffix': 'test.edu.cn',
      });

      expect(model.id, '1001');
    });
  });
}
