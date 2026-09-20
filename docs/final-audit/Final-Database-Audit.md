# CampusTrade 校园二手交易平台 生产级最终审查：数据库设计与物理约束审计报告

> **文档标识**：`docs/final-audit/Final-Database-Audit.md`  
> **审查主体**：资深数据库架构师团队  
> **审计日期**：2026-09-18  
> **审计对象**：PostgreSQL 16 物理表、Flyway V1~V8 迁移脚本、`init.sql`、事务一致性  
> **数据库审计评级**：**PASS (结构健全、物理约束严密、容器初始化 100% 一致)**

---

## 一、Flyway 迁移链路与文件一致性审查

对 `backend/src/main/resources/db/migration/` 下的 8 个版本化迁移脚本进行了全量 Hash 校验与依赖分析：

| 版本号 | 迁移文件名 | 核心 DDL / DML 内容 | 历史篡改风险 | 校验结论 |
| :---: | :--- | :--- | :---: | :---: |
| **V1** | `V1__init_user_and_auth_schema.sql` | 创建 `user`, `campus_school`, `student_verify` 表与认证索引 | 无 | **PASS** |
| **V2** | `V2__init_goods_and_category_schema.sql` | 创建 `goods`, `goods_category`, `goods_image`, `goods_tag` 表 | 无 | **PASS** |
| **V3** | `V3__init_interaction_schema.sql` | 创建 `favorite`, `browse_history`, `search_history` 互动表 | 无 | **PASS** |
| **V4** | `V4__init_order_schema.sql` | 创建 `trade_order` 订单表及 `uk_trade_order_active_goods` 部分唯一索引 | 无 | **PASS** |
| **V5** | `V5__upgrade_credit_domain.sql` | 创建 `user_credit`, `user_credit_log` 信用积分表与幂等索引 | 无 | **PASS** |
| **V6** | `V6__create_review_domain.sql` | 创建 `review` 交易评价表及分值范围 CHECK 约束 | 无 | **PASS** |
| **V7** | `V7__create_governance_domain.sql` | 创建 `report` 举报工单表及 `admin_audit_log` 审计日志表 | 无 | **PASS** |
| **V8** | `V8__create_review_like_domain.sql` | 增加 `like_count` 字段、非负约束与 `review_like` 点赞明细表 | 无 | **PASS** |

- **历史迁移完整性**：所有 V1～V7 历史迁移脚本在后续开发中保持“只读”，没有任何回溯篡改行为；
- **容器与生产初始化同步性**：`docker/postgres/init.sql` 与 Flyway V1～V8 累加最终状态进行了逐行对比，**17 张表、全部复合索引、部分唯一索引与 CHECK 约束 100% 完全同步，一致性评级为 A+**。

---

## 二、表设计、物理约束与索引性能深度审计

### 1. 物理防并发攻击：部分唯一索引（Partial Unique Indexes）
项目创新性且高效地使用了 PostgreSQL 原生支持的带 `WHERE` 条件的部分唯一索引，在数据库内核层面彻底消除并发脏数据：
1. **防并发重复下单**：
   ```sql
   CREATE UNIQUE INDEX uk_trade_order_active_goods 
       ON campus_trade.trade_order(goods_id) 
       WHERE order_status IN ('WAIT_SELLER_CONFIRM', 'WAIT_MEET');
   ```
   **效果**：同一件二手商品在未结单前，物理级保证至多存在一条活跃订单，彻底封死商品“一物多卖 / 超卖”漏洞。
2. **防恶意重复刷举报单**：
   ```sql
   CREATE UNIQUE INDEX uk_report_active 
       ON campus_trade.report (reporter_id, target_type, target_id) 
       WHERE status = 'PENDING';
   ```
   **效果**：同一举报人对同一实体处于待处理状态时，无法并发或反复提交工单，从数据库引擎层防御工单洪峰攻击。

### 2. 幂等与业务唯一约束
- **评价防重复提交**：`uk_review_order_reviewer (order_id, reviewer_id)` 物理保证订单单方不可重复评价；
- **点赞防重复点赞**：`uk_review_like_review_user (review_id, user_id)` 物理保证一人对一评价至多点赞一次；
- **信用流水幂等约束**：`uk_credit_log_idempotent (user_id, related_type, related_id, change_type)` 杜绝因网络重试导致积分重复增加或追扣。

### 3. CHECK 约束与防下溢保护
- 评价分值约束：`CONSTRAINT chk_review_score_range CHECK (score >= 1 AND score <= 5)`；
- 点赞数防下溢约束：`CONSTRAINT chk_review_like_count_non_negative CHECK (like_count >= 0)`；配合原子 SQL `GREATEST(0, like_count - 1)`，构筑了双保险；
- 举报状态与类型约束：`chk_report_target_type` 与 `chk_report_status` 严格限定枚举值。

### 4. 索引覆盖度与慢查询风险评估
- 审查所有分页列表接口：
  - 订单列表：覆盖 `(buyer_id, created_time DESC)` 与 `(seller_id, created_time DESC)`；
  - 评价列表：覆盖 `(reviewed_user_id, status, created_time DESC)` 与 `(goods_id, status, created_time DESC)`；
  - 治理工单列表：覆盖 `(status, created_time DESC)`；
  - 审计日志：覆盖 `(created_time DESC)`、`(target_type, target_id)`、`(admin_id, created_time DESC)`。
- **结论**：所有涉及用户与管理员的高频查询均精准命中联合索引，杜绝了全表扫描与临时表排序。

---

## 三、事务一致性与长事务（Long Transaction）风险排查

### 1. 事务边界合理性审查
审查所有标注 `@Transactional` 的 Service 实现方法：
- **订单创建**：`OrderServiceImpl.createOrder` 包含订单插入与商品状态变更为 `LOCKED`，同属本地事务，强一致；
- **订单完成**：`OrderServiceImpl.completeOrder` 包含订单状态变更、商品 `SOLD_OUT` 与买卖双方信用分增加，同属本地事务，强一致；
- **评价提交**：`ReviewServiceImpl.createReview` 包含评价记录插入与 `creditService.addCredit` / `deductCredit`，同属本地事务，强一致；
- **违规屏蔽与冲正**：`AdminGovernanceServiceImpl.handleReport` 包含工单流转、违规下架/屏蔽、信用反向追缴与审计日志插入，全流程同事务闭环。

### 2. 外部慢调用与长事务排查（Critical Check）
- **原则**：严禁在 `@Transactional` 本地数据库事务内发起任何外部第三方 HTTP 请求（如 DeepSeek AI 调用、邮件发送），否则外部网络慢响应会导致数据库连接池被长久占用引发雪崩；
- **审计结果**：
  - DeepSeek AI 助手服务（`AiGoodsServiceImpl`）全部为纯无事务方法；
  - 邮件验证码发送逻辑位于业务事务之外；
  - **结论**：**零长事务隐患，事务边界控制极佳**。
