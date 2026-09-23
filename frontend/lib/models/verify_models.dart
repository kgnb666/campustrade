/// 校园认证状态模型（对应后端 `GET /student/verify/status`）
///
/// 认证页需要区分四种形态：未认证（从未提交）/ 待审核 / 已认证 / 已驳回（带原因）。
/// 后端已把可读的状态描述一并返回（`verifyStatusDesc`），前端不再维护第二份状态映射表——
/// 状态文案一旦有两处，就会出现"后端改了文案、前端还显示旧说法"的分裂。
class VerifyStatusModel {
  /// PENDING / SUCCESS / REJECTED；从未提交过材料时为 null
  final String? verifyStatus;

  /// EMAIL（校园邮箱验证码）/ MANUAL（学生证人工审核）
  final String? verifyMethod;

  /// 中文状态描述
  final String? verifyStatusDesc;

  final String? schoolName;
  final String? studentNumber;

  /// 人工通道填写的真实姓名（可选）
  final String? realName;

  /// 人工通道提交的学生证/校园卡照片（可选）
  final String? evidenceUrl;

  /// 管理员审核意见（驳回原因）
  final String? reviewNote;

  final String? submittedTime;
  final bool verified;

  const VerifyStatusModel({
    this.verifyStatus,
    this.verifyMethod,
    this.verifyStatusDesc,
    this.schoolName,
    this.studentNumber,
    this.realName,
    this.evidenceUrl,
    this.reviewNote,
    this.submittedTime,
    this.verified = false,
  });

  /// 是否走过人工审核通道
  bool get isManual => (verifyMethod ?? '').toUpperCase() == 'MANUAL';

  /// 是否等待管理员审核
  bool get isPending => (verifyStatus ?? '').toUpperCase() == 'PENDING';

  /// 是否被驳回
  bool get isRejected => (verifyStatus ?? '').toUpperCase() == 'REJECTED';

  /// 从未提交过任何认证材料
  bool get isNone => (verifyStatus ?? '').trim().isEmpty;

  factory VerifyStatusModel.fromJson(Map<String, dynamic> json) {
    return VerifyStatusModel(
      verifyStatus: json['verifyStatus'] as String?,
      verifyMethod: json['verifyMethod'] as String?,
      verifyStatusDesc: json['verifyStatusDesc'] as String?,
      schoolName: json['schoolName'] as String?,
      studentNumber: json['studentNumber'] as String?,
      realName: json['realName'] as String?,
      evidenceUrl: json['evidenceUrl'] as String?,
      reviewNote: json['reviewNote'] as String?,
      submittedTime: json['submittedTime'] as String?,
      verified: json['verified'] as bool? ?? false,
    );
  }
}

/// 管理员审核队列里的一条认证申请（对应后端 `GET /admin/verifies`）
class AdminVerifyItem {
  final String id;
  final String? username;
  final String? nickname;
  final String? schoolName;
  final String? studentNumber;
  final String? realName;
  final String? evidenceUrl;
  final String? verifyStatus;
  final String? verifyStatusDesc;
  final String? reviewNote;
  final String? submittedTime;

  const AdminVerifyItem({
    required this.id,
    this.username,
    this.nickname,
    this.schoolName,
    this.studentNumber,
    this.realName,
    this.evidenceUrl,
    this.verifyStatus,
    this.verifyStatusDesc,
    this.reviewNote,
    this.submittedTime,
  });

  String get displayName {
    final nick = (nickname ?? '').trim();
    if (nick.isNotEmpty) return nick;
    return (username ?? '').trim();
  }

  factory AdminVerifyItem.fromJson(Map<String, dynamic> json) {
    return AdminVerifyItem(
      id: '${json['id']}',
      username: json['username'] as String?,
      nickname: json['nickname'] as String?,
      schoolName: json['schoolName'] as String?,
      studentNumber: json['studentNumber'] as String?,
      realName: json['realName'] as String?,
      evidenceUrl: json['evidenceUrl'] as String?,
      verifyStatus: json['verifyStatus'] as String?,
      verifyStatusDesc: json['verifyStatusDesc'] as String?,
      reviewNote: json['reviewNote'] as String?,
      submittedTime: json['submittedTime'] as String?,
    );
  }
}

/// 审核队列的一页数据
class AdminVerifyPageModel {
  final List<AdminVerifyItem> records;
  final int total;

  const AdminVerifyPageModel({required this.records, required this.total});

  factory AdminVerifyPageModel.fromJson(Map<String, dynamic> json) {
    final rawRecords = (json['records'] as List<dynamic>?) ?? const [];
    return AdminVerifyPageModel(
      records: rawRecords
          .map((e) => AdminVerifyItem.fromJson(e as Map<String, dynamic>))
          .toList(),
      total: (json['total'] as num?)?.toInt() ?? rawRecords.length,
    );
  }
}
