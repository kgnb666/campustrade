import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:frontend/api/dio_client.dart';
import 'package:frontend/controllers/auth_controller.dart';
import 'package:frontend/models/user_model.dart';
import 'package:frontend/routes/app_pages.dart';
import 'package:frontend/routes/app_routes.dart';
import 'package:frontend/services/storage_service.dart';
import 'package:frontend/utils/ui_feedback.dart';
import 'package:get/get.dart' hide Response;

/// 「无邮箱通道」（学号 + 管理员审核）的前端回归测试。
///
/// 这条通道的目标用户**没有**校园邮箱，所以"材料提交不了""审核状态看不到""被驳回后不知道改什么"
/// 这三件事在他们身上没有任何替代路径 —— 直接等于用不了平台。本文件把页面的四种状态与
/// 管理端审核入口钉住，避免以后改动把这条通道悄悄弄坏。
///
/// 另外钉住一条产品决策：**只有学校与学号必填**，姓名与学生证照片是可选加分项。
/// 这条通道面向的是"学校连邮箱都没有"的场景，多一个必填项就可能多挡掉一批人。
void main() {
  Interceptor? mockInterceptor;
  late List<({String title, String message, Duration duration})> toasts;
  late SnackbarDispatcher originalDispatcher;
  late void Function() originalCloser;

  Response jsonOk(RequestOptions options, Object? data, {String message = '操作成功'}) => Response(
        requestOptions: options,
        statusCode: 200,
        data: {'code': 200, 'message': message, 'data': data},
      );

  /// 装一个最小可用的假后端：只覆盖认证页与审核页会用到的接口
  void installMockApi({required Map<String, dynamic> status}) {
    if (mockInterceptor != null) {
      DioClient().dio.interceptors.remove(mockInterceptor);
    }
    mockInterceptor = InterceptorsWrapper(
      onRequest: (options, handler) {
        final path = options.path;
        if (path.contains('/school/list')) {
          return handler.resolve(jsonOk(options, [
            {
              'id': '1',
              'schoolName': '清华大学',
              'schoolCode': '10001',
              'emailSuffix': '@mails.tsinghua.edu.cn',
            }
          ]));
        }
        if (path.contains('/student/verify/status')) {
          return handler.resolve(jsonOk(options, status));
        }
        if (path.contains('/student/verify/manual')) {
          return handler.resolve(jsonOk(options, null,
              message: '材料已提交，管理员审核通过后即可点亮校园认证标识'));
        }
        if (path.contains('/admin/verifies')) {
          return handler.resolve(jsonOk(options, {
            'records': [
              {
                'id': '9001',
                'username': 'no_mail_student',
                'nickname': '没邮箱的同学',
                'schoolName': '广西民族师范学院',
                'studentNumber': '2024010203',
                'realName': '张三',
                'evidenceUrl': 'http://cdn.example.com/evidence/card.png',
                'verifyStatus': 'PENDING',
                'verifyStatusDesc': '待核销/待审核',
                'submittedTime': '2026-09-23 10:00:00',
              }
            ],
            // 刻意写成**字符串**：后端把 long 序列化成字符串，分页 total 也一样。
            // 这里用真实形态压测解析逻辑——曾因写成数字而让线上管理端整页报 TypeError。
            'total': '1',
          }));
        }
        return handler.resolve(jsonOk(options, null));
      },
    );
    DioClient().dio.interceptors.insert(0, mockInterceptor!);
  }

  setUp(() async {
    Get.reset();
    FlutterSecureStorage.setMockInitialValues({});
    await Get.putAsync(() => StorageService().init());
    Get.put(AuthController());

    toasts = [];
    originalDispatcher = snackbarDispatcher;
    originalCloser = snackbarCloser;
    snackbarDispatcher = (title, message, position, background, foreground, duration) {
      toasts.add((title: title, message: message, duration: duration));
    };
    snackbarCloser = () {};
  });

  tearDown(() {
    snackbarDispatcher = originalDispatcher;
    snackbarCloser = originalCloser;
    if (mockInterceptor != null) {
      DioClient().dio.interceptors.remove(mockInterceptor);
      mockInterceptor = null;
    }
    Get.reset();
  });

  Future<void> openPage(WidgetTester tester, String route) async {
    tester.view.physicalSize = const Size(1080, 2400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);

    await tester.pumpWidget(GetMaterialApp(initialRoute: route, getPages: AppPages.routes));
    await tester.pumpAndSettle();
  }

  testWidgets('待审核：不展示表单，明确告知"等待管理员审核"', (tester) async {
    installMockApi(status: {
      'verifyStatus': 'PENDING',
      'verifyMethod': 'MANUAL',
      'verifyStatusDesc': '待核销/待审核',
      'schoolName': '广西民族师范学院',
      'studentNumber': '2024010203',
      'realName': '张三',
      'verified': false,
    });
    await openPage(tester, AppRoutes.studentVerifyManual);

    expect(find.textContaining('等待管理员审核'), findsOneWidget,
        reason: '人工通道必须说清"提交≠认证成功"，否则学生以为已经认证了');
    // 待审核期间不出现提交表单：重复提交只会把审核队列刷满
    expect(find.text('提交审核'), findsNothing);
  });

  testWidgets('被驳回：展示驳回原因，并允许修改材料重新提交', (tester) async {
    installMockApi(status: {
      'verifyStatus': 'REJECTED',
      'verifyMethod': 'MANUAL',
      'verifyStatusDesc': '审核未通过',
      'schoolName': '广西民族师范学院',
      'studentNumber': '2024010203',
      'realName': '张三',
      'reviewNote': '照片模糊，请重新上传学生证内页',
      'verified': false,
    });
    await openPage(tester, AppRoutes.studentVerifyManual);

    expect(find.textContaining('照片模糊，请重新上传学生证内页'), findsOneWidget,
        reason: '驳回原因必须直接展示，学生才知道要改什么');
    expect(find.text('提交审核'), findsOneWidget, reason: '被驳回后必须能重新提交');
  });

  testWidgets('材料不齐：表单内联校验当场拦下，不发请求', (tester) async {
    installMockApi(status: {'verifyStatus': null, 'verified': false});
    await openPage(tester, AppRoutes.studentVerifyManual);

    expect(find.text('提交审核'), findsOneWidget);
    await tester.tap(find.text('提交审核'));
    await tester.pumpAndSettle();

    // 空表单提交时由 Form 的 validator 给出内联提示（比弹 toast 更贴近出错字段），
    // 关键是：没有发出任何请求，也没有"提交成功"这类误导性反馈。
    expect(find.text('请选择高校'), findsOneWidget, reason: '未选学校必须当场提示');
    expect(toasts, isEmpty, reason: '材料不齐不得发出请求');
  });

  testWidgets('只填学校+学号即可提交：姓名与照片都是可选', (tester) async {
    installMockApi(status: {'verifyStatus': null, 'verified': false});
    await openPage(tester, AppRoutes.studentVerifyManual);

    await tester.tap(find.byType(DropdownButtonFormField<SchoolModel>).first);
    await tester.pumpAndSettle();
    await tester.tap(find.text('清华大学 (10001)').last);
    await tester.pumpAndSettle();

    // 只填学号；姓名与照片留空
    await tester.enterText(find.byType(TextFormField).first, '2024010203');
    await tester.pumpAndSettle();
    await tester.tap(find.text('提交审核'));
    await tester.pumpAndSettle();

    expect(toasts.map((t) => t.title), contains('已提交'),
        reason: '只有学号也必须能提交：多一个必填项就可能多挡掉一批没有邮箱的学生');
    expect(find.textContaining('请上传'), findsNothing, reason: '照片不再是必填项');
  });

  testWidgets('管理端：列出待审核材料，并提供通过 / 驳回入口', (tester) async {
    installMockApi(status: const {'verified': false});
    await openPage(tester, AppRoutes.adminVerifyReview);

    expect(find.text('没邮箱的同学'), findsOneWidget);
    expect(find.text('广西民族师范学院'), findsOneWidget);
    expect(find.text('2024010203'), findsOneWidget);
    expect(find.text('通过'), findsOneWidget);
    expect(find.text('驳回'), findsOneWidget);
  });

  testWidgets('账号存在"邮箱通道待核销"记录时，本页仍必须显示填学号的表单', (tester) async {
    installMockApi(status: {
      'verifyStatus': 'PENDING',
      'verifyMethod': 'EMAIL',
      'verifyStatusDesc': '待核销/待审核',
      'verified': false,
    });
    await openPage(tester, AppRoutes.studentVerifyManual);

    expect(find.text('提交审核'), findsOneWidget,
        reason: '邮箱通道的待核销记录不该挡住人工通道的表单——旧逻辑会让人进页面后找不到填学号的地方');
    expect(find.textContaining('邮箱验证码认证在处理中'), findsOneWidget,
        reason: '另一条通道还有在审申请时必须说明，否则用户不知道页面在等什么');
    expect(find.byType(DropdownButtonFormField<SchoolModel>), findsOneWidget);
  });
}
