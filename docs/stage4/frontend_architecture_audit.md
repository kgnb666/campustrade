# CampusTrade Flutter 前端架构与订单系统接入前审计报告

- **审计阶段**：Stage 4-D-0 (Pre-implementation Audit for Order System)
- **目标工程**：`frontend/` (Flutter 3.x / Dart 3.x / GetX / Dio)
- **审计原则**：只审计，不修改代码，不新增页面，不修复问题，不进入编码阶段。

---

## 一、项目整体架构审计

### 1. 目录组织结构
Flutter 工程采用分层与业务功能模块化混合的目录结构：
```text
frontend/lib/
├── api/                    # 网络通信基础客户端 (DioClient 单例)
├── config/                 # 客户端运行时环境与全局配置 (AppConfig)
├── controllers/            # GetX 业务状态控制器 (Auth, Goods, Favorite, History)
├── models/                 # 数据领域模型与 JSON 映射定义
├── pages/                  # 视图页面按业务功能划分
│   ├── auth/               # 登录与注册页
│   ├── favorite/           # 收藏列表页
│   ├── goods/              # 商品中心 (列表、详情、发布、我的发布)
│   ├── history/            # 浏览足迹页
│   ├── home/               # 首页底栏与导航容器
│   └── profile/            # 个人主页与学生认证
├── routes/                 # 命名路由配置与 GetPage 映射
├── services/               # 业务接口网络请求服务与安全存储服务
├── utils/                  # 通用常量与辅助工具
└── widgets/                # 通用组件与徽章
```
- **架构评价**：结构清晰，各层职责边界分明，遵循 `Pages (UI) -> Controllers (State) -> Services (Data/Network) -> DioClient` 的经典分层模式。

### 2. GetX 状态管理使用情况
- **控制器基类**：所有业务控制器（`AuthController`, `GoodsController`, `FavoriteController`, `HistoryController`）均继承自 `GetxController`。
- **响应式状态**：广泛使用 `.obs` 响应式包装（`RxBool`, `RxString`, `RxList`, `Rxn<T>`）。
- **视图监听**：UI 层使用 `Obx(() => ...)` 进行局部响应式监听，性能开销小，无过度刷新问题。
- **服务注入**：
  - `StorageService` 继承 `GetxService`，在 `main()` 中使用 `await Get.putAsync(() => StorageService().init())` 完成异步持久化存储单例注册；
  - `AuthController` 在 `main()` 中通过 `Get.put(AuthController())` 全局常驻；
  - 其余控制器采用惰性或按需创建，但部分页面在 `StatefulWidget` 的 `initState` 中直接 `Get.put(Controller())`，页面销毁时若未注销，可能发生控制器状态缓存堆积。

### 3. 网络请求封装
- **客户端架构**：通过 `lib/api/dio_client.dart` 维护全局唯一的 `DioClient` 单例，内部持有 `Dio` 实例。
- **基础配置**：
  - `connectTimeout = 15000ms`
  - `receiveTimeout = 15000ms`
  - 默认头信息：`Content-Type: application/json`, `Accept: application/json`。
- **拦截器机制**：
  - `onRequest`：检查 `StorageService` 是否就绪，异步读取 Token，自动填充 `Authorization: Bearer <token>` 请求头；
  - `onResponse` 与 `onError`：仅在 `kDebugMode` 下打印日志。
- **存在隐患**：`DioClient` 并未对全局响应错误做拦截处理，各 Service 类（`GoodsService`, `FavoriteService` 等）各自以 `final Dio _dio = DioClient().dio;` 发起直接调用，缺乏统一的响应模型映射层。

### 4. API BaseUrl 配置
- **配置位置**：`lib/config/app_config.dart`
- **代码实现**：
  ```dart
  static const String apiBaseUrl = String.fromEnvironment(
    'API_BASE_URL',
    defaultValue: 'http://127.0.0.1:8080/api',
  );
  ```
- **评价**：支持 `--dart-define=API_BASE_URL=...` 在构建时灵活切换；默认路径带有 `/api` 前缀，与后端 Stage 4-C 中对 `/api/orders` 的映射完全对齐。

### 5. Token / JWT 管理
- **存储机制**：使用 `flutter_secure_storage`，在 Android 平台配置 `encryptedSharedPreferences: true`，具有强安全性。
- **生命周期**：
  - 登录成功：存储 `accessToken` 与 `refresh_token`；
  - 登出：清理 Token 键值；
  - 启动阶段：`AuthController.tryAutoLogin()` 从安全存储中读取 Token，若存在则自动调用 `/user/profile` 恢复登录态，校验失败则清空失效凭据。

### 6. 用户状态管理
- **现状**：全局仅由 `AuthController` 管理当前登录用户信息 (`currentUser: Rxn<UserProfileModel>`) 与登录态 (`isLoggedIn: RxBool`)。
- **缺失点**：工程中**不存在独立的 `UserController`**，所有个人资料维护、信用分读取、高校认证均归并在 `AuthController` 中。

### 7. 路由体系
- **实现方案**：基于 GetX 命名路由，在 `lib/routes/app_routes.dart` 定义常量，在 `lib/routes/app_pages.dart` 维护 `GetPage` 路由表。
- **现状**：覆盖 Home、Auth、Goods、Profile、Favorite、History。
- **缺漏**：完全没有订单模块的任何路由（缺少 `/order/create`, `/order/my`, `/order/detail`）。

### 8. 页面生命周期处理
- 多数复杂页面使用 `StatefulWidget`，在 `initState()` 中执行接口加载，在 `dispose()` 中销毁 `PageController` 或 `TextEditingController`，符合 Flutter 标准规范。

---

## 二、商品模块连接点审计 (`GoodsDetailPage`)

### 1. 核心字段与信息完备性检查
| 检查项 | 状态 | 详细说明 |
| :--- | :---: | :--- |
| **商品 ID** | **已具备** | `GoodsDetailModel.id` 完整保存；页面由 `Get.arguments` 接收并转换为整数 ID。 |
| **卖家信息** | **已具备** | `GoodsDetailModel` 包含 `sellerId`, `sellerUsername`, `sellerNickname`, `sellerAvatar`, `sellerVerified`, `sellerSchoolName`, `sellerCreditScore`, `sellerTradeCount`, `sellerGoodReviewCount`；并在 `_buildSellerCard` 完整展示了头像、昵称、学生认证状态与信用指标。 |
| **商品状态展示** | **严重缺失** | 详情页主体**未展示商品当前状态徽章**，仅展示了成色标签（`goods.conditionLevel` 如“9成新”）；买家无法从界面直观获知商品当前是处于在售、锁定、还是已售出。 |
| **操作入口位置** | **存在缺陷** | 底部操作栏左侧为收藏按钮，右侧仅有一个占满整栏的“联系卖家”按钮（点击弹窗提示暂无 IM）；**目前完全不存在“立即购买”或“下单面交”入口**！ |

### 2. 商品状态支持度审计 (`ON_SALE`, `LOCKED`, `SOLD`, `OFF_SHELF`)
- **数据模型**：`GoodsItemModel` 与 `GoodsDetailModel` 拥有 `String status` 字段，默认 fallback 为 `'ON_SALE'`。
- **现有状态处理**：
  - `MyGoodsPage._buildStatusBadge` 分支判断了 `ON_SALE`（在售中）、`OFF_SHELF`（已下架）、`SOLD`（已售出）；
  - **未覆盖 `LOCKED` 状态**：当商品被买家下单进入 `LOCKED`（锁定交易中）时，现有代码在 `switch` 中只能命中 `default`，显示橘色字符 `"LOCKED"`，缺乏本地化友好文案与专用样式；
  - `GoodsDetailPage` 中卖家视角仅判断了 `goods.status == 'ON_SALE' ? '下架商品' : '重新上架'`，买家视角完全未针对 `LOCKED` 或 `SOLD` 做置灰与阻断。

---

## 三、用户模块审计

### 1. 订单流程关键用户字段需求
订单全生命周期界面需要消费以下字段：
- 买家端：`buyerId`, `buyerNickname`, `buyerAvatar`
- 卖家端：`sellerId`, `sellerNickname`, `sellerAvatar`

### 2. 控制器与上下文支持现状
1. **是否存在 `UserController`**：
   - **不存在**。工程中只有 `AuthController`。
2. **当前登录用户信息获取途径**：
   - 通过 `Get.find<AuthController>().currentUser.value` 可以直接获取当前用户的 `id`, `username`, `nickname`, `avatar`；
   - 能够满足当前用户作为买家（下单时自身即为买家）或作为卖家（接单时自身即为卖家）的身份识别。
3. **交易对手方信息获取途径**：
   - **下单前**：买家在浏览商品详情进入确认下单页时，对手方（卖家）的 `sellerId`, `sellerNickname`, `sellerAvatar` 直接来源于 `GoodsDetailModel`；
   - **下单后**：在“我的订单”列表与“订单详情”页面中，后端 Stage 4-C 返回的 `OrderVO` 已完整冗余包含了 `buyerId`, `buyerNickname`, `buyerAvatar`, `sellerId`, `sellerNickname`, `sellerAvatar`；
   - **结论**：无需新增独立的 `UserController`，现有架构与后端 VO 契约完全支持订单展示。

---

## 四、API 层审计

### 1. 现状清单
| 组件 | 现状 | 评估 |
| :--- | :--- | :--- |
| **ApiClient / HttpService** | 仅有 `DioClient` 单例，无面向对象的抽象层基类 | 现有 Service 直接使用 `_dio.get/post`，风格统一，但缺少对 HTTP 状态码的统一异常映射 |
| **Result&lt;T&gt; / ApiResponse&lt;T&gt;** | 已有 `lib/models/api_response.dart` (`ApiResponse<T>`) | 虽有泛型响应模型，但现有 Service 多数使用 `response.data['code']` 手动解析 Map，未充分复用 |
| **错误处理** | 局部 catch `DioException` 并读取 `e.response?.data['message']` | 各业务 Service 异常时多以 `return false` 或 `return []` 吞掉异常，上层未能收到详细后端错误文案 |
| **401 自动跳转登录** | **完全未实现** | `DioClient` 的 `onError` 仅打印 debug 日志，未拦截 `401 Unauthorized`；若 Token 过期，接口静默失败或提示普通网络错误，未能自动跳转 `/login` |

---

## 五、审计总结与风险预警

1. **核心连接点缺失**：商品详情页缺少“立即购买/下单面交”按钮，买家无法进入下单流程；
2. **状态感知缺漏**：商品处于 `LOCKED`（已被其他买家锁定）或 `SOLD`（已成交）时，前端未展示状态徽章，亦未做下单按钮置灰保护；
3. **401 凭证失效降级**：网络层缺乏 401 自动登出并重定向机制，依赖页面手动校验；
4. **用户模块无阻碍**：无需新建 `UserController`，依赖 `AuthController.currentUser` 搭配后端 `OrderVO` 即可完美闭环。
