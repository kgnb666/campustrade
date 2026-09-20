# Stage 4-A: 订单安全、并发控制与状态联动设计 (Order Security Design)

- **所属模块**：交易安全防御与数据一致性体系 (Transaction Security & State Consistency)
- **核心目标**：防范刷单欺诈、杜绝高并发超卖、阻断水平越权，确保订单流转与商品状态严丝合缝。

---

## 一、核心安全边界设计 (Security Guardrails)

### 1. 自买自卖防御 (Self-Trading Prevention)
- **风险**：恶意用户使用同一个学生账号，发布虚构二手商品并立即下单完成，以此恶意刷取 `user_credit.trade_count` 交易量与信用积分。
- **强制规则**：
  ```java
  if (currentUserId.equals(goods.getSellerId())) {
      throw new BusinessException(400, "不能购买自己发布的商品");
  }
  ```
- **执行层次**：在领域模型 `Order.create()` 以及 Service 业务入口层设置双重校验。

### 2. 身份认证与权限守卫 (Authentication & School Match)
- **准入门槛**：买家下单必须满足 `studentVerify.verifyStatus == 'SUCCESS'`，未认证校园身份的用户拦截提示“请先完成高校学生身份认证后再发起交易”；
- **校区安全建议**：优先支持同校（`buyer.schoolId == goods.schoolId`）自提面交，异校交易在界面增加跨校区出行风险提醒。

### 3. 水平越权防御 (IDOR - Insecure Direct Object Reference)
- **查询隔离**：
  - 用户调用 `GET /orders/{id}` 详情时，系统必须比对：
    ```java
    if (!currentUserId.equals(order.getBuyerId()) && !currentUserId.equals(order.getSellerId())) {
        throw new BusinessException(403, "无权查看该订单信息");
    }
    ```
  - 列表接口 `GET /orders/my` 强制在 SQL 查询条件中注入当前用户 ID（`WHERE buyer_id = ? OR seller_id = ?`），无法通过篡改入参遍历他人订单。
- **操作权能严格校验**：
  - 确认接单：必须 `currentUserId.equals(order.getSellerId())`；
  - 买家取消：必须 `currentUserId.equals(order.getBuyerId())`；
  - 卖家取消：必须 `currentUserId.equals(order.getSellerId())`；
  - 确认完成：必须为订单关联双方。

---

## 二、并发控制与商品独占性设计 (Single-Inventory Concurrency Control)

二手闲置交易与标品电商最大的差异在于：**库存量严格恒等于 1**。
同一商品绝不能同时被两个买家下单。

### 1. 三道并发防护防线

```
[买家 A/B 高并发下单]
       │
       ▼
【第 1 道防线：数据库行级条件更新 (Optimistic Guard)】
UPDATE campus_trade.goods 
   SET status = 'LOCKED', updated_time = NOW() 
 WHERE id = #{goodsId} AND status = 'ON_SALE';
 (仅 1 个线程受影响行数为 1，其余线程影响行数为 0 立即快速失败：400 "该商品已被其他同学预订")
       │ (成功者进入事务)
       ▼
【第 2 道防线：业务事务锁与记录生成】
INSERT INTO campus_trade.trade_order (order_no, buyer_id, seller_id, goods_id, ...)
       │
       ▼
【第 3 道防线：PostgreSQL 部分唯一索引 (Partial Unique Index)】
CREATE UNIQUE INDEX uk_trade_order_active_goods 
    ON campus_trade.trade_order(goods_id) 
 WHERE order_status IN ('WAIT_SELLER_CONFIRM', 'WAIT_MEET');
 (底层物理兜底：即使应用层出现极其罕见的竞态穿透，数据库层直接报唯一冲突并自动回滚事务)
```

- **架构收益**：
  - 无需引入复杂的分布式锁组件（如 Redisson），基于 PostgreSQL 原生事务与局部索引即可实现极致可靠、零超卖的独占并发控制。

---

## 三、商品状态与订单状态联动设计 (Goods-Order State Synchronization)

订单生命周期发生改变时，被关联商品的公开可见性与流转状态必须实时联动：

```
+-------------------+      创建订单      +-----------------------+
|  goods: ON_SALE   | ─────────────────> |  order: WAIT_SELLER   |
|     (在售中)      |                    |     (待卖家确认)      |
+-------------------+                    +-----------┬-----------+
          ▲                                          │
          │                   取消订单               ▼
          ├───────────────────────────── ┌───────────────────────┐
          │ (解冻回售)                   │   order: WAIT_MEET    │
          │                              │     (等待校园面交)    │
          │                              └───────────┬───────────┘
          │                                          │ 确认完成
          ▼                                          ▼
+-------------------+                    +───────────────────────+
|  goods: LOCKED    |                    |   order: COMPLETED    |
|    (锁定预订中)   |                    |     (已完成交易)      |
+-------------------+                    +───────────────────────+
          │                                          │
          ▼ 交易完成                                 ▼
+-------------------+                    +───────────────────────+
|   goods: SOLD     | <───────────────── |  双方信用 trade_count |
|     (已售出)      |                    |     各自动 +1         |
+-------------------+                    +───────────────────────+
```

### 为什么必须这样联动设计？
1. **`ON_SALE` -> `LOCKED`**：
   - 当买家生成订单后，商品在公开商品列表（`GoodsListPage`）中被自动过滤（`WHERE status = 'ON_SALE'`），避免其他同学继续点击和发起无效询问；商品详情页展示“已被预订中”；
2. **`LOCKED` -> `ON_SALE`**：
   - 若卖家拒绝或双方因故协商取消，商品状态自动解冻回滚为 `ON_SALE`，重新出现在校园闲置广场，无需卖家重新费时发布；
3. **`LOCKED` -> `SOLD`**：
   - 线下当面验货交付完成后，商品彻底进入不可逆的历史归档状态 `SOLD`，永不再公开销售。

---

## 四、信用体系与扩展点预留 (Credit System Extension Points)

### 1. 现阶段信用数据沉淀
- 订单进入 `COMPLETED` 状态的数据库事务中：
  - 更新买家 `campus_trade.user_credit`：`trade_count = trade_count + 1`；
  - 更新卖家 `campus_trade.user_credit`：`trade_count = trade_count + 1`；
  - 达成一次真实校园线下履约信用积累。

### 2. 未来 Stage 扩展点预留 (不提前实现)
- **评价系统 (Review)**：
  - 预留领域事件 `TradeCompletedEvent`；
  - 未来 Stage 触发向买卖双方下发“评价提醒”，评价结果对应累加 `good_review_count` 或 `bad_review_count`，并根据动态算法微调 `credit_score`；
- **违规取消惩罚**：
  - 若用户恶意频繁下单又随意取消，未来可通过风控规则限制其每日最大下单次数。
