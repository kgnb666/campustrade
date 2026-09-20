# CampusTrade 校园二手交易平台 Stage 5-D 工程完成报告

> **阶段名称**：Stage 5-D：评价系统后端开发与信用联动落地  
> **状态**：已完成 (COMPLETED)  
> **自动化测试结果**：后端 146/146 全量通过 (Failures: 0, Errors: 0)；前端 `flutter analyze` 0 issues。  
> **核心产出**：Flyway V6 数据库迁移、`Review` 领域实体与 Mapper、`ReviewService` 业务实现、Spring 本地领域事件发布与信用联动、`ReviewController` REST API 与 `Result<T>` 统一包装、全套 12 项场景自动化测试。

---

## 一、修改与新增文件列表

| 序号 | 文件路径 | 模块 / 层级 | 变更类型 | 说明 |
| :--- | :--- | :--- | :--- | :--- |
| 1 | `backend/src/main/resources/db/migration/V6__create_review_domain.sql` | 数据库迁移 | **新增** | Flyway V6 脚本：创建 `campus_trade.review` 表、CHECK 约束与 4 组物理索引 |
| 2 | `backend/src/main/java/com/campustrade/enums/ReviewStatus.java` | 枚举层 | **新增** | 评价展示状态枚举 (`VISIBLE`, `AUDIT_REJECTED`) |
| 3 | `backend/src/main/java/com/campustrade/entity/Review.java` | 实体层 | **新增** | 评价持久化实体，映射 `campus_trade.review` 数据表 |
| 4 | `backend/src/main/java/com/campustrade/mapper/ReviewMapper.java` | 数据访问层 | **新增** | 继承 MyBatis-Plus `BaseMapper<Review>` |
| 5 | `backend/src/main/java/com/campustrade/dto/review/CreateReviewRequest.java` | 传输层 DTO | **新增** | 创建交易评价入参 DTO（带 Jakarta 参数校验与匿名别名支持） |
| 6 | `backend/src/main/java/com/campustrade/vo/review/ReviewVO.java` | 视图层 VO | **新增** | 评价信息 VO（支持匿名脱敏组装） |
| 7 | `backend/src/main/java/com/campustrade/vo/review/OrderReviewStatusVO.java` | 视图层 VO | **新增** | 订单双向评价状态视图 VO |
| 8 | `backend/src/main/java/com/campustrade/event/ReviewCreatedEvent.java` | 领域事件 | **新增** | Spring 本地领域事件载荷实体 |
| 9 | `backend/src/main/java/com/campustrade/listener/CreditReviewEventListener.java` | 事件监听器 | **新增** | 监听 `ReviewCreatedEvent` 并驱动 `CreditService` 进行阶梯积分与流水沉淀 |
| 10 | `backend/src/main/java/com/campustrade/service/ReviewService.java` | 领域服务层 | **新增** | 评价业务接口定义 |
| 11 | `backend/src/main/java/com/campustrade/service/impl/ReviewServiceImpl.java` | 领域服务层 | **新增** | 评价业务实现类（越权防护、防自买自评、防重评价、XSS 清洗与事件发布） |
| 12 | `backend/src/main/java/com/campustrade/controller/ReviewController.java` | API 控制层 | **新增** | RESTful 控制器（挂载 `/api/reviews`，全线受 JWT 保护） |
| 13 | `backend/src/test/java/com/campustrade/CampusTradeStage5DTests.java` | 测试套件 | **新增** | Stage 5-D 专项 12 场景自动化测试套件 |
| 14 | `backend/src/test/java/com/campustrade/CampusTradeStage5BTests.java` | 测试套件 | **调整** | 兼容性重构：Flyway 版本校验向前兼容 `>= 5` |
| 15 | `docs/stage5/Stage5-D-review-implementation-report.md` | 文档报告 | **新增** | 本实施与验收报告 |

---

## 二、数据库迁移结果

Spring Boot 启动与自动化测试过程中，Flyway 成功加载并应用了 `V6__create_review_domain.sql`。

- **Flyway Info 状态**：
  ```
  Current version of schema "campus_trade": 6
  Schema "campus_trade" is up to date. No migration necessary.
  ```
- **表结构及物理约束验证**：
  - `campus_trade.review` 表成功建立，包含完整的 12 个字段：`id`, `order_id`, `goods_id`, `reviewer_id`, `reviewed_user_id`, `score`, `content`, `tags`, `is_anonymous`, `status`, `created_time`, `updated_time`；
  - `chk_review_score_range` CHECK 约束生效，数据库级强校验 `1 <= score <= 5`；
  - `uk_review_order_reviewer` 唯一索引生效，物理保障同一笔订单同一人仅能生成一条评价；
  - `idx_review_target_time`、`idx_review_goods_status`、`idx_review_order` 高频查询索引生效。

---

## 三、Review 领域实现说明

1. **严格依赖履约终态事实**：  
   `ReviewServiceImpl.createReview` 强制要求关联订单存在且其状态严格为 `OrderStatus.COMPLETED`。对于处于 `WAIT_SELLER_CONFIRM`、`WAIT_MEET` 或 `CANCELLED` 的订单，拒绝评价并返回 `422 Unprocessable Entity`。
2. **参与方权限与身份推导（防 IDOR 投毒）**：  
   当前登录者必须等于 `order.buyerId` 或 `order.sellerId`。被评价人（`reviewedUserId`）完全由后端在本地推导（若当前为买家则评卖家，若为卖家则评买家），客户端无需也禁止传入 `reviewedUserId`。
3. **防自评与防重评价**：  
   代码防御性校验 `reviewerId != reviewedUserId`；前置查询 `(order_id, reviewer_id)`，若已评价返回 `409 Conflict`，并由底层唯一索引兜底。
4. **XSS 清洗与长度截断**：  
   评价文本 `content` 最大限制 500 字符，并通过正则与字符串过滤清除潜在 `<script>`、HTML 危险标签。

---

## 四、RESTful API 规范说明

所有接口挂载于 `/api/reviews`，统一采用 JWT 鉴权保护，响应格式统一封装为 `Result<T>`：

| 请求方法 | 接口路径 | 功能说明 | 核心返回数据 |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/reviews` | 创建交易评价 | `Result<ReviewVO>` |
| `GET` | `/api/reviews/user/{userId}` | 分页查询用户收到的公开评价 | `Result<IPage<ReviewVO>>` |
| `GET` | `/api/reviews/goods/{goodsId}` | 分页查询商品收到的公开评价 | `Result<IPage<ReviewVO>>` |
| `GET` | `/api/reviews/order/{orderId}` | 查询指定订单的双向评价状态与详情 | `Result<OrderReviewStatusVO>` |

- **匿名评价脱敏展示机制**：  
  当 `is_anonymous == true` 且查询者非评价人本人时，接口自动将 `reviewerNickname` 脱敏替换为 `"校友***"`，`reviewerAvatar` 置为 `null`，`reviewerId` 置为 `null`，确保校园熟人隐私安全。

---

## 五、Credit 领域联动实现说明

遵循 Stage 5-C 的架构决策，Review 域不直接修改信用数据，而是通过**领域事件驱动**：

```
[ReviewServiceImpl: 评价落库成功]
            │
            ▼
[发布事件: ReviewCreatedEvent (reviewId, targetUserId, score)]
            │
            ▼
[监听处理: CreditReviewEventListener]
            │
    ┌───────┴───────────────────────────────┐
    ▼                                       ▼
  5 星: addCredit(+3, REVIEW_GOOD)        1 星: deductCredit(-5, REVIEW_BAD)
  4 星: addCredit(+1, REVIEW_GOOD)        2 星: deductCredit(-2, REVIEW_BAD)
  3 星: 不调整信用分
            │
            ▼
[CreditService (Stage 5-B 已就绪)]
  1. 查询 user_credit_log 防重拦截 (相同 reviewId 绝不二次记分)
  2. 原子修改 user_credit，钳位 [0, 200] 区间
  3. 自动重新计算 credit_level
  4. 插入 user_credit_log 审计流水 (related_type='REVIEW', related_id=reviewId)
```

---

## 六、安全验证与边界测试

1. **越权评价拦截**：第三方无关用户访问 `POST /api/reviews`，直接拦截并抛出 `403 AccessDeniedException`；
2. **状态不符拦截**：未完成订单评价返回 `422`，提示“订单尚未完成，暂无法评价”；
3. **自评拦截**：买卖双方为同一人时（测试模拟）返回 `400`，提示“禁止自买自评”；
4. **评分星级边界**：评分输入 0、6 等非法数值，返回 `400`；
5. **强幂等防重记分**：测试用例 `test10` 验证了重复发送相同的 `ReviewCreatedEvent` 时，信用分保持不变，绝无重复累加。

---

## 七、自动化测试结果

### 1. Stage 5-D 专项测试（`CampusTradeStage5DTests`）
```
[INFO] Running com.campustrade.CampusTradeStage5DTests
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.305 s -- in com.campustrade.CampusTradeStage5DTests
[INFO] BUILD SUCCESS
```
- 测试 1：Flyway V6 数据库迁移成功执行且为当前版本 (Version=6) —— ✅ PASS
- 测试 2：COMPLETED 订单可以正常创建评价 —— ✅ PASS
- 测试 3：未完成订单不能评价 (422) —— ✅ PASS
- 测试 4：第三方非参与者用户不能评价 (403) —— ✅ PASS
- 测试 5：不能评价自己 (400) —— ✅ PASS
- 测试 6：同一订单重复评价被拦截 (409) —— ✅ PASS
- 测试 7：评分范围校验 (400) —— ✅ PASS
- 测试 8：5星好评触发信用联动，被评价人信用 +3 —— ✅ PASS
- 测试 9：1星差评触发信用联动，被评价人信用 -5，等级下调 —— ✅ PASS
- 测试 10：重复领域事件不会重复增加或扣减信用 (强幂等) —— ✅ PASS
- 测试 11：评价列表与订单双向评价状态查询正常 —— ✅ PASS
- 测试 12：匿名评价展示脱敏验证 (非本人脱去昵称与头像) —— ✅ PASS

### 2. 全量回归测试套件（`mvn test`）
```
[INFO] Results:
[INFO] Tests run: 146, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  18.931 s
```
历史所有 Stage 0~4、Stage 5-B、Stage 5-D 共 **146 项测试用例全部 100% 通过**，无任何功能破坏与回归问题。

### 3. 前端静态代码检查（`flutter analyze`）
```
Analyzing frontend...                                           
No issues found! (ran in 2.6s)
```

---

## 八、Stage 5-E 准入条件判定

| 准入验收检查项 | 目标要求 | 达成状态 |
| :--- | :--- | :---: |
| **1. 数据库版本与结构** | Flyway V6 执行成功，`review` 表与索引落盘 | ✅ 达成 |
| **2. 领域模型与实体映射** | `Review`, `ReviewStatus`, `ReviewMapper` 完备 | ✅ 达成 |
| **3. 业务服务层闭环** | `createReview`, `getReviewsByUser/Goods/Order` 全就绪 | ✅ 达成 |
| **4. 信用事件驱动集成** | 5阶星级映射、事件解耦、幂等防重流水验证通过 | ✅ 达成 |
| **5. RESTful API 完备** | Controller 4 大端点完备，统一 `Result<T>` 与脱敏 VO | ✅ 达成 |
| **6. 安全与边界防御** | IDOR 越权拦截、422状态拦截、409防重、XSS 清洗全部生效 | ✅ 达成 |
| **7. 全量自动化测试** | 146/146 项后端测试通过，前端 0 警告 | ✅ 达成 |

### 最终结论：
**各项后端功能、信用联动机制、API 接口与自动化测试已全部圆满交付！正式判定：READY FOR Stage 5-E（前端评价与信用中心交互闭环开发）！**
