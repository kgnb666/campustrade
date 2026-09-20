# Stage 4-B-1: 订单系统数据库迁移基础建设总结报告

- **执行阶段**：Stage 4-B-1 (Flyway V4 Migration)
- **迁移版本**：Flyway V4 (`V4__init_order_schema.sql`)
- **数据表**：`campus_trade.trade_order`

---

## 一、修改与新增文件列表

1. **[NEW]** [`backend/src/main/resources/db/migration/V4__init_order_schema.sql`](file:///d:/wkk/Second-hand%20trading%20platform/backend/src/main/resources/db/migration/V4__init_order_schema.sql)
   - Flyway V4 正式迁移脚本，包含 `campus_trade.trade_order` 表、5 个基础/复合索引以及 1 个基于活动状态的 PostgreSQL Partial Unique Index。
2. **[MODIFIED]** [`docker/postgres/init.sql`](file:///d:/wkk/Second-hand%20trading%20platform/docker/postgres/init.sql)
   - 同步追加 Stage 4 `trade_order` DDL，保证容器化冷启动建表与 Flyway 增量迁移脚本 100% 保持一致。
3. **[NEW]** [`backend/src/test/java/com/campustrade/CampusTradeStage4B1Tests.java`](file:///d:/wkk/Second-hand%20trading%20platform/backend/src/test/java/com/campustrade/CampusTradeStage4B1Tests.java)
   - Stage 4-B-1 专项验证测试套件，包含 4 项深度断言：Flyway 版本链完整性、物理表结构与 20 个字段约束、索引完整性、局部唯一索引并发防超卖验证。

---

## 二、Flyway 执行结果

在应用启动与单元测试运行时，Flyway 成功加载并应用 `V4__init_order_schema.sql`：
- **Schema**：`campus_trade`
- **Current Version**：`4`
- **Description**：`init order schema`
- **State**：`SUCCESS`
- **迁移链路状态**：
  - `V1`: `init user and auth schema` (SUCCESS)
  - `V2`: `init goods and category schema` (SUCCESS)
  - `V3`: `init interaction schema` (SUCCESS)
  - `V4`: `init order schema` (SUCCESS)

---

## 三、数据库验证结果

### 1. 数据表与字段结构 (`campus_trade.trade_order`)
通过 `information_schema.columns` 验证 20 个字段全部创建就绪：
- `id` (BIGSERIAL PRIMARY KEY)
- `order_no` (VARCHAR(32), UNIQUE)
- `buyer_id` (BIGINT NOT NULL)
- `seller_id` (BIGINT NOT NULL)
- `goods_id` (BIGINT NOT NULL)
- `school_id` (BIGINT)
- `goods_title_snapshot` (VARCHAR(200))
- `goods_price_snapshot` (NUMERIC(10,2))
- `goods_image_snapshot` (VARCHAR(500))
- `meet_location` (VARCHAR(200))
- `buyer_message` (VARCHAR(500))
- `seller_reply` (VARCHAR(500))
- `order_status` (VARCHAR(50) DEFAULT 'WAIT_SELLER_CONFIRM')
- `cancel_reason` (VARCHAR(500))
- `cancelled_by` (BIGINT)
- `confirmed_time` (TIMESTAMP)
- `completed_time` (TIMESTAMP)
- `cancelled_time` (TIMESTAMP)
- `created_time` (TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
- `updated_time` (TIMESTAMP DEFAULT CURRENT_TIMESTAMP)

### 2. 索引与并发约束检查 (`pg_indexes`)
- `uk_trade_order_no`: 业务订单号唯一索引
- `idx_trade_order_buyer_time`: `(buyer_id, created_time DESC)` 买家时序索引
- `idx_trade_order_seller_time`: `(seller_id, created_time DESC)` 卖家时序索引
- `idx_trade_order_goods`: `(goods_id)` 商品关联索引
- `idx_trade_order_status`: `(order_status)` 状态索引
- `uk_trade_order_active_goods`: PostgreSQL Partial Unique Index
  - 定义：`CREATE UNIQUE INDEX uk_trade_order_active_goods ON campus_trade.trade_order(goods_id) WHERE order_status IN ('WAIT_SELLER_CONFIRM', 'WAIT_MEET')`
  - 实测：当同一商品存在 `WAIT_SELLER_CONFIRM` 或 `WAIT_MEET` 订单时，并发插入第二个活动中订单被数据库底层强制拒绝并抛出 `DataIntegrityViolationException`；而已取消（`CANCELLED`）或已完成（`COMPLETED`）的历史订单不受限制，完美实现二手单一库存防超卖。

---

## 四、自动化测试执行结果 (`mvn test`)

```bash
[INFO] Running com.campustrade.CampusTradeStage4B1Tests
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] Results:
[INFO] 
[INFO] Tests run: 103, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  14.911 s
```
- **测试通过率**：**103 / 103 (100% PASS)**，零失败、零跳过、零回归问题。
