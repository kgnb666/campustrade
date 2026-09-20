# Stage 4-A: 订单数据库设计 (Order Database Design)

- **数据库**：PostgreSQL 16
- **架构模式**：Schema: `campus_trade`
- **Flyway 规划**：未来版本号预定为 `V4__init_order_schema.sql`（**本阶段仅作架构设计，不创建实际迁移文件**）。

---

## 一、表命名决策 (Table Naming Decision)

在 SQL 标准与 PostgreSQL 数据库中，`order` 属于**顶级保留关键字**（用于 `ORDER BY`）。
若直接命名为 `campus_trade."order"`：
- 所有的 MyBatis-Plus XML、手写 SQL、Flyway 脚本及数据库客户端查询中，必须强制使用双引号包裹 `"order"`，极易发生语法转义遗漏或混淆（如 `SELECT * FROM campus_trade.order` 将直接报语法错误）。
- 根据《阿里巴巴 Java 开发手册》以及 PostgreSQL 企业工程规范：
  - **决策**：表名统一命名为 **`campus_trade.trade_order`**；
  - 既能消除与 SQL 关键字的冲突风险，又能清晰表达“交易订单”的业务语义。

---

## 二、数据表结构说明表 (`trade_order`)

| 字段名称 (Column) | 物理类型 (Type) | 约束 (Constraints) | 默认值 (Default) | 业务定义与说明 |
| :--- | :--- | :--- | :--- | :--- |
| `id` | `BIGSERIAL` | `PRIMARY KEY` | 自增序列 | 订单物理主键 |
| `order_no` | `VARCHAR(32)` | `NOT NULL, UNIQUE` | 无 | 业务订单编号 (格式: `ORDyyyyMMddHHmmssXXXX`) |
| `buyer_id` | `BIGINT` | `NOT NULL` | 无 | 买家用户 ID (关联 `user.id`) |
| `seller_id` | `BIGINT` | `NOT NULL` | 无 | 卖家用户 ID (关联 `user.id`) |
| `goods_id` | `BIGINT` | `NOT NULL` | 无 | 交易商品 ID (关联 `goods.id`) |
| `school_id` | `BIGINT` | `NOT NULL` | 无 | 所属高校 ID (关联 `campus_school.id`) |
| `goods_title_snapshot` | `VARCHAR(100)` | `NOT NULL` | 无 | 下单瞬间商品标题快照 |
| `goods_price_snapshot` | `DECIMAL(10,2)` | `NOT NULL` | 无 | 下单瞬间商品成交价格快照 |
| `goods_image_snapshot` | `VARCHAR(255)` | 可为空 | 无 | 下单瞬间商品首图快照 URL |
| `meet_location` | `VARCHAR(100)` | `NOT NULL` | 无 | 约定的线下校园面交地点 (如: 学二食堂门口) |
| `buyer_message` | `VARCHAR(255)` | 可为空 | 无 | 买家下单时附加留言/期望时段 |
| `seller_reply` | `VARCHAR(255)` | 可为空 | 无 | 卖家接单时的确认回复留言 |
| `order_status` | `VARCHAR(30)` | `NOT NULL` | `'WAIT_SELLER_CONFIRM'` | 状态: `WAIT_SELLER_CONFIRM`, `WAIT_MEET`, `COMPLETED`, `CANCELLED` |
| `cancel_reason` | `VARCHAR(255)` | 可为空 | 无 | 订单取消或拒绝的具体原因 |
| `cancelled_by` | `VARCHAR(20)` | 可为空 | 无 | 取消方角色标识 (`BUYER` / `SELLER` / `SYSTEM`) |
| `created_time` | `TIMESTAMP` | `NOT NULL` | `CURRENT_TIMESTAMP` | 买家提交订单时间 |
| `confirmed_time` | `TIMESTAMP` | 可为空 | 无 | 卖家确认接单时间 |
| `completed_time` | `TIMESTAMP` | 可为空 | 无 | 线下验货交付完成时间 |
| `cancelled_time` | `TIMESTAMP` | 可为空 | 无 | 订单关闭或终止时间 |
| `updated_time` | `TIMESTAMP` | `NOT NULL` | `CURRENT_TIMESTAMP` | 记录最后更新时间 |

---

## 三、索引规划与性能优化 (Indexes Planning)

针对校园二手订单的高频业务查询场景，规划如下索引方案：

```sql
-- 1. 业务订单号唯一索引 (单据幂等检索)
CREATE UNIQUE INDEX uk_trade_order_no 
    ON campus_trade.trade_order(order_no);

-- 2. 买家订单历史复合索引 (按买家 ID 分页倒序浏览“我买到的”)
CREATE INDEX idx_trade_order_buyer_time 
    ON campus_trade.trade_order(buyer_id, created_time DESC);

-- 3. 卖家订单历史复合索引 (按卖家 ID 分页倒序浏览“我卖出的”)
CREATE INDEX idx_trade_order_seller_time 
    ON campus_trade.trade_order(seller_id, created_time DESC);

-- 4. 商品关联索引 (反向溯源与防重复下单查询)
CREATE INDEX idx_trade_order_goods 
    ON campus_trade.trade_order(goods_id);

-- 5. 订单流转状态索引 (运营排查与状态过滤)
CREATE INDEX idx_trade_order_status 
    ON campus_trade.trade_order(order_status);

-- 6. 活动中订单唯一部分索引 (PostgreSQL Partial Index: 物理级防止同一商品一货多卖)
CREATE UNIQUE INDEX uk_trade_order_active_goods 
    ON campus_trade.trade_order(goods_id) 
    WHERE order_status IN ('WAIT_SELLER_CONFIRM', 'WAIT_MEET');
```

> [!TIP]
> **PostgreSQL 部分索引 (Partial Index) 的架构妙用**：
> `uk_trade_order_active_goods` 仅在 `order_status` 为活动状态（`WAIT_SELLER_CONFIRM` 或 `WAIT_MEET`）时建立唯一约束。
> 一旦订单变成 `COMPLETED` 或 `CANCELLED`，该商品即可允许再次出售或生成历史订单记录，但在活动期内，数据库底层从物理层面彻底焊死同一商品并发下单竞争的可能！

---

## 四、Flyway V4 脚本设计草案 (预备 Stage 4-B 使用)

```sql
-- ==============================================================================
-- Flyway Migration V4: Stage 4 交易订单与校园面交闭环核心表结构
-- (设计草案，Stage 4-B 执行时落地)
-- ==============================================================================

CREATE TABLE IF NOT EXISTS campus_trade.trade_order (
    id BIGSERIAL PRIMARY KEY,
    order_no VARCHAR(32) NOT NULL,
    buyer_id BIGINT NOT NULL,
    seller_id BIGINT NOT NULL,
    goods_id BIGINT NOT NULL,
    school_id BIGINT NOT NULL,
    goods_title_snapshot VARCHAR(100) NOT NULL,
    goods_price_snapshot DECIMAL(10,2) NOT NULL,
    goods_image_snapshot VARCHAR(255),
    meet_location VARCHAR(100) NOT NULL,
    buyer_message VARCHAR(255),
    seller_reply VARCHAR(255),
    order_status VARCHAR(30) NOT NULL DEFAULT 'WAIT_SELLER_CONFIRM',
    cancel_reason VARCHAR(255),
    cancelled_by VARCHAR(20),
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed_time TIMESTAMP,
    completed_time TIMESTAMP,
    cancelled_time TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_trade_order_no UNIQUE (order_no)
);

CREATE INDEX IF NOT EXISTS idx_trade_order_buyer_time ON campus_trade.trade_order(buyer_id, created_time DESC);
CREATE INDEX IF NOT EXISTS idx_trade_order_seller_time ON campus_trade.trade_order(seller_id, created_time DESC);
CREATE INDEX IF NOT EXISTS idx_trade_order_goods ON campus_trade.trade_order(goods_id);
CREATE INDEX IF NOT EXISTS idx_trade_order_status ON campus_trade.trade_order(order_status);

CREATE UNIQUE INDEX IF NOT EXISTS uk_trade_order_active_goods 
    ON campus_trade.trade_order(goods_id) 
    WHERE order_status IN ('WAIT_SELLER_CONFIRM', 'WAIT_MEET');
```
