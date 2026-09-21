import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:get/get.dart';
import '../api/dio_client.dart';
import '../models/user_model.dart';
import '../routes/app_routes.dart';
import '../services/storage_service.dart';
import '../utils/api_error.dart';
import '../utils/app_logger.dart';
import '../utils/name_utils.dart';
import '../utils/ui_feedback.dart';

/// 全局认证与用户信息状态控制器
class AuthController extends GetxController {
  final StorageService _storage = Get.find<StorageService>();
  final DioClient _dioClient = DioClient();

  final RxBool isLoggedIn = false.obs;
  final RxBool isLoading = false.obs;
  final RxString token = ''.obs;
  final Rxn<UserProfileModel> currentUser = Rxn<UserProfileModel>();
  final RxList<SchoolModel> schools = <SchoolModel>[].obs;

  /// 高校列表加载失败原因（空字符串表示无错误）。
  /// 认证页据此给出"重新加载"入口，避免下拉框一直空着而用户不知道原因。
  final RxString schoolsError = ''.obs;

  @override
  void onInit() {
    super.onInit();
    tryAutoLogin();
  }

  /// 启动时尝试从本地 SecureStorage 自动登录
  ///
  /// 失败时必须清空**全部**本地凭据（[StorageService.clearAll]），而不能只清 access token：
  /// refresh token 的有效期是 7 天，若把它留下，用户会陷入"自动登录失败 → 下一次请求
  /// 拿到 401 → 无感刷新用残留的 refresh token 静默重登成功"的诡异状态，
  /// 表现为"明明已经退出/失效了却又自己登了回来"。
  Future<void> tryAutoLogin() async {
    final savedToken = await _storage.getToken();
    if (savedToken != null && savedToken.isNotEmpty) {
      token.value = savedToken;
      final success = await fetchProfile();
      if (success) {
        isLoggedIn.value = true;
      } else {
        await _storage.clearAll();
        token.value = '';
        isLoggedIn.value = false;
        AppLogger.warn('[AuthController] 自动登录失败，已清空全部本地凭据（含 refresh token）');
      }
    }
  }

  /// 用户登录
  Future<bool> login(String username, String password) async {
    try {
      isLoading.value = true;
      final response = await _dioClient.dio.post('/auth/login', data: {
        'username': username.trim(),
        'password': password,
      });

      if (response.data['code'] == 200) {
        final data = response.data['data'];
        final loginResult = LoginResultModel.fromJson(data);

        token.value = loginResult.accessToken;
        await _storage.saveToken(loginResult.accessToken);
        await _storage.saveRefreshToken(loginResult.refreshToken);

        // 新会话开始：复位"本次会话已过期"标记，否则同一进程内第二次会话过期会被静默忽略
        _dioClient.markSessionRestored();

        if (loginResult.userInfo != null) {
          currentUser.value = loginResult.userInfo;
        } else {
          await fetchProfile();
        }

        isLoggedIn.value = true;
        // 先跳转再提示：提示若弹在 offAllNamed 之前，其自动关闭定时器会随旧路由一起被销毁，
        // 导致"登录成功"永久挂在 overlay 上不消失（实测缺陷）。
        safeSnackbarAfterNavigation(
          () => safeOffAllNamed(AppRoutes.home),
          '登录成功',
          '欢迎回到 CampusTrade，${displayNameOf(currentUser.value?.nickname, username)}！',
          snackPosition: SnackPosition.BOTTOM,
          backgroundColor: Colors.green.withAlpha(40),
          colorText: Colors.green[900],
        );
        return true;
      } else {
        AppLogger.error('[AuthController] login 业务失败: ${response.data['message']}');
        safeSnackbar('登录失败', response.data['message'] ?? '用户名或密码错误',
            snackPosition: SnackPosition.BOTTOM,
            backgroundColor: Colors.red.withAlpha(40),
            colorText: Colors.red[900]);
        return false;
      }
    } on DioException catch (e) {
      AppLogger.error('[AuthController] login 请求失败', error: e);
      safeSnackbar('登录异常', describeApiError(e, fallback: '用户名或密码错误'),
          snackPosition: SnackPosition.BOTTOM,
          backgroundColor: Colors.red.withAlpha(40),
          colorText: Colors.red[900]);
      return false;
    } catch (e, stack) {
      // 兜底：响应解析、本地存储等非网络异常此前会静默逃逸，表现为"点击登录没反应"
      AppLogger.error('[AuthController] login unexpected error', error: e, stackTrace: stack);
      safeSnackbar('登录异常', '登录失败，请稍后重试',
          snackPosition: SnackPosition.BOTTOM,
          duration: const Duration(seconds: 6),
          backgroundColor: Colors.red.withAlpha(40),
          colorText: Colors.red[900]);
      return false;
    } finally {
      isLoading.value = false;
    }
  }

  /// 用户注册
  Future<bool> register(String username, String password, String email) async {
    try {
      isLoading.value = true;
      final response = await _dioClient.dio.post('/auth/register', data: {
        'username': username.trim(),
        'password': password,
        'email': email.trim(),
      });

      if (response.data['code'] == 200) {
        // 同上：注册成功后要跳登录页，提示必须在跳转之后弹
        // 该文案含三个分句（注册成功 / 信用档案 / 请登录），3 秒读不完，给到 5 秒
        safeSnackbarAfterNavigation(
          () => safeOffNamed(AppRoutes.login),
          '注册成功',
          '恭喜您注册成功，已为您自动创建初始 100 信用档案，请登录',
          snackPosition: SnackPosition.BOTTOM,
          duration: const Duration(seconds: 5),
          backgroundColor: Colors.green.withAlpha(40),
          colorText: Colors.green[900],
        );
        return true;
      } else {
        AppLogger.error('[AuthController] register 业务失败: ${response.data['message']}');
        safeSnackbar('注册失败', response.data['message'] ?? '注册信息有误',
            snackPosition: SnackPosition.BOTTOM,
            backgroundColor: Colors.red.withAlpha(40),
            colorText: Colors.red[900]);
        return false;
      }
    } on DioException catch (e) {
      AppLogger.error('[AuthController] register 请求失败', error: e);
      safeSnackbar('注册异常', describeApiError(e, fallback: '注册失败，请稍后重试'),
          snackPosition: SnackPosition.BOTTOM,
          backgroundColor: Colors.red.withAlpha(40),
          colorText: Colors.red[900]);
      return false;
    } catch (e, stack) {
      AppLogger.error('[AuthController] register unexpected error', error: e, stackTrace: stack);
      safeSnackbar('注册异常', '注册失败，请稍后重试',
          snackPosition: SnackPosition.BOTTOM,
          duration: const Duration(seconds: 6),
          backgroundColor: Colors.red.withAlpha(40),
          colorText: Colors.red[900]);
      return false;
    } finally {
      isLoading.value = false;
    }
  }

  /// 用户登出
  Future<void> logout() async {
    try {
      await _dioClient.dio.post('/auth/logout');
    } catch (e) {
      // 服务端登出失败不影响本地登出：凭据仍会被彻底清除，仅记录日志
      AppLogger.warn('[AuthController] 登出接口调用失败（本地凭据仍会清除）', error: e);
    }

    // 同步清空本地持久化的 token、refreshToken 与用户信息，避免任何残留状态泄露
    await _storage.clearAll();
    token.value = '';
    isLoggedIn.value = false;
    currentUser.value = null;

    _dioClient.resetSessionState();

    try {
      if (Get.context != null) {
        // 先跳转再提示（原因见 safeSnackbarAfterNavigation 的注释）
        safeSnackbarAfterNavigation(
          () => safeOffAllNamed(AppRoutes.home),
          '已安全退出',
          '您已安全退出当前账号',
        );
      }
    } catch (e, stack) {
      AppLogger.warn('[AuthController] 登出后跳转失败', error: e, stackTrace: stack);
    }
  }

  /// 登录会话过期处理 (无感刷新彻底失败后的优雅降级)
  ///
  /// 由 [DioClient] 在"刷新也失败"时真实调用（单一落点）：
  /// 清空本地凭据与内存态、提示用户并跳转登录页。
  /// 注意：这里**不**复位 [DioClient] 的会话过期去重标记——该标记表示
  /// "本轮会话已处理完过期"，只有重新登录成功才会复位。
  Future<void> handleSessionExpired() async {
    await _storage.clearAll();
    token.value = '';
    isLoggedIn.value = false;
    currentUser.value = null;

    try {
      if (Get.context != null && Get.currentRoute != AppRoutes.LOGIN) {
        // 先跳转再提示（原因见 safeSnackbarAfterNavigation 的注释）
        safeSnackbarAfterNavigation(
          () => safeOffAllNamed(AppRoutes.LOGIN),
          '登录已失效',
          '您的登录会话已过期，请重新登录',
          snackPosition: SnackPosition.TOP,
          backgroundColor: Colors.orange.withAlpha(40),
          colorText: Colors.orange[900],
        );
      }
    } catch (e, stack) {
      // 没有 Navigator（单元测试）时跳转会失败，但不能因此中断上面的凭据清理
      AppLogger.warn('[AuthController] 会话过期跳转登录失败', error: e, stackTrace: stack);
    }
  }

  /// 拉取最新个人信息
  Future<bool> fetchProfile() async {
    try {
      final response = await _dioClient.dio.get('/user/profile');
      if (response.data['code'] == 200) {
        currentUser.value = UserProfileModel.fromJson(response.data['data']);
        return true;
      }
      AppLogger.warn('[AuthController] fetchProfile 返回非 200: ${response.data['message']}');
      return false;
    } catch (e) {
      // 自动登录路径依赖 false 触发本地凭据清理，因此这里不向上抛，只记录
      AppLogger.warn('[AuthController] fetchProfile error', error: e);
      return false;
    }
  }

  /// 修改资料 (昵称, 头像, 手机号)
  Future<bool> updateProfile({String? nickname, String? avatar, String? phone}) async {
    try {
      isLoading.value = true;
      final response = await _dioClient.dio.put('/user/profile', data: {
        if (nickname != null) 'nickname': nickname.trim(),
        if (avatar != null) 'avatar': avatar.trim(),
        if (phone != null) 'phone': phone.trim(),
      });

      if (response.data['code'] == 200) {
        currentUser.value = UserProfileModel.fromJson(response.data['data']);
        safeSnackbar('修改成功', '个人资料已更新',
            snackPosition: SnackPosition.BOTTOM,
            backgroundColor: Colors.green.withAlpha(40),
            colorText: Colors.green[900]);
        return true;
      } else {
        AppLogger.error('[AuthController] updateProfile 业务失败: ${response.data['message']}');
        safeSnackbar('修改失败', response.data['message'] ?? '修改失败',
            snackPosition: SnackPosition.BOTTOM);
        return false;
      }
    } catch (e) {
      AppLogger.error('[AuthController] updateProfile error', error: e);
      safeSnackbar('修改异常', describeApiError(e, fallback: '更新资料时发生错误'),
          snackPosition: SnackPosition.BOTTOM);
      return false;
    } finally {
      isLoading.value = false;
    }
  }

  /// 加载高校列表
  ///
  /// 失败不再静默：记录日志、把原因写进 [schoolsError]，认证页据此给出"重新加载"入口，
  /// 避免用户面对一个永远空着的下拉框却不知道发生了什么。
  Future<void> loadSchools() async {
    try {
      final response = await _dioClient.dio.get('/school/list');
      if (response.data['code'] == 200) {
        final list = (response.data['data'] as List)
            .map((item) => SchoolModel.fromJson(item as Map<String, dynamic>))
            .toList();
        schools.assignAll(list);
        schoolsError.value = '';
      } else {
        schoolsError.value = '高校列表加载失败，请稍后重试';
        AppLogger.warn('[AuthController] loadSchools 返回非 200: ${response.data['message']}');
      }
    } catch (e) {
      schoolsError.value = describeApiError(e, fallback: '高校列表加载失败，请检查网络后重试');
      AppLogger.warn('[AuthController] loadSchools error', error: e);
    }
  }

  /// 提交校园认证申请并发送验证码
  ///
  /// 验证码由服务端通过真实邮件下发到校园邮箱，接口响应不再携带验证码（data 恒为空），
  /// 因此这里只返回"是否已成功下发"，由页面引导用户查收邮件。
  Future<bool> submitVerify(String schoolId, String studentNumber, String schoolEmail) async {
    try {
      isLoading.value = true;
      final response = await _dioClient.dio.post('/student/verify', data: {
        'schoolId': schoolId,
        'studentNumber': studentNumber.trim(),
        'schoolEmail': schoolEmail.trim(),
      });

      if (response.data['code'] == 200) {
        safeSnackbar('验证码已发送', response.data['message'] ?? '验证码已发送至校园邮箱',
            snackPosition: SnackPosition.BOTTOM,
            duration: const Duration(seconds: 4),
            backgroundColor: Colors.blue.withAlpha(40),
            colorText: Colors.blue[900]);
        return true;
      } else {
        AppLogger.error('[AuthController] submitVerify 业务失败: ${response.data['message']}');
        safeSnackbar('申请失败', response.data['message'] ?? '信息校验未通过',
            snackPosition: SnackPosition.BOTTOM,
            backgroundColor: Colors.red.withAlpha(40),
            colorText: Colors.red[900]);
        return false;
      }
    } on DioException catch (e) {
      // 含后端 409（该校园邮箱已被他人认证）与 429（发起人×邮箱 3 次/24h 配额）
      AppLogger.error('[AuthController] submitVerify 请求失败', error: e);
      safeSnackbar('申请异常', describeApiError(e, fallback: '提交失败'),
          snackPosition: SnackPosition.BOTTOM,
          backgroundColor: Colors.red.withAlpha(40),
          colorText: Colors.red[900]);
      return false;
    } catch (e, stack) {
      AppLogger.error('[AuthController] submitVerify unexpected error', error: e, stackTrace: stack);
      safeSnackbar('申请异常', '提交失败，请稍后重试',
          snackPosition: SnackPosition.BOTTOM,
          backgroundColor: Colors.red.withAlpha(40),
          colorText: Colors.red[900]);
      return false;
    } finally {
      isLoading.value = false;
    }
  }

  /// 提交邮箱验证码核验完成认证
  Future<bool> verifyCode(String schoolEmail, String verifyCode) async {
    try {
      isLoading.value = true;
      final response = await _dioClient.dio.post('/student/verify/code', data: {
        'schoolEmail': schoolEmail.trim(),
        'verifyCode': verifyCode.trim(),
      });

      if (response.data['code'] == 200) {
        await fetchProfile();
        // 先跳转再提示（原因见 safeSnackbarAfterNavigation 的注释）
        safeSnackbarAfterNavigation(
          () => safeOffNamed(AppRoutes.profile),
          '认证成功',
          '恭喜！您已成功通过校园学生身份认证',
          snackPosition: SnackPosition.BOTTOM,
          backgroundColor: Colors.green.withAlpha(40),
          colorText: Colors.green[900],
        );
        return true;
      } else {
        AppLogger.error('[AuthController] verifyCode 业务失败: ${response.data['message']}');
        safeSnackbar('核验失败', response.data['message'] ?? '验证码不正确',
            snackPosition: SnackPosition.BOTTOM,
            backgroundColor: Colors.red.withAlpha(40),
            colorText: Colors.red[900]);
        return false;
      }
    } on DioException catch (e) {
      // 后端以真实 HTTP 状态返回业务错误，其中 409 表示"该校园邮箱已被他人认证"，
      // 文案统一由 describeApiError 取服务端 message（这里只补日志与兜底）
      AppLogger.error('[AuthController] verifyCode 请求失败', error: e);
      safeSnackbar('核验异常', describeApiError(e, fallback: '核验失败'),
          snackPosition: SnackPosition.BOTTOM,
          backgroundColor: Colors.red.withAlpha(40),
          colorText: Colors.red[900]);
      return false;
    } catch (e, stack) {
      AppLogger.error('[AuthController] verifyCode unexpected error', error: e, stackTrace: stack);
      safeSnackbar('核验异常', '核验失败，请稍后重试',
          snackPosition: SnackPosition.BOTTOM,
          backgroundColor: Colors.red.withAlpha(40),
          colorText: Colors.red[900]);
      return false;
    } finally {
      isLoading.value = false;
    }
  }
}
