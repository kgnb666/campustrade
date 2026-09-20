# Stage 4-D：Flutter 订单系统前端接入与交互设计规划

- **目标工程**：`frontend/`
- **实施阶段**：Stage 4-D
- **前置依赖**：Stage 4-B-2 (后端状态机完成) + Stage 4-C (后端订单 REST API 完成) + Stage 4-D-0 (前端审计完成)
- **核心目标**：在 Flutter 客户端完整打通二手交易下单确认、买卖双方订单列表、订单生命周期详情与面交核销闭环。

---

## 一、页面设计方案 (UI/UX)

```
[商品详情页 GoodsDetailPage]
        │
        ├── 点击【立即购买/下单面交】
        ▼
[确认下单页 OrderCreatePage]
        │
        ├── 填写约定地点与留言，点击【提交订单】
        ▼
[订单详情页 OrderDetailPage] ◄────────┐
        ▲                              │ 点击订单项
        │                              │
[个人中心 ProfilePage] ──点击【我的订单】──> [我的订单页 OrderListPage]
                                              ├── Tab 1: 我买到的 (BUYER)
                                              └── Tab 2: 我卖出的 (SELLER)
```

### 1. 确认下单页 (`OrderCreatePage` - 新增)
- **路由路径**：`/order/create`
- **入参传递**：`GoodsDetailModel`（从商品详情页携带传入）。
- **页面布局**：
  - **顶部面交安全须知卡片**：醒目黄色/蓝色提示条，提醒“校园面交请选择校内公共区域（如食堂门口、图书馆），当面验收商品无误后双方交割”。
  - **商品信息快照卡片**：展示商品主图封面、标题、成色标签、价格、卖家昵称、高校认证勋章。
  - **面交信息输入区**：
    - 面交地点输入框（`meetLocation`）：自动预填 `goods.location`，允许买家自定义修改；
    - 买家留言输入框（`buyerMessage`）：支持最多 500 字多行留言，附字符数统计。
  - **底部悬浮结算栏**：
    - 左侧：合计应付金额（¥ XX.XX，醒目大号价格）。
    - 右侧：“提交订单”按钮（带点击 Loading 动画与防重复提交锁定）。

### 2. 我的订单页 (`OrderListPage` - 新增)
- **路由路径**：`/order/my`
- **页面布局**：
  - **顶部 AppBar**：标题“我的订单”，带刷新按钮。
  - **双视角 Tab 栏**：
    - `Tab 1: 我买到的`（查询 `role=BUYER`）
    - `Tab 2: 我卖出的`（查询 `role=SELLER`）
  - **状态筛选 Chip**：横向滚动筛选条（全部、待卖家确认、待面交、已完成、已取消）。
  - **订单卡片列表 (`ListView.separated`)**：
    - 卡片 Header：对手方昵称/头像 + 状态徽章（绿色“待确认”、蓝色“待面交”、深灰“已完成”、浅灰“已取消”）；
    - 卡片 Body：点击跳转订单详情，展示商品缩略图快照、商品标题快照、约定面交地点、实付总价；
    - 卡片 Footer：下单时间 + 快捷动作按钮（如待确认时卖家卡片显示“确认接单”；待面交时显示“完成交易”）。
  - **缺省与加载态**：列表为空时展示友好的“暂无相关订单”插画与返回首页按钮；支持下拉刷新 (`RefreshIndicator`) 与上拉分页加载。

### 3. 订单详情页 (`OrderDetailPage` - 新增)
- **路由路径**：`/order/detail`
- **入参传递**：`orderId`
- **页面布局**：
  - **顶部大状态横幅**：彩色背景展示当前大图标 + 订单状态（如“待卖家确认接单” / “待线下验货面交” / “交易已圆满完成” / “订单已取消”）。
  - **面交约定与核销卡片**：展示约定地点、买家留言。
  - **交易对手方信息卡片**：展示买家或卖家的头像、昵称、学生认证学校、信用积分、成交笔数，支持点击联系对方。
  - **商品快照信息卡片**：点击可回溯查看原始商品，展示下单快照价格、快照标题、主图。
  - **订单生命周期 Timeline**：
    - 1. 买家提交订单 (展示时间)
    - 2. 卖家确认接单 (展示时间，未达成显示待确认)
    - 3. 线下验货交割 (展示时间，已取消显示取消原因与取消人)
  - **底部动作条 (根据当前用户身份与状态机动态渲染)**：
    - `WAIT_SELLER_CONFIRM`:
      - 卖家：红色“取消订单” + 绿色主要按钮“确认接单”
      - 买家：灰色“取消订单”
    - `WAIT_MEET`:
      - 买家/卖家：灰色“取消订单” + 蓝色主要按钮“确认完成交易”
    - `COMPLETED`: 浅绿状态条“交易已完成交割”
    - `CANCELLED`: 浅红状态条“订单已取消 (原因: xxx)”

### 4. 改造已有页面
- **`GoodsDetailPage`**：
  - 底部操作栏左侧保留“收藏”，右侧重构：
    - 若当前用户为卖家：保留“管理商品 / 重新上架 / 下架”；
    - 若当前用户为非卖家：
      - `ON_SALE`：展示“联系卖家”(OutlinedButton) 与“立即购买”(ElevatedButton)；
      - `LOCKED`：按钮置灰并提示“商品锁定中 (他人已下单)”；
      - `SOLD`：按钮置灰并提示“商品已售出”；
      - `OFF_SHELF`：提示“商品已下架”。
- **`ProfilePage`**：
  - 在卡片列表中新增“我的订单”菜单项（带货运/交易图标与副标题“查看我买入与卖出的闲置订单”），点击导航到 `AppRoutes.orderList`。

---

## 二、Controller 设计方案 (`OrderController`)

文件路径：`frontend/lib/controllers/order_controller.dart`

```dart
class OrderController extends GetxController {
  final OrderService _orderService = OrderService();
  final AuthController _authController = Get.find<AuthController>();

  // 响应式状态
  final RxList<OrderModel> buyerOrders = <OrderModel>[].obs;
  final RxList<OrderModel> sellerOrders = <OrderModel>[].obs;
  final Rxn<OrderModel> currentOrder = Rxn<OrderModel>();
  
  final RxBool isListLoading = false.obs;
  final RxBool isDetailLoading = false.obs;
  final RxBool isSubmitting = false.obs;

  final RxString selectedStatus = ''.obs; // 状态筛选
  final RxInt activeTabIndex = 0.obs;     // 0: BUYER, 1: SELLER

  // 核心方法
  Future<bool> createOrder({required int goodsId, String? meetLocation, String? buyerMessage});
  Future<void> loadOrders({required String role, bool refresh = false});
  Future<void> loadOrderDetail(int orderId);
  Future<bool> confirmOrder(int orderId);
  Future<bool> cancelOrder(int orderId, String cancelReason);
  Future<bool> completeOrder(int orderId);
}
```

- **防重复提交**：使用 `isSubmitting` 控制按钮 Loading 与不可点，避免用户多次连击产生重复订单。
- **状态联动**：接单、取消或完成成功后，自动同步更新 `currentOrder` 并刷新买家/卖家本地列表。

---

## 三、Model 设计方案 (`OrderModel`)

文件路径：`frontend/lib/models/order_model.dart`

```dart
class OrderModel {
  final int id;
  final String orderNo;
  final int goodsId;
  final String goodsTitleSnapshot;
  final double goodsPriceSnapshot;
  final String? goodsImageSnapshot;
  final String? meetLocation;
  final String? buyerMessage;
  final String? sellerReply;
  
  final int buyerId;
  final String buyerUsername;
  final String? buyerNickname;
  final String? buyerAvatar;
  
  final int sellerId;
  final String sellerUsername;
  final String? sellerNickname;
  final String? sellerAvatar;
  
  final String orderStatus; // WAIT_SELLER_CONFIRM, WAIT_MEET, COMPLETED, CANCELLED
  final String statusDesc;  // 待卖家确认, 待面交, 已完成, 已取消
  final String? cancelReason;
  final int? cancelledBy;
  
  final String? confirmedTime;
  final String? completedTime;
  final String? cancelledTime;
  final String createdTime;
  final String updatedTime;

  // 构造函数与 fromJson
  factory OrderModel.fromJson(Map<String, dynamic> json);

  // 状态辅助判断 Getter
  bool get isWaitSellerConfirm => orderStatus == 'WAIT_SELLER_CONFIRM';
  bool get isWaitMeet => orderStatus == 'WAIT_MEET';
  bool get isCompleted => orderStatus == 'COMPLETED';
  bool get isCancelled => orderStatus == 'CANCELLED';
}
```

---

## 四、API 封装设计方案 (`OrderService`)

文件路径：`frontend/lib/services/order_service.dart`

对接 Stage 4-C 已经验证通过的后端 6 大 RESTful 路由：

| 方法 | 请求路径 | 传输格式 | 说明 |
| :--- | :--- | :--- | :--- |
| `createOrder` | `POST /api/orders` | JSON: `goodsId, meetLocation, buyerMessage` | 创建订单，返回 `OrderModel` |
| `getMyOrders` | `GET /api/orders/my` | Query: `role, status, page, size` | 分页获取订单，返回列表及分页元数据 |
| `getOrderDetail` | `GET /api/orders/{id}` | Path: `id` | 获取订单详情，返回 `OrderModel` |
| `confirmOrder` | `PUT /api/orders/{id}/confirm` | Path: `id` | 卖家接单，返回更新后 `OrderModel` |
| `cancelOrder` | `PUT /api/orders/{id}/cancel` | Path: `id` + JSON: `cancelReason` | 取消订单，返回更新后 `OrderModel` |
| `completeOrder`| `PUT /api/orders/{id}/complete` | Path: `id` | 完成交易，返回更新后 `OrderModel` |

- **错误与异常处理规范**：
  - 拦截 `DioException`，提取 `e.response?.data['message']`；
  - 针对 `401 Unauthorized` 自动弹窗并调用 `Get.toNamed(AppRoutes.login)`；
  - 针对 `403 Forbidden` 友好提示“您无权操作该订单”。

---

## 五、自动化测试计划 (Testing Plan)

新增 Widget 测试套件：`frontend/test/stage4_d_widget_test.dart`

测试用例设计：
1. **商品详情页下单入口测试**：
   - 在售商品买家视角显示“立即购买”；
   - 卖家自己查看商品不显示“立即购买”；
   - 商品处于 `LOCKED` 或 `SOLD` 时显示禁用状态并提示无法下单；
   - 未登录用户点击“立即购买”拦截并跳转登录页。
2. **确认下单页交互测试**：
   - 验证商品快照卡片与默认面交地点渲染；
   - 输入买家留言，点击“提交订单”发出正确的 JSON 载荷；
   - 提交成功后自动导航至订单详情页。
3. **我的订单页面 Tab 与列表测试**：
   - 验证“我买到的”与“我卖出的” Tab 切换触发不同的 `role` 请求；
   - 验证订单卡片中各状态徽章的文案与渲染；
   - 验证空列表状态展示。
4. **订单详情与状态机动作测试**：
   - 卖家视角下处于 `WAIT_SELLER_CONFIRM` 时渲染“确认接单”按钮，点击后向后端发送 `PUT /api/orders/{id}/confirm`；
   - 处于 `WAIT_MEET` 时渲染“确认完成交易”按钮，点击后向后端发送 `PUT /api/orders/{id}/complete`；
   - 点击“取消订单”弹出原因对话框，验证空字符串校验拦截，输入原因后发送 `PUT /api/orders/{id}/cancel`。

---

Stage 4-D 规划已全面就绪，各模块职责、数据流向与异常边界明确，等待指令后即可平滑进入开发与测试阶段。
