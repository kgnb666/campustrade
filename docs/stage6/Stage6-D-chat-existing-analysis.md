# CampusTrade 校园二手交易平台 Stage 6-D 现有系统即时通讯现状审计报告

> **文档标识**：`docs/stage6/Stage6-D-chat-existing-analysis.md`  
> **编制阶段**：Stage 6-D（即时通讯系统设计评审阶段）  
> **编制日期**：2026-09-18  
> **当前状态**：AUDITED & REVIEWED  
> **核心原则**：`交易安全 > 隐私保护 > 数据一致性 > 可维护性 > 性能`

---

## 一、审计背景与目标

CampusTrade 平台现已完成 Stage 0 至 Stage 5 的用户中心、校园认证、商品检索、交易订单闭环、信用与评价体系，以及 Stage 6-B/6-C 的举报治理、审计日志与评价点赞增强。
在目前的二手交易流程中，买卖双方在线下自提面交前，存在高频的真实沟通诉求（如商品细节咨询、成色确认、面交地点/时间协商、售后答疑）。
本报告对当前后端与前端代码库进行全量审计，盘点既有资产、排查是否存在违规或冗余代码，并评估订单系统、用户安全系统及治理体系对即时通讯（IM）领域的支撑度与安全边界。

---

## 二、代码库即时通讯相关实现现状排查

对 `backend/` 与 `frontend/` 目录进行了全面深度检索（关键字：`chat`、`message`、`websocket`、`socket`、`conversation`、`im`、`notification`），排查结果如下：

### 1. 后端工程 (`backend/`) 排查
- **即时通信协议与依赖**：
  - `pom.xml` 中**完全没有**引入任何与即时通讯相关的依赖组件，例如 `spring-boot-starter-websocket`、Netty 自定义协议栈、STOMP、MQTT 或三方 IM SDK。
  - 项目保持极度轻量与纯洁，未提前编写任何 WebSocket 握手拦截器、Channel 处理器或长连接 Session 容器。
- **`chat` 关键字出现点**：
  - 仅存在于 DeepSeek AI 模块（`DeepSeekProperties.java`、`DeepSeekClient.java`、`AiGoodsServiceImpl.java`），其模型名称为 `deepseek-chat`，调用端点为 `/chat/completions`。此属于 Stage 3 的 AI 商品助手功能，与买卖双方的即时通讯无任何关联。
- **`message` 关键字出现点**：
  - 基础响应对象：`ResultCode.java` 与 `Result.java` 中的提示文本字段 `message`；
  - 异常类定义：`GlobalExceptionHandler.java` 与各类业务异常中的 `e.getMessage()`；
  - 订单表字段：`TradeOrder.java` 与数据库 `campus_trade.trade_order` 中的静态备注列 `buyer_message`（买家下单时的一次性文字备注）与 `seller_reply`（卖家接单回复备注）。
- **`conversation` / `im` / `socket`**：
  - 全局检索结果为 0。完全无会话表、无聊天消息表、无相关 Mapper/Service/Controller。

### 2. 前端工程 (`frontend/`) 排查
- **商品详情页 (`goods_detail_page.dart`)**：
  - 第 513~534 行存在一个“联系卖家”按钮（`Icons.chat_bubble_outline`）；
  - 当前交互为弹窗占位提示：
    ```dart
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('联系卖家'),
        content: Text('已向卖家 ${goods.sellerNickname} 发起会话提醒。\n即时聊天 (IM) 系统将在后续版本上线！'),
        actions: [TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('确定'))],
      ),
    );
    ```
  - 无任何网络请求发出，无 WebSocket 连接建立，仅为纯静态 UI 占位。
- **订单详情页 (`order_detail_page.dart`)**：
  - 第 794~814 行展示订单静态买家留言与卖家回复，为静态文本展示，不具备双向交互能力。

**审计结论**：当前工程**完全没有**实现任何即时通讯业务逻辑，历史阶段严格遵守了红线约束，无架构负债或早产代码。

---

## 三、订单系统连接点与权限依据深度分析

即时通讯系统必须深度依托二手交易场景，绝不可做成泛社交工具。对已有的交易订单系统（`trade_order` 表与 `TradeOrder.java`）进行分析：

### 1. 核心权限字段分析

| 字段名 | 数据类型 | 在聊天系统中的权限与语义价值 |
| :--- | :--- | :--- |
| `buyer_id` | `BIGINT` | **买家法定身份**：确定订单对应会话的买方主体，用于校验当前登录用户是否拥有该订单的聊天权限。 |
| `seller_id` | `BIGINT` | **卖家法定身份**：确定订单对应会话的卖方主体，任何非该两人的第三方访问均应拦截为 IDOR 越权。 |
| `goods_id` | `BIGINT` | **商品锚点**：交易聊天的上下文来源。聊天会话必须挂载商品卡片，方便双方直观了解议价与咨询标的。 |
| `order_status` | `VARCHAR(32)` | **会话状态控制与权限衰减依据**：控制聊天生命周期的关键状态机依据。 |
| `meet_location`| `VARCHAR(255)` | **面交约定**：聊天中双方最终协商一致的地点，后续系统可通过指令或快捷操作反哺订单修改。 |

### 2. 基于订单状态的会话权限衰减规则
在 Stage 4 中，订单具备以下四种状态：
1. `WAIT_SELLER_CONFIRM`（待卖家确认）：买家已下单，此时沟通诉求极高（确认是否有货、是否接受面交时间），双方具备完整聊天权限；
2. `WAIT_MEET`（待线下交付）：卖家已接单，双方正在协商具体交付地点与时间，具备完整聊天权限；
3. `COMPLETED`（交易完成）：订单已终态。买卖双方仍有短暂售后咨询或评价沟通诉求。**设计规则**：允许继续沟通，但在订单完成 **7 天后**，该订单维度的会话自动归档（`CLOSED`），转为只读模式；
4. `CANCELLED`（订单取消）：交易终止。双方争议或无交易可能。**设计规则**：订单取消后 **24 小时内**允许最后说明，超时自动关闭会话，避免持续骚扰。

---

## 四、用户认证与权限系统复用审计

### 1. 现有认证链路资产
- **JWT 机制**：采用 `io.jsonwebtoken` 实现无状态 Token 验证，有效载荷中包含 `username`，签名安全；
- **上下文获取**：通过 `SecurityContextHolder.getContext().getAuthentication()` 可安全提取当前已登录用户的认证主体与角色；
- **统一黑名单**：登出或废弃 Token 会写入 Redis 黑名单（`jwt:blacklist:`），`JwtAuthenticationFilter` 会强制前置过滤。

### 2. 核心风险发现：用户冻结状态检查缺失
在审查 `CustomUserDetailsService.java` 与 `JwtAuthenticationFilter.java` 时发现关键安全隐患：
```java
// CustomUserDetailsService.java
return new org.springframework.security.core.userdetails.User(
    user.getUsername(),
    user.getPassword(),
    "ACTIVE".equalsIgnoreCase(user.getStatus()), // 设置 enabled 标志
    true, true, true,
    Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + user.getRole()))
);
```
- **问题现状**：当管理员在 Stage 6-B 后台冻结违规用户（将其 `status` 置为 `FROZEN`）时，`JwtAuthenticationFilter` 在获取 `UserDetails` 后，直接调用 `new UsernamePasswordAuthenticationToken(userDetails, null, authorities)` 并注入 `SecurityContextHolder`，**并没有校验 `userDetails.isEnabled()`**！
- **严重性**：这意味着已被冻结的用户如果持有尚未过期的 JWT Token，在默认的 REST 请求中依然可能通过过滤器！目前各业务服务中部分进行了 `user.getStatus()` 的二次检查，部分仅依赖 SecurityContext。
- **IM 架构加固要求**：即时通讯属于极度高频且易于传播违规言论的场景，**在握手连接及每条消息发送前，必须强制调用用户状态检查**：若发送方或接收方状态为 `FROZEN`，必须直接拒绝消息并切断连接！

---

## 五、举报治理系统与聊天违规内容接入分析

Stage 6-B 已落地了一套成熟且稳健的治理闭环体系（`Report`、`AdminGovernanceService`、`AdminAuditLog`），但目前仅覆盖 `GOODS`、`REVIEW`、`USER` 三大目标。

### 1. 接入聊天治理的改造方案
1. **多态目标枚举扩充**：
   - 未来在 `ReportTargetType` 枚举中扩展 `MESSAGE`（举报单条违规消息）或 `CONVERSATION`（举报整个违规会话）；
   - 在用户端聊天气泡提供“长按/点击举报”入口；
2. **上下文自动留痕取证**：
   - 传统商品/评价举报由用户上传图片证据，但聊天记录如果由用户自行截图，极易被篡改或伪造；
   - **自动化存证设计**：当用户在会话中发起举报时，服务端自动提取该违规消息及其前后各 10 条（共 21 条）消息快照，生成结构化只读存证 JSON 或关联索引，存入工单证据链中，确保管理员取证真实可靠；
3. **治理动作协同**：
   - 管理员采纳举报后，可执行：
     - 单条违规消息屏蔽（`SHIELD_MESSAGE`）；
     - 限制违规用户即时通讯权限（禁言 7 天 / 30 天）；
     - 严重违规直接冻结账号（`FREEZE_USER`），联动 `CreditService` 进行信用重罚；
   - 全部操作强制记录进 `AdminAuditLog`，实现全链路透明可追溯。

---

## 六、审计总结

1. **零历史包袱**：当前无任何残留 IM 代码，为设计干净、高内聚、低耦合的即时通讯领域模型提供了绝佳条件；
2. **场景依托清晰**：即时通讯必须锚定在商品与订单两大核心实体之上，杜绝无边界泛社交化；
3. **安全基线明确**：必须填补用户冻结拦截短板，确立以 PostgreSQL 为真理源、结合已有举报审计框架的严密治理底线。
