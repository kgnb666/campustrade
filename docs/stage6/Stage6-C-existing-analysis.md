# Stage 6-C：评价体系与治理现状深度审计报告

**编制日期**：2026-09-18  
**所属阶段**：Stage 6-C（设计评审阶段 - 严禁修改业务代码）  
**审计目标**：全面排查 Stage 5 评价体系与 Stage 6-B 举报治理链路的已有实现，明确评价点赞、点赞计数、数据一致性及治理屏蔽边界的现状。

---

## 一、评价点赞与计数现状排查结论

经过对全站代码库（后端 Java、前端 Flutter、数据库 Flyway V1~V7 迁移脚本、Docker 初始化脚本）的全局检索，确认：

| 检查项 | 目标位置 / 符号 | 现状排查结果 | 说明 |
| :--- | :--- | :---: | :--- |
| **点赞计数列** | `campus_trade.review.like_count` | ❌ **不存在** | 现有 `review` 表仅有 12 个字段，未定义任何点赞列 |
| **点赞明细表** | `campus_trade.review_like` | ❌ **不存在** | 数据库未建此表，无点赞物理存储结构 |
| **点赞实体与 Mapper** | `ReviewLike.java`, `ReviewLikeMapper.java` | ❌ **不存在** | 后端代码无相关持久化映射类 |
| **VO 点赞字段** | `ReviewVO.likeCount`, `ReviewVO.likedByCurrentUser` | ❌ **不存在** | `ReviewVO` 目前仅包含评分、内容、标签、匿名及作者信息 |
| **Redis 点赞缓存** | `review:like:*` | ❌ **不存在** | Redis 目前仅用于 Token 黑名单、实名验证码、商品浏览量、搜索热词及举报限流 |
| **前端点赞交互** | Flutter 评价列表点赞按钮 / 动效 | ❌ **不存在** | Stage 5-E 明确延期，前端未开发任何点赞组件 |

> **审计定论**：当前系统**完全不存在评价点赞功能**。该功能属于纯净的 Greenfield 增量领域，不存在任何历史遗留耦合或半成品干扰。

---

## 二、Stage 5 既有评价体系核心逻辑盘点

### 1. 交易评价前置强约束
- **订单履约终态依赖**：仅当关联订单状态严格为 `OrderStatus.COMPLETED` 时才允许评价；处于 `WAIT_SELLER_CONFIRM`、`WAIT_MEET` 或 `CANCELLED` 的订单严禁评价（抛出 `422 Unprocessable Entity`）。
- **参与方身份推导与防 IDOR**：只有买家（`order.buyerId`）或卖家（`order.sellerId`）有权评价，被评人（`reviewedUserId`）由系统本地严格推导，客户端无法篡改。
- **评价物理唯一性**：数据库具备唯一索引 `uk_review_order_reviewer (order_id, reviewer_id)`，一笔订单每人仅可评价一次。
- **评价时间窗口**：订单完成时间超过 168 小时（7 天）后评价通道自动关闭。
- **匿名脱敏保护**：若买家/卖家勾选 `isAnonymous = true`，非本人视角下昵称脱敏为 `校友***`，头像置空。

### 2. 信用与事件闭环
- 评价提交成功后，通过 Spring 本地事件 `ReviewCreatedEvent` 异步解耦驱动 `CreditReviewEventListener`；
- 调用 `CreditService` 实施阶梯积分：
  - 5 星：+3 分 (`REVIEW_GOOD`)
  - 4 星：+1 分 (`REVIEW_GOOD`)
  - 3 星：0 分
  - 2 星：-2 分 (`REVIEW_BAD`)
  - 1 星：-5 分 (`REVIEW_BAD`)
- 物理唯一索引 `uk_credit_log_idempotent` 保障每笔评价的信用加减分强幂等。

---

## 三、Stage 6-B 举报治理体系与评价处理链路盘点

Stage 6-B 已建立完整的“工单-治理-冲正-审计”不可逆链路：

```text
用户提交举报 (target_type = REVIEW, target_id = reviewId)
  ↓
落库 report 表 (status = 'PENDING')
  ↓
管理员后台查看待处理工单 (GET /api/admin/reports)
  ↓
管理员审核判定属实 (PUT /api/admin/reports/{id}/handle, action = VALID)
  ↓
1. report.status 变更为 HANDLED_VALID
2. review.status 变更为 AUDIT_REJECTED (前台列表不再展示)
3. 信用安全冲正 (Credit Reversal):
   - 若原为 5 星: deductCredit(targetUserId, 3, ADMIN_ADJUST, "REVIEW", reviewId)
   - 若原为 4 星: deductCredit(targetUserId, 1, ADMIN_ADJUST, "REVIEW", reviewId)
   - 若原为 2 星: addCredit(targetUserId, 2, ADMIN_ADJUST, "REVIEW", reviewId)
   - 若原为 1 星: addCredit(targetUserId, 5, ADMIN_ADJUST, "REVIEW", reviewId)
4. 记录 admin_audit_log (不可篡改审计流水)
```

### 核心审计启示：
1. **`AUDIT_REJECTED` 评价必须与公开互动完全绝缘**：被治理屏蔽的评价绝不能继续接受新的点赞；
2. **点赞数据不能随评价屏蔽而硬删除**：历史点赞明细应当完整保留以备审计；
3. **不可破坏已有的信用冲正与状态机规则**。

---

## 四、现有列表查询 N+1 性能缺陷深度审查

审查 `ReviewServiceImpl.java` 中现有的两个公开列表方法：
1. `getReviewsByUser(Long userId, Integer page, Integer size)`
2. `getReviewsByGoods(Long goodsId, Integer page, Integer size)`

### 发现的严重架构缺陷：
```java
// ReviewServiceImpl.java 当前实现
return reviewPage.convert(review -> convertToVO(review, null, null));

private ReviewVO convertToVO(Review review, String goodsTitleSnapshot, Long viewingUserId) {
    ...
    User reviewer = userMapper.selectById(review.getReviewerId()); // ❌ 循环单条查询
    ...
    Goods goods = goodsMapper.selectById(review.getGoodsId());     // ❌ 循环单条查询
    ...
}
```
**问题剖析**：
- 当一页返回 10 条评价时，当前代码会执行：
  - 1 次评价分页查询；
  - 10 次 `userMapper.selectById` 查询评价人信息；
  - 10 次 `goodsMapper.selectById` 查询商品标题；
  - 共执行 **21 次 SQL 查询**！
- 如果 Stage 6-C 在 `convertToVO` 中再增加 `reviewLikeMapper.selectCount(...)` 和 `reviewLikeMapper.selectOne(...)` 来获取点赞数与本人是否点赞：
  - 每次循环又会增加 2 次 SQL；
  - 10 条评价将膨胀至 **41 次 SQL 查询**！典型的典型 N+1 性能灾难。

> **Stage 6-C 架构改进硬指标**：必须彻底重构列表组装逻辑，改用 **批量聚合内存映射（Batch In-Memory Mapping）**，将查询次数压缩至恒定的 **4 次批量 SQL**。
