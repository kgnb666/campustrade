# Stage 6-C：评价点赞数据库迁移与存储模型设计规范

**编制日期**：2026-09-18  
**所属阶段**：Stage 6-C（设计评审阶段 - 严禁修改业务代码与执行 SQL）  
**文档目标**：详细设计 `review_like` 数据表结构、`campus_trade.review` 字段扩充方案、物理唯一性约束、索引拓扑及未来 Flyway V8 迁移规划。

---

## 一、数据库真理源与存储模式决策

在 Stage 3.5 与 Stage 6-A 既有原则基础上，再次强调：

$$\mathbf{PostgreSQL = Source\ of\ Truth\ (唯一真理源)}$$

针对点赞数如何存储，对比方案：

### 方案 A：每次查询实时 `COUNT(review_like)`
- **优点**：无需在主表维护冗余字段，不存在主子表计数不一致风险。
- **缺点**：在列表查询（每页 10~20 条）或商品详情页多评价并发加载时，需要对 `review_like` 执行多个 `COUNT(*)` 子查询或关联聚合，在大数据量或热点商品下产生巨大 CPU 开销。

### 方案 B：主表冗余 `review.like_count` + 原子更新 (采纳方案)
- **优点**：查询复杂度为 $O(1)$，无需任何连接即可在读取 `review` 记录时直接拿到点赞数，与商品浏览量 `view_count` 风格高度一致。
- **一致性保证**：点赞与取消点赞操作通过 `@Transactional` 本地事务包裹：
  - 点赞时：明细表插入成功后，执行 `UPDATE review SET like_count = like_count + 1 WHERE id = ?`；
  - 取消时：明细表删除成功后，执行 `UPDATE review SET like_count = GREATEST(0, like_count - 1) WHERE id = ?`；
  - 配合 `GREATEST(0, ...)` 规避负数下溢。

---

## 二、数据表结构技术规范

### 1. `campus_trade.review` 表结构扩充
为现有评价表扩充冗余计数列：
```sql
ALTER TABLE campus_trade.review 
    ADD COLUMN IF NOT EXISTS like_count INT NOT NULL DEFAULT 0;
```
- **字段类型**：`INT` (4 字节整数，支持到 21 亿，校园场景完全冗余)；
- **默认值**：`0`；
- **非空约束**：`NOT NULL`。

---

### 2. `campus_trade.review_like` 点赞明细表结构
```sql
CREATE TABLE IF NOT EXISTS campus_trade.review_like (
    id BIGSERIAL PRIMARY KEY,
    review_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

#### 字段明细说明：
| 字段名 | 类型 | 约束 | 业务含义 | 说明 |
| :--- | :--- | :--- | :--- | :--- |
| `id` | `BIGSERIAL` | `PRIMARY KEY` | 点赞明细自增主键 | 保证每条点赞操作有唯一标识 |
| `review_id` | `BIGINT` | `NOT NULL` | 被点赞评价 ID | 逻辑外键，关联 `campus_trade.review.id` |
| `user_id` | `BIGINT` | `NOT NULL` | 点赞人用户 ID | 逻辑外键，关联 `campus_trade.user.id` |
| `created_time` | `TIMESTAMP` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | 点赞时间戳 | 用于时间排序与频控防刷追溯 |

---

## 三、物理索引与约束设计

```sql
-- 1. 核心物理唯一索引 (一人一评仅能点赞一次)
CREATE UNIQUE INDEX IF NOT EXISTS uk_review_like_review_user 
    ON campus_trade.review_like (review_id, user_id);

-- 2. 点赞人历史查询索引 (支持查询“我的点赞”或用户行为审计)
CREATE INDEX IF NOT EXISTS idx_review_like_user_time 
    ON campus_trade.review_like (user_id, created_time DESC);

-- 3. 评价主表按热度/点赞数倒序索引 (为未来热评排序预留)
CREATE INDEX IF NOT EXISTS idx_review_goods_likes 
    ON campus_trade.review (goods_id, like_count DESC, created_time DESC);
```

### 索引原理深度分析：
1. **`uk_review_like_review_user`**：
   - 复合键 `(review_id, user_id)` 不仅是底层唯一的物理铁律，而且根据 PostgreSQL B-Tree 最左匹配原则，以 `review_id` 为前缀的查询（如 `SELECT user_id FROM review_like WHERE review_id = ?`）可以直接走该索引，**无需额外为 `review_id` 单独建立普通索引**，节约写入与磁盘开销；
2. **`idx_review_like_user_time`**：
   - 覆盖 `user_id` 维度的检索，保障未来扩展“我的点赞历史”或风控扫描某异常用户高频点赞行为时性能最优。

---

## 四、外键与级联策略决策 (Foreign Key Policy)

- **为什么不使用物理外键 `REFERENCES review(id) ON DELETE CASCADE`？**
  1. 遵从 CampusTrade 全局微服务架构规范，全站所有表（`goods`, `trade_order`, `favorite`, `browse_history`, `report`）均采用**应用层逻辑外键**，数据库不挂强物理外键；
  2. 评价在平台上**坚决不执行物理 DELETE**（仅执行逻辑屏蔽 `AUDIT_REJECTED`），因此不存在“主表评价被删导致外键悬空”的情况；
  3. 逻辑解耦提升数据库批量插入与并发性能，规避外键行级锁冲突。

---

## 五、未来 Flyway V8 迁移规划草案 (待 Stage 6-C 评审通过后执行)

后续正式进入实现阶段时，将创建 `V8__create_review_like_domain.sql`：
```sql
-- ==============================================================================
-- Flyway Migration V8: Stage 6-C 评价点赞领域表结构与索引
-- ==============================================================================

-- 1. 评价主表扩充 like_count
ALTER TABLE campus_trade.review 
    ADD COLUMN IF NOT EXISTS like_count INT NOT NULL DEFAULT 0;

-- 2. 创建评价点赞明细表
CREATE TABLE IF NOT EXISTS campus_trade.review_like (
    id BIGSERIAL PRIMARY KEY,
    review_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 3. 建立物理约束与索引
CREATE UNIQUE INDEX IF NOT EXISTS uk_review_like_review_user 
    ON campus_trade.review_like (review_id, user_id);

CREATE INDEX IF NOT EXISTS idx_review_like_user_time 
    ON campus_trade.review_like (user_id, created_time DESC);
```
该迁移完全向下兼容既有 V1~V7 结构，对既有表数据零侵入零破坏。
