# Stage 4-C: 订单 REST API 接口层与权限控制实现总结报告

- **执行阶段**：Stage 4-C (Order REST API & Permission Control)
- **涵盖范围**：DTO / VO / Controller / 权限控制 / 单元与集成测试
- **前置依赖**：Stage 4-B-2 (Domain Model & State Machine)

---

## 一、新增与修改文件清单

1. **[NEW]** [`backend/src/main/java/com/campustrade/dto/order/CreateOrderRequest.java`](file:///d:/wkk/Second-hand%20trading%20platform/backend/src/main/java/com/campustrade/dto/order/CreateOrderRequest.java)
   - 下单入参 DTO：包含 `goodsId` (@NotNull), `meetLocation` (<=200), `buyerMessage` (<=500)。
2. **[NEW]** [`backend/src/main/java/com/campustrade/dto/order/CancelOrderRequest.java`](file:///d:/wkk/Second-hand%20trading%20platform/backend/src/main/java/com/campustrade/dto/order/CancelOrderRequest.java)
   - 取消订单入参 DTO：包含 `cancelReason` (@NotBlank, <=500)。
3. **[NEW]** [`backend/src/main/java/com/campustrade/dto/order/OrderQueryRequest.java`](file:///d:/wkk/Second-hand%20trading%20platform/backend/src/main/java/com/campustrade/dto/order/OrderQueryRequest.java)
   - 分页查询入参 DTO：支持角色视角 `role` (BUYER / SELLER)、状态筛选 `status`、页码 `page` (@Min(1))、条数 `size` (@Min(1), @Max(100))。
4. **[NEW]** [`backend/src/main/java/com/campustrade/vo/order/OrderUserInfoVO.java`](file:///d:/wkk/Second-hand%20trading%20platform/backend/src/main/java/com/campustrade/vo/order/OrderUserInfoVO.java)
   - 用户脱敏视图对象：包含 `id`, `username`, `nickname`, `avatar`。
5. **[NEW]** [`backend/src/main/java/com/campustrade/vo/order/OrderVO.java`](file:///d:/wkk/Second-hand%20trading%20platform/backend/src/main/java/com/campustrade/vo/order/OrderVO.java)
   - 订单响应视图对象：禁止直接返回 Entity，包含订单号、商品快照、买卖家信息及嵌套对象、订单状态与中文描述、全节点时序时间戳。
6. **[MODIFIED]** [`backend/src/main/java/com/campustrade/service/OrderService.java`](file:///d:/wkk/Second-hand%20trading%20platform/backend/src/main/java/com/campustrade/service/OrderService.java)
   - 增加 `getMyOrders`, `getOrderDetail`, `convertToVO` 及 `createOrder(buyerId, request)` 接口声明。
7. **[MODIFIED]** [`backend/src/main/java/com/campustrade/service/impl/OrderServiceImpl.java`](file:///d:/wkk/Second-hand%20trading%20platform/backend/src/main/java/com/campustrade/service/impl/OrderServiceImpl.java)
   - 实现分页查询、用户批量加载（避免 N+1）、订单详情越权校验与 VO 转换构建器。
8. **[NEW]** [`backend/src/main/java/com/campustrade/controller/OrderController.java`](file:///d:/wkk/Second-hand%20trading%20platform/backend/src/main/java/com/campustrade/controller/OrderController.java)
   - 订单 REST API 控制器，提供 6 个核心路由。
9. **[NEW]** [`backend/src/test/java/com/campustrade/CampusTradeStage4CTests.java`](file:///d:/wkk/Second-hand%20trading%20platform/backend/src/test/java/com/campustrade/CampusTradeStage4CTests.java)
   - 涵盖 10 项核心维度的自动化集成测试套件。

---

## 二、API 契约设计说明

所有接口挂载在 `/api/orders` (同时兼容 `/orders`)，统一采用 `Result<T>` 封装。

| HTTP 方法 | 接口路径 | 描述 | 请求体 / Query 参数 | 响应类型 | 权限控制 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `POST` | `/api/orders` | 买家创建订单 | `CreateOrderRequest` (JSON) | `Result<OrderVO>` | 登录用户（买家不能买自己商品） |
| `GET` | `/api/orders/my` | 分页查询我的订单 | `role` (BUYER/SELLER), `status`, `page` (>=1), `size` (1~100) | `Result<IPage<OrderVO>>` | 登录用户（仅查本人数据） |
| `GET` | `/api/orders/{id}` | 查询订单详情 | 路径参数 `id` | `Result<OrderVO>` | 买家本人或卖家本人（第三方 403） |
| `PUT` | `/api/orders/{id}/confirm` | 卖家确认接单 | 路径参数 `id` | `Result<OrderVO>` | 仅限卖家本人（非卖家 403） |
| `PUT` | `/api/orders/{id}/cancel` | 取消订单 | 路径参数 `id` + `CancelOrderRequest` (JSON) | `Result<OrderVO>` | 买家或卖家本人（非当事人 403） |
| `PUT` | `/api/orders/{id}/complete` | 完成交易 | 路径参数 `id` | `Result<OrderVO>` | 买家或卖家本人（非当事人 403） |

---

## 三、权限验证与安全防护

1. **未登录保护 (401 Unauthorized)**：
   - Spring Security 无状态 JWT 过滤器自动拦截未附带合法 Bearer Token 的请求；
   - 统一由 `AuthenticationEntryPoint` 返回标准 401 响应：`{"code":401,"message":"未登录或登录已失效，请重新登录","data":null}`。
2. **水平越权拦截 (403 Forbidden)**：
   - 订单详情 `GET /api/orders/{id}`：操作人必须为订单 `buyer_id` 或 `seller_id`，第三方访问触发 `AccessDeniedException`，返回 403；
   - 卖家接单 `PUT /api/orders/{id}/confirm`：严格校验当前用户为 `seller_id`，买家或第三方尝试接单触发 403；
   - 取消与完成 `PUT /api/orders/{id}/cancel` & `complete`：非买家且非卖家无权操作，触发 403。
3. **输入参数安全防御 (400 Bad Request)**：
   - Jakarta Validation 强校验：`cancelReason` 必须 `@NotBlank`（空串、空白字符或缺失直接报 400）；
   - 分页极值限制：`page < 1` 拦截报 400，`size < 1` 或 `size > 100` 拦截报 400，防止深度翻页或恶意批量拖库。
4. **数据库异常隐藏保护**：
   - 状态机校验与业务逻辑异常统一封装为 `OrderBusinessException` 与 `BusinessException`；
   - 全局异常处理器统一拦截转换，杜绝向前端暴露任何 SQL 或底表敏感信息。

---

## 四、自动化测试执行报告

### 1. Stage 4-C 专项集成测试 (`CampusTradeStage4CTests`)
```text
[INFO] Running com.campustrade.CampusTradeStage4CTests
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.716 s -- in com.campustrade.CampusTradeStage4CTests
[INFO] Results:
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

| 用例编号 | 测试方法 | 覆盖指标 | 结果 |
| :--- | :--- | :--- | :--- |
| 1 | `test01_create_order_api_success` | 创建订单 API 成功，快照完整、状态变为 WAIT_SELLER_CONFIRM，商品锁定为 LOCKED | **PASS** |
| 2 | `test02_unauthenticated_returns_401` | 6 个订单接口未登录请求统一拦截返回 401 | **PASS** |
| 3 | `test03_query_orders_success` | 买家/卖家视角分页查询我的订单成功，买家/卖家分别获取订单详情成功 | **PASS** |
| 4 | `test04_third_party_view_order_returns_403` | 第三方无关用户调用 `GET /api/orders/{id}` 拦截返回 403 | **PASS** |
| 5 | `test05_seller_confirm_order_success` | 卖家调用 `PUT /api/orders/{id}/confirm` 成功，状态变为 WAIT_MEET | **PASS** |
| 6 | `test06_non_seller_confirm_order_returns_403` | 买家或第三方尝试确认接单拦截返回 403 | **PASS** |
| 7 | `test07_cancel_order_success` | 取消订单成功，状态变为 CANCELLED，商品原子恢复为 ON_SALE | **PASS** |
| 8 | `test08_cancel_order_empty_reason_fails` | 取消原因为 null、空串、纯空白字符均拦截返回 400 | **PASS** |
| 9 | `test09_complete_trade_success` | 完成交易成功，状态变为 COMPLETED，商品变 SOLD，双方 trade_count 自增 | **PASS** |
| 10 | `test10_pagination_param_limits` | 分页参数 `page < 1` 或 `size < 1` 或 `size > 100` 拦截返回 400 | **PASS** |

### 2. 全量回归测试结果 (`mvn test`)
```text
[INFO] Results:
[INFO] Tests run: 124, Failures: 0, Errors: 0, Skipped: 0
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] Total time:  14.508 s
[INFO] Finished at: 2026-09-17T17:39:32+08:00
[INFO] ------------------------------------------------------------------------
```
- **全部测试通过率**：**124 / 124 (100% PASS)**，零失败、零错误、零跳过。
