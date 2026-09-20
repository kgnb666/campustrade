# CampusTrade 校园二手交易平台 Stage 6-D 即时通讯与现有系统融合设计规范

> **文档标识**：`docs/stage6/Stage6-D-chat-integration-design.md`  
> **编制阶段**：Stage 6-D（即时通讯系统设计评审阶段）  
> **编制日期**：2026-09-18  
> **当前状态**：INTEGRATION DESIGN COMPLETE  
> **核心原则**：`高内聚低耦合、事件驱动、交易闭环联动、治理闭环协同`

---

## 一、系统融合全景架构

即时通讯系统并非孤立的通信管道，而是串联 CampusTrade 核心业务环节的“互动纽带”。
其与已有子域（商品、订单、信用、举报治理、审计）的交互全景如下：

```text
    ┌──────────────┐          ┌────────────────┐          ┌───────────────────┐
    │  商品 (Goods) │          │ 订单 (TradeOrder)│          │ 举报治理 (Report)  │
    └──────┬───────┘          └───────┬────────┘          └─────────┬─────────┘
           │ 锚定咨询上下文           │ 订单卡片事件推送            │ 违规存证/封禁处置
           ▼                          ▼                             ▼
   ┌────────────────────────────────────────────────────────────────────────┐
   │                       即时通讯子域 (Chat Domain)                         │
   │               Conversation (会话)  +  Message (消息)                   │
   └──────────────────────────────────┬─────────────────────────────────────┘
                                      │ 信用门槛限制 / 违规扣除信用分
                                      ▼
                           ┌─────────────────────┐
                           │ 信用系统 (Credit)    │
                           │ 审计中心 (AdminAudit)│
                           └─────────────────────┘
```

---

## 二、与商品领域（Goods）的融合设计

1. **商品作为会话的生命起点**：
   - 任何用户端发起的咨询会话必须携带 `goodsId`；
   - 会话表 `conversation.goods_id` 物理关联目标商品，建立唯一的买卖家商品沟通通道；
2. **会话顶部的“商品上下文卡片”（Goods Header Card）**：
   - 用户进入聊天界面时，界面顶部常驻展示该商品的关键快照：商品封面缩略图、标题、转让价格、商品当前状态（在售 `ON_SALE`、已锁定 `LOCKED`、已售出 `COMPLETED`、已下架 `OFF_SHELF`）；
   - 支持买家在聊天界面直接点击“立即购买/去下单”按钮，一键调起下单确认页，无需跳出页面重复搜索；
3. **商品状态动态联动感知**：
   - 当卖家将商品修改为 `OFF_SHELF`，或商品被其他买家下单锁定（`LOCKED`）时，聊天界面通过事件感知并向会话信息栏更新商品状态标签，防止买卖双方在已售出商品上发生无效沟通。

---

## 三、与订单领域（TradeOrder）的深度交易闭环联动

传统的二手交易中，订单状态变动往往需要用户反复刷新订单详情；而在即时通讯中，订单的所有关键生命周期事件将**自动转化为系统消息卡片**推入会话中：

| 订单生命周期事件 | 触发源 | 推入聊天的系统卡片内容 | 交互动作（Action Button） |
| :--- | :--- | :--- | :--- |
| **买家下单成功** | 买家提交订单 | 提示双方：`买家已下单，请卖家在 24 小时内确认接单。面交地点：[校区南门]` | 卖家端展示“确认接单”快捷按钮；买家端展示“查看订单详情” |
| **卖家确认接单** | 卖家接单 | 提示双方：`卖家已接单！双方已锁定交易，请在聊天中协商具体的面交时间与细节。` | 展示“修改约定地点”快捷入口 |
| **订单面交完成** | 双方核销完成 | 提示双方：`交易已顺利完成！款项已结清，请为本次交易互相评价。` | 展示“立即去评价”按钮，直跳 Stage 5 评价弹窗 |
| **订单主动取消** | 任一方取消订单 | 提示双方：`订单已被取消。取消原因：[买家协商取消]。商品已重新恢复在售。` | 展示“重新下单”或“查看详情” |

**工程实现要求**：系统消息由服务端内部领域事件触发，直接以 `msg_type = 'SYSTEM'` 写入 `message` 表，其 `extra_data` 结构化记录 `order_id` 与 `order_no`，客户端渲染为卡片组件，不可被用户撤回或伪造。

---

## 四、与举报治理（Report）及管理员审计（AdminAudit）的融合

在 Stage 6-B 中已构建了健全的工单与审计机制。聊天子域的接入将大幅提升对骚扰、侮辱与交易欺诈的威慑力：

### 1. 聊天证据链自动留痕（Evidence Snapshot）
- **长按举报机制**：在聊天窗口中，用户长按任意气泡，可点击“举报该消息/骚扰”；
- **全自动上下文存证**：
  - 用户提交举报时，服务端捕获被举报的 `messageId`；
  - 自动向前抓取 10 条、向后抓取 10 条消息（共 21 条），打包序列化为 JSON 存入 `Report.evidence_images` 或专用证据字段；
  - **优势**：杜绝用户自行截图时利用修图工具“断章取义”或伪造虚假对话，为管理员提供真实、不可篡改的对话事实。

### 2. 管理员治理处置与审计联动
管理员在处理聊天举报工单（`ReportTargetType = MESSAGE`）时，系统提供治理工具：
1. **屏蔽违规单条消息（`SHIELD_MESSAGE`）**：将该消息 `status` 变更为 `SHIELDED`，聊天界面替换为“该消息违反平台规范，已被平台屏蔽”；
2. **冻结违规用户（`FREEZE_USER`）**：对严重涉诈或恶意性骚扰用户，直接将其账号置为 `FROZEN`，其所有活动的会话立即切断；
3. **审计留痕**：每一次治理动作均强制记录至 `campus_trade.admin_audit_log`，留存管理员 ID、处理原因、被处罚人及时间戳。

---

## 五、与信用体系（Credit）的奖惩联动

依托 Stage 5 的信用体系，即时通讯与用户信用深度互锁：
1. **信用准入熔断**：
   - 信用评级为 **`TERRIBLE`（信用严重违规）** 的用户，系统剥夺其主动发起商品聊天会话的权限；
2. **违规精准扣分（Credit Penalty）**：
   - 当管理员采纳针对言语辱骂、恶意骚扰、校外引流诈骗的举报时，系统联动调用：
     ```java
     creditService.deductCredit(
         violatorUserId,
         10, // 扣除 10~20 分
         CreditChangeType.ADMIN_ADJUST,
         "CHAT_MESSAGE",
         messageId,
         "聊天中存在恶劣违规言论/涉诈行为，管理员核实扣罚"
     );
     ```
   - 扣分流水写入 `user_credit_log`，信用分变动透明公示。

---

## 六、未来领域事件（Domain Events）设计规划

为保持模块解耦，推荐未来在 Spring 容器中基于 `@EventListener` 或 Spring `ApplicationEventPublisher` 实现事件解耦：

```java
// 仅规范定义，本阶段不创建具体代码类

/** 1. 消息发送落盘事件：触发异步下行推送与敏感词风控审计 */
public record MessageSentEvent(
    Long messageId, 
    Long conversationId, 
    Long senderId, 
    Long receiverId, 
    String msgType, 
    String content, 
    LocalDateTime sentTime
) {}

/** 2. 会话创建事件：触发未读初始化与商机通知 */
public record ConversationCreatedEvent(
    Long conversationId, 
    Long userAId, 
    Long userBId, 
    Long goodsId
) {}

/** 3. 消息撤回事件：触发下行广播同步状态变更 */
public record MessageRecalledEvent(
    Long messageId, 
    Long conversationId, 
    Long operatorId
) {}

/** 4. 聊天举报触发事件：触发证据快照自动化封存 */
public record ChatReportSubmittedEvent(
    Long reportId, 
    Long conversationId, 
    Long targetMessageId, 
    Long reporterId
) {}
```
**规范声明**：本阶段只规划事件语义与入参契约，严禁在本设计阶段编写任何事件发布或监听实现代码。
