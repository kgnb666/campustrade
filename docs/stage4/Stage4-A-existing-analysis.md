# Stage 4-A: 当前已有系统审查与订单模型复用分析报告

- **审查对象**：CampusTrade 校园二手交易平台（Stage 0 ~ Stage 3.5 真实代码库）
- **审查目标**：评估商品、用户、信用模型及数据库架构对 Stage 4 交易订单系统的支撑能力与潜在风险。

---

## 一、当前已有领域模型审查

### 1. 商品模型 (`Goods` & `campus_trade.goods`)
- **主键与归属**：
  - `id`: BIGSERIAL (自增主键，唯一标识一件二手物品)
  - `seller_id`: BIGINT (明确归属于某位在校学生用户)
  - `school_id`: BIGINT (明确归属于某一高校校区)
- **核心交易要素**：
  - `title`: VARCHAR(100) (商品名称)
  - `price`: DECIMAL(10,2) (发布时的出售单价)
  - `original_price`: DECIMAL(10,2) (入手原价，选填)
  - `condition_level`: VARCHAR(20) (成色级别，如全新、95新等)
  - `location`: VARCHAR(100) (卖家设定的建议交易地点，如“学二食堂门口”)
- **状态字段现状**：
  - `status`: VARCHAR(20) DEFAULT 'ON_SALE'
  - 源码注释预留状态：`DRAFT, ON_SALE, LOCKED, SOLD, OFF_SHELF`
  - **当前生产流转状态**：Stage 2~3 中仅激活并使用了 `ON_SALE` (在售) 与 `OFF_SHELF` (下架)。状态字典已在概念上预留了 `LOCKED` (交易锁定中) 与 `SOLD` (已售出)，为 Stage 4 订单状态联动提供了天然契合点。

### 2. 用户与信用模型 (`User`, `StudentVerify`, `UserCredit`)
- **用户主体 (`campus_trade."user"`)**：
  - `id`: BIGINT (雪花算法 ID)
  - `username`, `nickname`, `avatar`, `phone`, `email`
  - `status`: 'ACTIVE' / 'DISABLED'
- **校园身份认证 (`campus_trade.student_verify`)**：
  - `user_id`, `school_id`, `student_number`, `verify_status` ('SUCCESS', 'PENDING', 'REJECTED')
  - **交易前提**：Stage 1 已实现校园认证闭环，买家和卖家发起/确认订单前必须具备真实校园身份，确保交易安全性。
- **用户信用档案 (`campus_trade.user_credit`)**：
  - `user_id`: BIGINT UNIQUE
  - `credit_score`: 初始 100 分
  - `trade_count`: 累计完成交易笔数，当前默认为 0
  - `good_review_count`: 好评次数，当前默认为 0
  - `bad_review_count`: 差评次数，当前默认为 0
  - **复用价值**：`trade_count` 字段已在数据库和实体中就绪，Stage 4 订单达成终态 `COMPLETED` 时可直接累加双方信用交易量。

### 3. Flyway 当前版本现状
- `V1__init_user_and_auth_schema.sql`：用户与认证、信用表结构
- `V2__init_goods_and_category_schema.sql`：分类、商品、图片、标签表结构
- `V3__init_interaction_schema.sql`：收藏、浏览足迹、搜索历史表结构
- **版本规划**：Stage 4 的订单模块表结构将作为 **`V4__init_order_schema.sql`** 进行演进，绝不修改、不破坏 V1~V3 既有脚本。

---

## 二、可以完全复用的字段与能力

1. **买卖双方与校区识别**：
   - 买家 ID 来自当前登录上下文 JWT `SecurityUtils.getCurrentUserId()`；
   - 卖家 ID 可直接从 `goods.seller_id` 获取；
   - 校验同校交易可直接比对买家与卖家各自在 `student_verify.school_id` 或 `goods.school_id`。
2. **履约地点建议**：
   - `goods.location` 可作为买家发起订单时的默认面交候选地点。
3. **信用档案更新**：
   - `user_credit.trade_count` 无需改动表结构，即可无缝承接订单履约信用沉淀。

---

## 三、当前缺失的字段与核心模型

1. **订单核心实体缺失**：
   - 当前项目完全没有 `trade_order` / `Order` 相关的数据库表、Mapper、Entity、DTO、VO 和 Controller。
2. **交易快照机制缺失**：
   - 卖家在商品发布后随时可能再次编辑标题、价格或描述；
   - 当前无任何订单快照字段，若直接外键关联商品，商品改价或编辑后历史订单记录将被篡改。
3. **面交履约信息缺失**：
   - 缺乏记录实际约定的面交地点、买家提货时间期望、买家留言、卖家接单回复的实体属性。
4. **流转审计时间线缺失**：
   - 缺乏发起时间、卖家确认接单时间、确认成交时间、取消时间的完整状态机时序凭证。

---

## 四、潜在业务与技术风险分析

1. **二手闲置库存单一性 (Single-Inventory Concurrency Race)**：
   - 与传统电商多库存标品不同，校园二手平台每件闲置物品库存通常仅为 **1 件**；
   - **风险**：若两个买家同时对同一在售商品发起交易请求，若无并发互斥机制，可能产生两个处于活动中的订单，导致卖家面临“一货多卖”的不可行状态；
   - **应对**：必须在数据库层与状态机层建立严格的唯一约束或行锁互斥。
2. **自买自卖逻辑漏洞 (Self-Buying Vulnerability)**：
   - **风险**：卖家使用自己的账号购买自己发布的商品，用于刷取 `trade_count` 信用分；
   - **应对**：在领域模型校验与 API 入口处硬编码 `buyer_id != seller_id` 守卫。
3. **离线现金/面交特性 (Offline Peer-to-Peer)**：
   - **特性**：校园二手交易是纯线下场景，双方一手交钱（现金或当面微信/支付宝转账）一手交货；
   - **风险**：平台若强行引入线上支付网关，反而大幅增加交易摩擦与提现费率；
   - **应对**：坚持纯线下校园自提/面交原则，不设在线支付，通过卖家确认接单 -> 双方线下见面核验 -> 当面确认完成形成信任闭环。
