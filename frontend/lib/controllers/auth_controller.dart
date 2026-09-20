import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:get/get.dart';
import '../api/dio_client.dart';
import '../models/user_model.dart';
import '../routes/app_routes.dart';
import '../services/storage_service.dart';

/// 全局认证与用户信息状态控制器
class AuthController extends GetxController {
  final StorageService _storage = Get.find<StorageService>();
  final DioClient _dioClient = DioClient();

  final RxBool isLoggedIn = false.obs;
  final RxBool isLoading = false.obs;
  final RxString token = ''.obs;
  final Rxn<UserProfileModel> currentUser = Rxn<UserProfileModel>();
  final RxList<SchoolModel> schools = <SchoolModel>[].obs;

  @override
  void onInit() {
    super.onInit();
    tryAutoLogin();
  }

  /// 启动时尝试从本地 SecureStorage 自动登录
  Future<void> tryAutoLogin() async {
    final savedToken = await _storage.getToken();
    if (savedToken != null && savedToken.isNotEmpty) {
      token.value = savedToken;
      final success = await fetchProfile();
      if (success) {
        isLoggedIn.value = true;
      } else {
        await _storage.clearToken();
        token.value = '';
        isLoggedIn.value = false;
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

        if (loginResult.userInfo != null) {
          currentUser.value = loginResult.userInfo;
        } else {
          await fetchProfile();
        }

        isLoggedIn.value = true;
        Get.snackbar('登录成功', '欢迎回到 CampusTrade，${currentUser.value?.nickname ?? username}！',
            snackPosition: SnackPosition.BOTTOM,
            backgroundColor: Colors.green.withAlpha(40),
            colorText: Colors.green[900]);

        Get.offAllNamed(AppRoutes.home);
        return true;
      } else {
        Get.snackbar('登录失败', response.data['message'] ?? '用户名或密码错误',
            snackPosition: SnackPosition.BOTTOM,
            backgroundColor: Colors.red.withAlpha(40),
            colorText: Colors.red[900]);
        return false;
      }
    } on DioException catch (e) {
      final msg = e.response?.data is Map
          ? e.response?.data['message']
          : (e.message ?? '网络连接失败');
      Get.snackbar('登录异常', msg ?? '用户名或密码错误',
          snackPosition: SnackPosition.BOTTOM,
          backgroundColor: Colors.red.withAlpha(40),
          colorText: Colors.red[900]);
      return false;
    } catch (e) {
      // 兜底：响应解析、本地存储等非网络异常此前会静默逃逸，表现为"点击登录没反应"
      Get.snackbar('登录异常', '客户端处理异常：$e',
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
        Get.snackbar('注册成功', '恭喜您注册成功，已为您自动创建初始 100 信用档案，请登录',
            snackPosition: SnackPosition.BOTTOM,
            backgroundColor: Colors.green.withAlpha(40),
            colorText: Colors.green[900]);
        Get.offNamed(AppRoutes.login);
        return true;
      } else {
        Get.snackbar('注册失败', response.data['message'] ?? '注册信息有误',
            snackPosition: SnackPosition.BOTTOM,
            backgroundColor: Colors.red.withAlpha(40),
            colorText: Colors.red[900]);
        return false;
      }
    } on DioException catch (e) {
      final msg = e.response?.data is Map
          ? e.response?.data['message']
          : (e.message ?? '网络连接失败');
      Get.snackbar('注册异常', msg ?? '注册失败，请稍后重试',
          snackPosition: SnackPosition.BOTTOM,
          backgroundColor: Colors.red.withAlpha(40),
          colorText: Colors.red[900]);
      return false;
    } catch (e) {
      Get.snackbar('注册异常', '客户端处理异常：$e',
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
    } catch (_) {}

    // 同步清空本地持久化的 token、refreshToken 与用户信息，避免任何残留状态泄露
    await _storage.clearAll();
    token.value = '';
    isLoggedIn.value = false;
    currentUser.value = null;

    _dioClient.resetSessionState();

    try {
      if (Get.context != null) {
        Get.snackbar('已安全退出', '您已安全退出当前账号',
            snackPosition: SnackPosition.BOTTOM);
        Get.offAllNamed(AppRoutes.home);
      }
    } catch (_) {}
  }

  /// 登录会话过期处理 (无感刷新彻底失败后的优雅降级)
  Future<void> handleSessionExpired() async {
    await _storage.clearAll();
    token.value = '';
    isLoggedIn.value = false;
    currentUser.value = null;

    _dioClient.resetSessionState();

    try {
      if (Get.context != null && Get.currentRoute != AppRoutes.LOGIN) {
        Get.snackbar('登录已失效', '您的登录会话已过期，请重新登录',
            snackPosition: SnackPosition.TOP,
            backgroundColor: Colors.orange.withAlpha(40),
            colorText: Colors.orange[900]);
        Get.offAllNamed(AppRoutes.LOGIN);
      }
    } catch (_) {}
  }

  /// 拉取最新个人信息
  Future<bool> fetchProfile() async {
    try {
      final response = await _dioClient.dio.get('/user/profile');
      if (response.data['code'] == 200) {
        currentUser.value = UserProfileModel.fromJson(response.data['data']);
        return true;
      }
      return false;
    } catch (e) {
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
        Get.snackbar('修改成功', '个人资料已更新',
            snackPosition: SnackPosition.BOTTOM,
            backgroundColor: Colors.green.withAlpha(40),
            colorText: Colors.green[900]);
        return true;
      } else {
        Get.snackbar('修改失败', response.data['message'] ?? '修改失败',
            snackPosition: SnackPosition.BOTTOM);
        return false;
      }
    } catch (e) {
      Get.snackbar('修改异常', '更新资料时发生错误',
          snackPosition: SnackPosition.BOTTOM);
      return false;
    } finally {
      isLoading.value = false;
    }
  }

  /// 加载高校列表
  Future<void> loadSchools() async {
    try {
      final response = await _dioClient.dio.get('/school/list');
      if (response.data['code'] == 200) {
        final list = (response.data['data'] as List)
            .map((item) => SchoolModel.fromJson(item as Map<String, dynamic>))
            .toList();
        schools.assignAll(list);
      }
    } catch (_) {}
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
        Get.snackbar('验证码已发送', response.data['message'] ?? '验证码已发送至校园邮箱',
            snackPosition: SnackPosition.BOTTOM,
            duration: const Duration(seconds: 4),
            backgroundColor: Colors.blue.withAlpha(40),
            colorText: Colors.blue[900]);
        return true;
      } else {
        Get.snackbar('申请失败', response.data['message'] ?? '信息校验未通过',
            snackPosition: SnackPosition.BOTTOM,
            backgroundColor: Colors.red.withAlpha(40),
            colorText: Colors.red[900]);
        return false;
      }
    } on DioException catch (e) {
      final msg = e.response?.data is Map
          ? e.response?.data['message']
          : (e.message ?? '网络请求失败');
      Get.snackbar('申请异常', msg ?? '提交失败',
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
        Get.snackbar('认证成功', '恭喜！您已成功通过校园学生身份认证',
            snackPosition: SnackPosition.BOTTOM,
            backgroundColor: Colors.green.withAlpha(40),
            colorText: Colors.green[900]);
        Get.offNamed(AppRoutes.profile);
        return true;
      } else {
        Get.snackbar('核验失败', response.data['message'] ?? '验证码不正确',
            snackPosition: SnackPosition.BOTTOM,
            backgroundColor: Colors.red.withAlpha(40),
            colorText: Colors.red[900]);
        return false;
      }
    } on DioException catch (e) {
      final msg = e.response?.data is Map
          ? e.response?.data['message']
          : (e.message ?? '验证码核验失败');
      Get.snackbar('核验异常', msg ?? '核验失败',
          snackPosition: SnackPosition.BOTTOM,
          backgroundColor: Colors.red.withAlpha(40),
          colorText: Colors.red[900]);
      return false;
    } finally {
      isLoading.value = false;
    }
  }
}
