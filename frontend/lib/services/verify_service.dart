import 'dart:typed_data';

import 'package:dio/dio.dart';
import '../api/dio_client.dart';
import '../models/verify_models.dart';
import '../utils/api_error.dart';
import '../utils/app_logger.dart';

/// 校园认证服务（「无邮箱通道」与审核队列）
///
/// 邮箱验证码通道的方法仍在 [AuthController] 里（`/student/verify`、`/student/verify/code`）；
/// 本服务只负责新增的第二条通道：学生证照片 + 管理员人工审核，以及管理员侧的审核队列。
/// 两条通道的**认证结果语义完全相同**（都是 `verify_status = SUCCESS`），因此这里不做任何
/// "另一种认证"的特殊标记，只有材料与审核过程的差异。
///
/// 错误处理与项目其它服务一致：抛 [ApiException]，message 已是可直接展示的中文文案。
class VerifyService {
  final Dio _dio = DioClient().dio;

  /// 上传认证材料（学生证/校园卡照片），返回可直接访问的地址
  ///
  /// 复用后端的 `/file/upload`（与商品图同一通道、同一 MinIO 桶）：认证材料没有理由再走一套
  /// 独立的存储链路，否则又多一份权限、配额与清理逻辑需要维护。
  Future<String> uploadEvidence(Uint8List bytes, String filename) async {
    try {
      final formData = FormData.fromMap({
        'file': MultipartFile.fromBytes(bytes, filename: filename),
      });
      final response = await _dio.post('/file/upload', data: formData);
      if (response.statusCode == 200 && response.data['code'] == 200) {
        return response.data['data'] as String;
      }
      throw ApiException(serverMessageOr(response, '材料上传失败'), statusCode: response.statusCode);
    } catch (e) {
      AppLogger.error('[VerifyService] uploadEvidence error', error: e);
      throw ApiException.from(e, fallback: '材料上传失败');
    }
  }

  /// 提交「无邮箱通道」认证材料，返回服务端提示文案（"材料已提交，等待审核"）
  ///
  /// 只有 [schoolId] 与 [studentNumber] 是必填：姓名与照片是可选加分材料。
  /// 这条通道面向"学校连邮箱都没有"的学生，多一个必填项就可能多挡掉一批人。
  Future<String> submitManualVerify({
    required String schoolId,
    required String studentNumber,
    String? realName,
    String? evidenceUrl,
  }) async {
    try {
      final response = await _dio.post('/student/verify/manual', data: {
        'schoolId': schoolId,
        'studentNumber': studentNumber,
        'realName': realName,
        'evidenceUrl': evidenceUrl,
      });
      if (response.statusCode != 200 || response.data['code'] != 200) {
        throw ApiException(serverMessageOr(response, '材料提交失败'), statusCode: response.statusCode);
      }
      return (response.data['message'] as String?) ?? '材料已提交，等待管理员审核';
    } catch (e) {
      AppLogger.error('[VerifyService] submitManualVerify error', error: e);
      throw ApiException.from(e, fallback: '材料提交失败');
    }
  }

  /// 查询本人认证状态（未认证 / 待审核 / 已认证 / 已驳回）
  Future<VerifyStatusModel> getMyVerifyStatus() async {
    try {
      final response = await _dio.get('/student/verify/status');
      if (response.statusCode == 200 && response.data['code'] == 200) {
        final data = response.data['data'];
        if (data == null) {
          return const VerifyStatusModel();
        }
        return VerifyStatusModel.fromJson(data as Map<String, dynamic>);
      }
      throw ApiException(serverMessageOr(response, '认证状态查询失败'), statusCode: response.statusCode);
    } catch (e) {
      AppLogger.error('[VerifyService] getMyVerifyStatus error', error: e);
      throw ApiException.from(e, fallback: '认证状态查询失败');
    }
  }

  /// 管理员：认证审核队列（默认待审核，按提交时间正序）
  Future<AdminVerifyPageModel> pageVerifies({
    String status = 'PENDING',
    int page = 1,
    int size = 10,
  }) async {
    try {
      final response = await _dio.get('/admin/verifies', queryParameters: {
        'status': status,
        'page': page,
        'size': size,
      });
      if (response.statusCode == 200 && response.data['code'] == 200) {
        return AdminVerifyPageModel.fromJson(response.data['data'] as Map<String, dynamic>);
      }
      throw ApiException(serverMessageOr(response, '审核队列加载失败'), statusCode: response.statusCode);
    } catch (e) {
      AppLogger.error('[VerifyService] pageVerifies error', error: e);
      throw ApiException.from(e, fallback: '审核队列加载失败');
    }
  }

  /// 管理员：处置一条认证申请（通过 / 驳回）
  ///
  /// [note] 在驳回时必填：服务端会拒绝"没有原因的驳回"，前端这里也先做一次提示，
  /// 避免明知会被拒还发一次请求。
  Future<void> reviewVerify({
    required String id,
    required bool approve,
    String? note,
  }) async {
    try {
      final response = await _dio.put('/admin/verifies/$id/review', data: {
        'action': approve ? 'APPROVE' : 'REJECT',
        'note': note,
      });
      if (response.statusCode != 200 || response.data['code'] != 200) {
        throw ApiException(serverMessageOr(response, '审核处置失败'), statusCode: response.statusCode);
      }
    } catch (e) {
      AppLogger.error('[VerifyService] reviewVerify error', error: e);
      throw ApiException.from(e, fallback: '审核处置失败');
    }
  }
}
