# Stage 5-C: 现有订单体系与信用体系审计与连接点分析

> **文档标识**：`docs/stage5/Stage5-C-review-existing-analysis.md`  
> **阶段**：Stage 5-C（评价系统设计与信用联动）  
> **性质**：现状审计与架构决策  

---

## 一、Order Domain 现状审计与评价接入点分析

### 1.1 订单生命周期与终态现状
在 Stage 4 中，订单域的核心状态机实现如下：
```
                  ┌───────────────────────┐
                  │  WAIT_SELLER_CONFIRM  │
                  └──────────┬────────────┘
                             │ (卖家接单 confirmOrder)
                             ▼
                  ┌───────────────────────┐
                  │       WAIT_MEET       │
                  └──────────┬────────────┘
                             │ (双方确认 completeOrder)
                             ▼
                  ┌───────────────────────┐
                  │       COMPLETED       │ <--- 评价系统唯一准入终态
                  └───────────────────────┘
```
- **核心结论 1**：只有状态严格为 `OrderStatus.COMPLETED` 的订单，才具备评价资格。处于 `WAIT_SELLER_CONFIRM`、`WAIT_MEET` 或 `CANCELLED` 的订单一律不产生交易评价权。
- **核心结论 2**：订单完成属于终态，`completedTime` 记录了确切的交易完成物理时间戳，可作为评价有效期（例如完成后的 7 天内）计算的基准时刻。

---

### 1.2 订单参与方与双向评价能力
- 在校园二手交易场景中，买卖双方具有平等的履约信用诉求：
  - **买家评价卖家**：关注商品品质、货不对板情况、面交守时度、包装与沟通友好度；
  - **卖家评价买家**：关注买家是否按时赴约、是否临时大刀砍价、是否爽快验货确认。
- **现有数据模型适配度**：
  `TradeOrder` 表中原生具备 `buyer_id` 和 `seller_id`，且在订单创建时强制 `buyer_id != seller_id`。因此，一笔订单天然支持且仅支持产生 **2 条互评记录**：
  1. `buyer_id` 评价 `seller_id`
  2. `seller_id` 评价 `buyer_id`

---

### 1.3 是否需要扩展订单表字段？（方案比选）

| 方案 | 设计方式 | 优点 | 缺点 | 评估结论 |
| :--- | :--- | :--- | :--- | :--- |
| **方案 A：订单表侵入修改** | 在 `trade_order` 表增加 `buyer_reviewed` (BOOLEAN)、`seller_reviewed` (BOOLEAN) 字段 | 查询订单详情时直接带出状态，无需连表 | ① 违背高内聚低耦合原则；<br>② 修改了已通过全量测试的 Stage 4 核心表结构；<br>③ 并发更新订单容易产生行锁竞争。 | ❌ 不推荐 |
| **方案 B：独立评价表动态投影（解耦方案）** | `trade_order` 保持完全不变；由 `review` 表通过 `(order_id, reviewer_id)` 唯一索引管理。在展示层或 VO 装配时做轻量关联判断 | ① **对 Stage 0~4 业务零侵入**；<br>② 数据归属领域纯粹，Review 域自闭环；<br>③ 数据库结构完全符合 DDD 规范。 | 查询订单是否已评需轻量查询 review 表或在订单 VO 组装时批量查询 | ✅ **强烈推荐（采纳）** |

---

## 二、Credit Domain 现状审计与联动链路分析

### 2.1 信用系统已有能力回顾（Stage 5-B）
1. **持久层结构**：
   - `campus_trade.user_credit`：包含 `credit_score` (0~200), `credit_level` (EXCELLENT/GOOD/FAIR/POOR), `completed_count`, `cancel_count`, `good_review_count`, `bad_review_count`。
   - `campus_trade.user_credit_log`：包含 `(user_id, related_type, related_id, change_type)` 唯一幂等索引。
2. **服务层能力**：
   - `CreditService.addCredit(...)` / `CreditService.deductCredit(...)`：封装了事务、0~200钳位运算、等级重算与流水记录。
   - `CreditChangeType` 枚举中已预置：`REVIEW_GOOD`（好评）与 `REVIEW_BAD`（差评）。

---

### 2.2 评价触发信用变更的架构决策

#### 架构方案对比：

```mermaid
graph TD
    subgraph 方案一: 强耦合直接调用
        R1[ReviewService] -->|直接注入并调用| C1[CreditService]
    end

    subgraph 方案二: Spring 领域事件驱动 (推荐架构)
        R2[ReviewService] -->|发布领域事件| E[ReviewCreatedEvent]
        E -->|Spring EventBus 派发| H[CreditReviewEventListener]
        H -->|异步/事务后处理| C2[CreditService]
    end
```

| 维度 | 方案一：Service 直接注入调用 | 方案二：Spring 领域事件解耦 (推荐) |
| :--- | :--- | :--- |
| **耦合度** | Review 域强依赖 Credit 域，包依赖双向或单向硬绑定 | **零直接依赖**，Review 域仅声明并发布标准业务事件 `ReviewCreatedEvent` |
| **扩展性** | 后续若需增加“评价后通知对方”、“评价后触发勋章”，需不断在 `ReviewService` 堆砌代码 | **极佳**，新增 Handler 即可（如 `NotificationHandler`, `BadgeHandler`）无需改动核心评价代码 |
| **事务一致性** | 同一本地事务，若信用服务异常会导致评价也回滚 | 支持 `@TransactionalEventListener`，可灵活选择随评价同一事务提交或在事务提交后独立入账 |
| **可测试性** | 测试 Review 时需 Mock CreditService | 测试 Review 时只需断言 Event 是否发布，单元测试边界干净清晰 |

### 2.3 最终架构决策
- **采纳方案二：领域事件驱动架构（Spring Local Domain Event）**。
- `ReviewServiceImpl` 负责创建并持久化评价记录，成功后通过 Spring `ApplicationEventPublisher` 发布 `ReviewCreatedEvent`。
- 由独立的 `CreditReviewEventListener` 监听该事件，解析星级评分，调用 `CreditService` 进行记分与流水记录。
- 该设计既保障了校园单体架构的简练，又完全遵循领域驱动设计的松耦合规范。
