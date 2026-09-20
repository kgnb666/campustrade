import 'package:flutter/foundation.dart';
import 'package:get/get.dart';
import '../api/review_api.dart';
import '../config/app_config.dart';
import '../utils/api_error.dart';
import '../models/api_response.dart';
import '../models/review.dart';
import 'auth_controller.dart';
import 'order_controller.dart';

/// 评价提交状态机
enum ReviewSubmitState {
  idle,
  submitting,
  success,
  error,
}

/// 评价与信用中心状态管理控制器
class ReviewController extends GetxController {
  final ReviewApi _reviewApi;

  ReviewController({ReviewApi? reviewApi})
      : _reviewApi = reviewApi ?? ReviewApi();

  /// 表单提交状态
  final Rx<ReviewSubmitState> submitState = ReviewSubmitState.idle.obs;

  /// 表单错误信息
  final RxString errorMessage = ''.obs;

  /// 评分星级 (0 表示未选择，合法区间为 1~5)
  final RxInt selectedScore = 0.obs;

  /// 评价内容正文 (最多 500 字)
  final RxString content = ''.obs;

  /// 选中的标签列表
  final RxList<String> selectedTags = <String>[].obs;

  /// 是否匿名评价 (默认 false)
  final RxBool isAnonymous = false.obs;

  /// 订单评价状态缓存 `Map<String, OrderReviewStatusModel>`（键为订单 ID，ID 用字符串承载以免 Web 端丢精度）
  final RxMap<String, OrderReviewStatusModel> orderReviewStatusMap =
      <String, OrderReviewStatusModel>{}.obs;

  /// 订单状态拉取 Loading
  final RxBool loadingOrderStatus = false.obs;

  /// 商品评价列表缓存 `Map<String, List<ReviewModel>>`
  final RxMap<String, List<ReviewModel>> goodsReviewsMap =
      <String, List<ReviewModel>>{}.obs;

  /// 商品评价拉取 Loading
  final RxBool loadingGoodsReviews = false.obs;

  /// 用户收到的评价列表
  final RxList<ReviewModel> userReviews = <ReviewModel>[].obs;

  /// 用户评价拉取 Loading
  final RxBool loadingUserReviews = false.obs;

  /// 已发起过「收到的评价」加载的用户 ID。
  ///
  /// 个人中心页面会在 build 阶段按需触发一次加载，若仅以「列表为空」作为判断条件，
  /// 那么"确实没有评价"的用户会陷入：加载完成 → 列表仍为空 → 再次触发 → 无限请求 + 无限重建（页面闪烁）。
  /// 因此改为按用户记录"是否已发起过"，每个用户只自动加载一次，失败时用页面上的刷新按钮重试。
  String? loadedUserReviewsUserId;

  /// 是否正在提交
  bool get isSubmitting => submitState.value == ReviewSubmitState.submitting;

  /// 推荐白名单快捷标签列表 (根据身份适配)
  List<String> getPresetTags({bool isBuyer = true}) {
    if (isBuyer) {
      // 买家对卖家
      return const [
        '守时诚信',
        '物美价廉',
        '成色极佳',
        '描述相符',
        '沟通友好',
        '包装仔细',
      ];
    } else {
      // 卖家对买家
      return const [
        '爽快买家',
        '守时面交',
        '沟通顺畅',
        '诚信交易',
        '态度友好',
      ];
    }
  }

  /// 设置评分 (强制 1~5)
  void setScore(int score) {
    if (score >= 1 && score <= 5) {
      selectedScore.value = score;
    }
  }

  /// 切换标签选中状态
  void toggleTag(String tag) {
    if (selectedTags.contains(tag)) {
      selectedTags.remove(tag);
    } else {
      if (selectedTags.length < 5) {
        selectedTags.add(tag);
      }
    }
  }

  /// 设置评价内容 (限制 500 字)
  void setContent(String text) {
    if (text.length <= 500) {
      content.value = text;
    } else {
      content.value = text.substring(0, 500);
    }
  }

  /// 设置匿名开关
  void setAnonymous(bool value) {
    isAnonymous.value = value;
  }

  /// 重置评价表单
  void resetForm() {
    selectedScore.value = 0;
    content.value = '';
    selectedTags.clear();
    isAnonymous.value = false;
    submitState.value = ReviewSubmitState.idle;
    errorMessage.value = '';
  }

  /// 获取指定订单的双向评价状态
  Future<OrderReviewStatusModel?> fetchOrderReviewStatus(String orderId) async {
    loadingOrderStatus.value = true;
    try {
      final res = await _reviewApi.getOrderReviewStatus(orderId);
      if (res.isSuccess && res.data != null) {
        orderReviewStatusMap[orderId] = res.data!;
        return res.data;
      }
    } catch (e) {
      debugPrint('[ReviewController] fetchOrderReviewStatus error: $e');
    } finally {
      loadingOrderStatus.value = false;
    }
    return null;
  }

  /// 获取商品评价列表
  Future<List<ReviewModel>> fetchGoodsReviews(
    String goodsId, {
    int page = 1,
    int size = AppConfig.reviewPageSize,
    bool refresh = true,
  }) async {
    loadingGoodsReviews.value = true;
    try {
      final res = await _reviewApi.getReviewsByGoods(goodsId,
          page: page, size: size);
      if (res.isSuccess && res.data != null) {
        final records = res.data!.records;
        if (refresh) {
          goodsReviewsMap[goodsId] = records;
        } else {
          final current = goodsReviewsMap[goodsId] ?? [];
          goodsReviewsMap[goodsId] = [...current, ...records];
        }
        return goodsReviewsMap[goodsId]!;
      }
    } catch (e) {
      debugPrint('[ReviewController] fetchGoodsReviews error: $e');
    } finally {
      loadingGoodsReviews.value = false;
    }
    return goodsReviewsMap[goodsId] ?? [];
  }

  /// 获取用户收到的评价列表 (个人信用中心)
  Future<void> fetchUserReviews(
    String userId, {
    int page = 1,
    int size = AppConfig.reviewPageSize,
    bool refresh = true,
  }) async {
    // 先登记"已为该用户发起过加载"，避免页面 build 阶段反复触发（空列表也会被判为未加载）
    loadedUserReviewsUserId = userId;
    loadingUserReviews.value = true;
    try {
      final res =
          await _reviewApi.getReviewsByUser(userId, page: page, size: size);
      if (res.isSuccess && res.data != null) {
        if (refresh) {
          userReviews.assignAll(res.data!.records);
        } else {
          userReviews.addAll(res.data!.records);
        }
      }
    } catch (e) {
      debugPrint('[ReviewController] fetchUserReviews error: $e');
    } finally {
      loadingUserReviews.value = false;
    }
  }

  /// 提交交易评价
  Future<ApiResponse<ReviewModel>> submitReview(String orderId) async {
    // 1. 防重复提交并发保护
    if (isSubmitting) {
      return ApiResponse<ReviewModel>(
        code: 400,
        message: '评价正在提交中，请勿重复操作',
        data: null,
      );
    }

    // 2. 星级必选校验
    if (selectedScore.value < 1 || selectedScore.value > 5) {
      submitState.value = ReviewSubmitState.error;
      errorMessage.value = '请选择评分星级 (1~5星)';
      return ApiResponse<ReviewModel>(
        code: 400,
        message: errorMessage.value,
        data: null,
      );
    }

    // 3. 字数限制校验
    if (content.value.length > 500) {
      submitState.value = ReviewSubmitState.error;
      errorMessage.value = '评价内容最多500字';
      return ApiResponse<ReviewModel>(
        code: 400,
        message: errorMessage.value,
        data: null,
      );
    }

    submitState.value = ReviewSubmitState.submitting;
    errorMessage.value = '';

    try {
      final req = CreateReviewRequest(
        orderId: orderId,
        score: selectedScore.value,
        content: content.value.trim().isNotEmpty ? content.value.trim() : null,
        tags: selectedTags.toList(),
        isAnonymous: isAnonymous.value,
      );

      final res = await _reviewApi.createReview(req);

      if (res.isSuccess && res.data != null) {
        submitState.value = ReviewSubmitState.success;

        // 刷新该订单的双向评价状态
        await fetchOrderReviewStatus(orderId);

        // 跨控制器同步: 刷新订单详情
        if (Get.isRegistered<OrderController>()) {
          await Get.find<OrderController>().fetchOrderDetail(orderId);
        }

        // 跨控制器同步: 刷新当前用户信用档案与个人资料
        if (Get.isRegistered<AuthController>()) {
          await Get.find<AuthController>().fetchProfile();
        }

        return res;
      } else {
        submitState.value = ReviewSubmitState.error;
        errorMessage.value = res.message;
        return res;
      }
    } catch (e, stack) {
      debugPrint('[ReviewController] submitReview error: $e\n$stack');
      submitState.value = ReviewSubmitState.error;
      errorMessage.value = describeApiError(e, fallback: '提交评价失败，请稍后重试');
      return ApiResponse<ReviewModel>(
        code: 500,
        message: errorMessage.value,
        data: null,
      );
    }
  }
}
