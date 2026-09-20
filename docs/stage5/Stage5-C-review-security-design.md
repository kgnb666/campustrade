# Stage 5-C: 评价系统安全与风控防护设计方案

> **文档标识**：`docs/stage5/Stage5-C-review-security-design.md`  
> **阶段**：Stage 5-C（评价系统设计与信用联动）  
> **性质**：安全架构与风控防线规格书  

---

## 一、越权攻击防护 (IDOR Prevention)

### 1.1 威胁模型：水平越权与替身投毒
- **威胁场景 A（替身评价）**：攻击者传入合法完成的 `orderId`，但在请求体中伪造 `reviewedUserId`，企图将恶意差评投递给无辜第三人，或将 5 星好评定向刷给自己的关联小号。
- **威胁场景 B（窃权评价）**：攻击者截获他人的已完成 `orderId`，以自己的身份发起评价，篡改原本属于他人对交易的真实反馈。

### 1.2 防御架构与实现规范
```
[客户端请求: POST /api/reviews]
           │ 仅携带: orderId, score, content, tags, isAnonymous
           ▼
[Controller: 获取当前安全上下文中的当前登录用户 ID (currentUserId)]
           │
           ▼
[Service: 强一致查询 trade_order 主档]
           │
           ├─ 校验 1: order 是否存在？(不存在 -> 404)
           ├─ 校验 2: orderStatus 是否为 COMPLETED？(非完成 -> 422)
           ├─ 校验 3: currentUserId == buyerId ?
           │           ├─ 是 -> 认定身份为【买家评价卖家】，后端锁定: reviewedUserId = order.sellerId
           │           └─ 否 -> 检查 currentUserId == sellerId ?
           │                     ├─ 是 -> 认定身份为【卖家评价买家】，后端锁定: reviewedUserId = order.buyerId
           │                     └─ 否 -> 【非法越权】，抛出 403 Forbidden
           ▼
[后端安全闭环: 严禁客户端在 Request Body 中传递 reviewedUserId 字段！]
```

---

## 二、虚假评价与刷信誉防线

1. **真实交易绑定事实源**：
   - 彻底拒绝“无单评价”或“下单未交付评价”；
   - 依赖 PostgreSQL 事务内行锁校验，只有买卖双方共同在物理现场完成面交（`WAIT_MEET -> COMPLETED`）后，才开放该订单的评价权。
2. **自买自评免疫机制**：
   - Stage 4 订单创建接口已从业务层与数据库层强行限制 `buyerId != sellerId`，从根源切断了“自己发布商品、自己下单、自己给自己打 5 星刷信用”的途径。
3. **评价时效闸门**：
   - 严格限定 `completedTime` 后的 168 小时（7 天）窗口。杜绝几个月后通过历史僵尸订单批量改评或突然翻旧账恶意报复。

---

## 三、评价不可篡改性与防敲诈设计

在电商与二手交易中，最普遍的恶性行为是**“差评勒索”**（买家给出 1 星差评，私下要求卖家发红包或返现才改好评）：
- **不可撤销不可修改设计**：
  - 后端**完全不提供** `PUT /api/reviews/{id}` 或 `DELETE /api/reviews/{id}` 接口；
  - 评价提交成功即为**不可逆终态**；
  - 杜绝一切以“修改好评”为筹码的利益输送与勒索可能。
- **平台仲裁闭环**：
  - 遇到事实不清、恶意报复差评的，仅允许通过平台客服/管理员发起人工争议核实，由管理员执行单条评价屏蔽（`status = AUDIT_REJECTED`），并通过流水表 `ADMIN_ADJUST` 予以冲正，杜绝私下暗箱操作。

---

## 四、文本内容安全与 XSS 防护

1. **富文本与脚本注入（XSS）拦截**：
   - 评价文字 `content` 与标签 `tags` 严格做文本转义处理；
   - 数据库存储纯文本，禁止执行任何 HTML / JavaScript 标签渲染；
   - 标签字段限制最大 5 个且每个最长 20 字符，由白名单字典（预设常用标签）优先匹配。
2. **校园敏感词过滤预留接口 (Hook)**：
   - 预留 `SensitiveWordFilter` 拦截管道：
   ```java
   public interface SensitiveWordFilter {
       boolean containsSensitiveWord(String text);
       String mask(String text);
   }
   ```
   - 若命中政治敏感、暴恐、涉黄或极其恶劣的辱骂词汇：系统自动驳回提交或将评价置为待人工复核（`AUDIT_REJECTED`），不予公网公开展示，并记录安全告警日志。
