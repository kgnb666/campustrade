/// 用户信用档案模型
class UserCreditModel {
  final int creditScore;
  final int tradeCount;
  final int goodReviewCount;
  final int badReviewCount;
  final int completedCount;
  final int cancelCount;
  final String? creditLevel;
  final String? updatedTime;

  UserCreditModel({
    required this.creditScore,
    required this.tradeCount,
    required this.goodReviewCount,
    required this.badReviewCount,
    this.completedCount = 0,
    this.cancelCount = 0,
    this.creditLevel,
    this.updatedTime,
  });

  /// 获取计算后的信用等级 (EXCELLENT, GOOD, FAIR, POOR)
  String get computedCreditLevel {
    if (creditLevel != null && creditLevel!.isNotEmpty) {
      return creditLevel!;
    }
    if (creditScore >= 130) return 'EXCELLENT';
    if (creditScore >= 100) return 'GOOD';
    if (creditScore >= 80) return 'FAIR';
    return 'POOR';
  }

  /// 信用等级中文名称
  String get levelDescription {
    switch (computedCreditLevel.toUpperCase()) {
      case 'EXCELLENT':
        return '信用极好';
      case 'GOOD':
        return '信用良好';
      case 'FAIR':
        return '信用中等';
      case 'POOR':
        return '信用较低';
      default:
        return '信用良好';
    }
  }

  factory UserCreditModel.fromJson(Map<String, dynamic> json) {
    return UserCreditModel(
      creditScore: json['creditScore'] as int? ?? 100,
      tradeCount: json['tradeCount'] as int? ?? 0,
      goodReviewCount: json['goodReviewCount'] as int? ?? 0,
      badReviewCount: json['badReviewCount'] as int? ?? 0,
      completedCount: json['completedCount'] is int
          ? json['completedCount'] as int
          : (json['completedCount'] != null
              ? (int.tryParse(json['completedCount'].toString()) ?? 0)
              : (json['tradeCount'] as int? ?? 0)),
      cancelCount: json['cancelCount'] is int
          ? json['cancelCount'] as int
          : (json['cancelCount'] != null
              ? (int.tryParse(json['cancelCount'].toString()) ?? 0)
              : 0),
      creditLevel: json['creditLevel']?.toString(),
      updatedTime: json['updatedTime']?.toString(),
    );
  }

  Map<String, dynamic> toJson() => {
        'creditScore': creditScore,
        'tradeCount': tradeCount,
        'goodReviewCount': goodReviewCount,
        'badReviewCount': badReviewCount,
        'completedCount': completedCount,
        'cancelCount': cancelCount,
        if (creditLevel != null) 'creditLevel': creditLevel,
        if (updatedTime != null) 'updatedTime': updatedTime,
      };
}

/// 用户资料视图模型
///
/// ID 统一用 String 承载：后端 Long 型雪花 ID 以字符串下发，Web 端用 int 会丢精度。
class UserProfileModel {
  final String? id;
  final String username;
  final String? nickname;
  final String? avatar;
  final String? phone;
  final String? email;
  final String role;
  final String status;
  final String verifyStatus;
  final String? schoolName;
  final String? studentNumber;
  final UserCreditModel? credit;

  UserProfileModel({
    this.id,
    required this.username,
    this.nickname,
    this.avatar,
    this.phone,
    this.email,
    required this.role,
    required this.status,
    required this.verifyStatus,
    this.schoolName,
    this.studentNumber,
    this.credit,
  });

  bool get isVerified => verifyStatus == 'SUCCESS';

  factory UserProfileModel.fromJson(Map<String, dynamic> json) {
    return UserProfileModel(
      // 后端 Long 型 ID 统一序列化为字符串（雪花 ID 超出 JS 精度），此处兼容字符串与数字两种形式
      id: json['id']?.toString(),
      username: json['username'] as String? ?? '',
      nickname: json['nickname'] as String?,
      avatar: json['avatar'] as String?,
      phone: json['phone'] as String?,
      email: json['email'] as String?,
      role: json['role'] as String? ?? 'USER',
      status: json['status'] as String? ?? 'ACTIVE',
      verifyStatus: json['verifyStatus'] as String? ?? 'NONE',
      schoolName: json['schoolName'] as String?,
      studentNumber: json['studentNumber'] as String?,
      credit: json['credit'] != null
          ? UserCreditModel.fromJson(json['credit'] as Map<String, dynamic>)
          : null,
    );
  }

  Map<String, dynamic> toJson() => {
        'id': id,
        'username': username,
        'nickname': nickname,
        'avatar': avatar,
        'phone': phone,
        'email': email,
        'role': role,
        'status': status,
        'verifyStatus': verifyStatus,
        'schoolName': schoolName,
        'studentNumber': studentNumber,
        'credit': credit?.toJson(),
      };
}

/// 登录结果返回模型
class LoginResultModel {
  final String accessToken;
  final String refreshToken;
  final UserProfileModel? userInfo;

  LoginResultModel({
    required this.accessToken,
    required this.refreshToken,
    this.userInfo,
  });

  factory LoginResultModel.fromJson(Map<String, dynamic> json) {
    return LoginResultModel(
      accessToken: json['accessToken'] as String? ?? '',
      refreshToken: json['refreshToken'] as String? ?? '',
      userInfo: json['userInfo'] != null
          ? UserProfileModel.fromJson(json['userInfo'] as Map<String, dynamic>)
          : null,
    );
  }
}

/// 高校信息模型
class SchoolModel {
  final String id;
  final String schoolName;
  final String schoolCode;
  final String emailSuffix;

  SchoolModel({
    required this.id,
    required this.schoolName,
    required this.schoolCode,
    required this.emailSuffix,
  });

  factory SchoolModel.fromJson(Map<String, dynamic> json) {
    return SchoolModel(
      // 同上：兼容后端以字符串形式返回的 Long 型 ID
      id: json['id']?.toString() ?? '',
      schoolName: json['schoolName'] as String? ?? '',
      schoolCode: json['schoolCode'] as String? ?? '',
      emailSuffix: json['emailSuffix'] as String? ?? '',
    );
  }
}
