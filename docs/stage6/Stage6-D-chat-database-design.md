# CampusTrade 校园二手交易平台 Stage 6-D 即时通讯数据库设计规范

> **文档标识**：`docs/stage6/Stage6-D-chat-database-design.md`  
> **编制阶段**：Stage 6-D（即时通讯系统设计评审阶段）  
> **编制日期**：2026-09-18  
> **当前状态**：DESIGN SPECIFICATION (仅设计规范，严禁生成实体 SQL 迁移文件)  
> **核心原则**：`PostgreSQL 为唯一 Source of Truth，强一致、零脏读、高性能游标分页`

---

## 一、Flyway V9 迁移规划概述

在未来进入实现阶段后，即时通讯数据表将由 `V9__create_chat_domain.sql` 引入 `campus_trade` 模式。
本规范详细规划两张核心数据表的设计方案：
1. `campus_trade.conversation`（会话表）：存储会话元信息与最新快照；
2. `campus_trade.message`（消息明细表）：存储有序消息流。

---

## 二、会话表（`conversation`）结构设计

### 1. 字段设计与规范说明

| 列名 | 类型 | 空值约束 | 默认值 | 语义说明与设计初衷 |
| :--- | :--- | :---: | :---: | :--- |
| `id` | `BIGSERIAL` | NOT NULL | 自增序列 | 物理主键，唯一标识一个会话实例 |
| `user_a_id` | `BIGINT` | NOT NULL | - | 参与者 A 用户 ID（**规则约束：恒定保证 `user_a_id < user_b_id`**） |
| `user_b_id` | `BIGINT` | NOT NULL | - | 参与者 B 用户 ID |
| `goods_id` | `BIGINT` | NULL | NULL | 关联咨询的商品 ID，外键关联 `goods(id)` |
| `order_id` | `BIGINT` | NULL | NULL | 关联履约的订单 ID，外键关联 `trade_order(id)` |
| `last_message_id` | `BIGINT` | NULL | NULL | 最新一条消息的 ID，用于极速定位消息游标 |
| `last_message_content` | `VARCHAR(500)`| NULL | NULL | 最新一条消息的文本快照（图片则存 `[图片]`，系统卡片存 `[系统通知]`） |
| `last_message_time` | `TIMESTAMP` | NOT NULL | CURRENT_TIMESTAMP | 最新一条消息的时间戳，用于会话列表倒序排序 |
| `unread_count_a` | `INT` | NOT NULL | 0 | 参与者 A 当前未读消息数，非负检查约束 `CHECK (unread_count_a >= 0)` |
| `unread_count_b` | `INT` | NOT NULL | 0 | 参与者 B 当前未读消息数，非负检查约束 `CHECK (unread_count_b >= 0)` |
| `status` | `VARCHAR(20)` | NOT NULL | 'ACTIVE' | 会话状态：`ACTIVE`, `BLOCKED`, `CLOSED` |
| `created_time` | `TIMESTAMP` | NOT NULL | CURRENT_TIMESTAMP | 会话创建时间 |
| `updated_time` | `TIMESTAMP` | NOT NULL | CURRENT_TIMESTAMP | 会话最后更新时间 |

### 2. 用户 ID 归一化（Canonical Ordering）机制
- **问题**：若买家 1001 找卖家 1002 咨询商品 5001，系统生成一条记录；如果卖家 1002 回访买家 1001，若不对参数排序，可能生成 `(1001, 1002)` 和 `(1002, 1001)` 两条重复会话，导致消息分裂；
- **解决规则**：在代码层与数据库物理层，**强制要求入库时 `user_a_id = MIN(uid1, uid2)` 且 `user_b_id = MAX(uid1, uid2)`**；
- **物理唯一索引**：
  ```sql
  -- 规范设计（非执行）：同一买卖双方针对同一商品在系统中绝对只存在一条会话
  CREATE UNIQUE INDEX uk_conversation_users_goods 
      ON campus_trade.conversation (user_a_id, user_b_id, goods_id);
  ```

### 3. 会话列表查询与专用覆盖索引
用户打开“消息”页面时，需要按最新消息时间倒序展示会话列表。
由于用户既可能是 `user_a`，也可能是 `user_b`，因此配置联合索引：
```sql
CREATE INDEX idx_conv_user_a_time ON campus_trade.conversation (user_a_id, last_message_time DESC);
CREATE INDEX idx_conv_user_b_time ON campus_trade.conversation (user_b_id, last_message_time DESC);
```
**查询 SQL 范式**：
```sql
SELECT * FROM campus_trade.conversation 
WHERE (user_a_id = :currentUserId OR user_b_id = :currentUserId)
  AND status != 'BLOCKED'
ORDER BY last_message_time DESC 
LIMIT 20;
```
联合索引确保极速返回，无需扫描未参与会话。

---

## 三、消息表（`message`）结构设计

### 1. 字段设计与规范说明

| 列名 | 类型 | 空值约束 | 默认值 | 语义说明与设计初衷 |
| :--- | :--- | :---: | :---: | :--- |
| `id` | `BIGSERIAL` | NOT NULL | 自增序列 | 物理主键，单调递增，天然充当游标 Pagination 的基准 |
| `conversation_id` | `BIGINT` | NOT NULL | - | 所属会话 ID，外键关联 `conversation(id)` ON DELETE CASCADE |
| `sender_id` | `BIGINT` | NOT NULL | - | 消息发送方用户 ID，外键关联 `user(id)` |
| `receiver_id` | `BIGINT` | NOT NULL | - | 消息接收方用户 ID，外键关联 `user(id)` |
| `msg_type` | `VARCHAR(20)`| NOT NULL | 'TEXT' | 消息类型：`TEXT`, `IMAGE`, `SYSTEM` |
| `content` | `TEXT` | NOT NULL | - | 消息体正文（文本、图片 URL 或系统卡片标题） |
| `extra_data` | `JSONB` | NULL | NULL | 扩展结构化元数据（图片宽高、订单号快照、卡片跳转 URL 等） |
| `is_read` | `BOOLEAN` | NOT NULL | FALSE | 接收方是否已读 |
| `read_time` | `TIMESTAMP` | NULL | NULL | 接收方已读时间 |
| `status` | `VARCHAR(20)`| NOT NULL | 'NORMAL' | 消息状态：`NORMAL`（正常展示）, `RECALLED`（已撤回）, `SHIELDED`（违规屏蔽） |
| `client_msg_id` | `VARCHAR(64)`| NOT NULL | - | 客户端生成的 UUID，用于发送接口的网络幂等去重 |
| `created_time` | `TIMESTAMP` | NOT NULL | CURRENT_TIMESTAMP | 消息入库落盘时间 |

### 2. 核心索引设计

1. **游标分页核心索引（Cursor Pagination）**：
   ```sql
   CREATE INDEX idx_msg_conv_id ON campus_trade.message (conversation_id, id DESC);
   ```
2. **客户端网络幂等唯一索引**：
   ```sql
   CREATE UNIQUE INDEX uk_msg_client_id ON campus_trade.message (sender_id, client_msg_id);
   ```
   **效果**：客户端弱网重试时，相同 `client_msg_id` 会触发唯一键冲突，直接返回已有消息对象，杜绝同一条消息在界面重复展示。
3. **未读消息扫描索引**：
   ```sql
   CREATE INDEX idx_msg_unread ON campus_trade.message (conversation_id, receiver_id) WHERE is_read = FALSE;
   ```
   部分索引极大加速一键标记全会话已读操作。

---

## 四、核心读写场景与 SQL 执行范式

### 1. 聊天记录游标分页（Cursor Pagination）
- **传统 Offset 分页的缺陷**：在翻阅历史聊天时，若执行 `OFFSET 1000 LIMIT 20`，随着新消息不断插入，传统的页码会发生“数据飘移”导致重复拉取；且 Deep Paging 会导致数据库全量扫描前面的所有行。
- **基于单调递增 ID 的 Cursor 分页范式**：
  - **首屏加载（最新 20 条）**：
    ```sql
    SELECT * FROM campus_trade.message 
    WHERE conversation_id = :conversationId 
    ORDER BY id DESC 
    LIMIT 20;
    ```
    客户端接收后逆序排列展示在聊天视窗底部。
  - **向上翻阅更早的历史记录（以最顶上一条消息 ID 作为 `cursor`）**：
    ```sql
    SELECT * FROM campus_trade.message 
    WHERE conversation_id = :conversationId 
      AND id < :cursorMessageId 
    ORDER BY id DESC 
    LIMIT 20;
    ```
    **性能优势**：无论总消息量是 1 万条还是 1000 万条，单次索引定位仅需消耗 $O(\log N)$，耗时恒定在 0.5ms 以内，彻底消除 Deep Paging 损耗。

### 2. 发送消息时的原子事务更新
当发送方 `senderId` 发送消息落盘时，必须在同一个数据库本地事务中执行：
```sql
-- 1. 插入消息明细
INSERT INTO campus_trade.message (conversation_id, sender_id, receiver_id, msg_type, content, client_msg_id) 
VALUES (:convId, :senderId, :receiverId, :msgType, :content, :clientMsgId) 
RETURNING id, created_time;

-- 2. 原子更新会话快照与未读计数 (假设 receiverId 为 user_b)
UPDATE campus_trade.conversation 
SET last_message_id = :newMsgId,
    last_message_content = :contentSnapshot,
    last_message_time = :msgCreatedTime,
    unread_count_b = unread_count_b + 1,
    updated_time = CURRENT_TIMESTAMP 
WHERE id = :convId;
```

### 3. 打开会话与已读原子标记
当用户 `receiverId` 进入聊天页面时：
```sql
-- 1. 批量更新该会话下所有发给自己的未读消息为已读
UPDATE campus_trade.message 
SET is_read = TRUE, read_time = CURRENT_TIMESTAMP 
WHERE conversation_id = :convId 
  AND receiver_id = :currentUserId 
  AND is_read = FALSE;

-- 2. 原子重置会话表对应未读计数为 0
UPDATE campus_trade.conversation 
SET unread_count_a = (CASE WHEN user_a_id = :currentUserId THEN 0 ELSE unread_count_a END),
    unread_count_b = (CASE WHEN user_b_id = :currentUserId THEN 0 ELSE unread_count_b END),
    updated_time = CURRENT_TIMESTAMP 
WHERE id = :convId;
```
两条 SQL 均命中索引，毫秒级完成，保证用户端未读红点精确清零。
