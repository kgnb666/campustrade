> ⚠️ **历史报告（修复前快照，结论已过时）**
>
> 本文产生于 2026-09-20 上午的审计，**早于随后完成的 Stage 1–8 加固**。
> 文中诸如「189/189 测试通过」「Flyway V1–V8」「init.sql 与迁移 100% 同步」「裁决 NEEDS OPTIMIZATION」
> 等结论**均已被推翻**，请勿作为项目现状依据。
>
> 当前状态请以以下为准：
> - `README.md` / `docs/README.md`（已校正的阶段、端口、变量、门禁与部署说明）
> - Stage 1–8 的提交记录（工程地基 → 认证安全 → 校园认证 → 数据一致性 → 契约收敛 → 前端稳定性 → 性能 → 生产交付）
> - 测试基线：**后端 255 项、前端 176 项**（阶段 8 时为 242 / 142；见根目录 `CHANGELOG.md`）
>
> 修复过程与结论见同目录 `Stage-Fix-*.md`。

---

# CampusTrade 校园二手交易平台 生产级最终审查：安全审计报告

> **文档标识**：`docs/final-audit/Final-Security-Audit.md`  
> **审查主体**：安全审计专家团队  
> **审计日期**：2026-09-18  
> **审计范围**：认证授权、IDOR 水平越权、输入与文件安全、管理员治理安全  
> **总体安全评级**：**HIGH (整体防御严密，发现 1 项 P1 权限穿透风险需上线前修复)**

---

## 一、漏洞与风险全景摘要

| 编号 | 风险类型 | 风险描述 | 影响组件/位置 | 风险等级 | 状态 |
| :---: | :--- | :--- | :--- | :---: | :---: |
| **SEC-01** | **身份认证/权限失效** | 被冻结（`FROZEN`）用户持有效 Token 可绕过过滤器继续调用 API | `JwtAuthenticationFilter.java` | **P1 (HIGH)** | 待修复 |
| **SEC-02** | **业务机制不全** | Refresh Token 有颁发与存储但缺少刷新端点 | `AuthController.java` | **P2 (MEDIUM)** | 待补充 |
| **SEC-03** | **文件上传安全** | 图片上传缺少 Magic Bytes（文件头二进制魔数）校验 | `FileServiceImpl.java` | **P2 (MEDIUM)** | 建议加固 |
| **SEC-04** | **跨站脚本 (XSS)** | 富文本输入未进行 HTML 实体转义（Web 端展示隐患） | `Goods/Review/Order` | **P3 (LOW)** | 建议加固 |
| **SEC-05** | **水平越权 (IDOR)** | 订单、评价、商品、工单水平越权全量审查 | 核心业务 Controller | **SECURE** | **100% 免疫** |
| **SEC-06** | **SQL 注入漏洞** | 全局 SQL 拼接与预编译参数审查 | 核心 Mapper | **SECURE** | **100% 免疫** |

---

## 二、重点安全专项深度审计

### 1. 认证安全与 Token 机制

#### 【漏洞 SEC-01 详细报告】被冻结用户绕过 JWT 过滤器漏洞
- **漏洞位置**：  
  [`backend/src/main/java/com/campustrade/security/JwtAuthenticationFilter.java`](file:///d:/wkk/Second-hand%20trading%20platform/backend/src/main/java/com/campustrade/security/JwtAuthenticationFilter.java#L52-L60)
- **风险等级**：**P1 (HIGH)**
- **攻击方式与复现逻辑**：
  1. 恶意用户正常登录系统，获得有效期为 2 小时的 Access Token；
  2. 随后该用户在平台发布违禁品或进行诈骗，管理员在后台核实并将其状态设置为 `FROZEN`；
  3. `CustomUserDetailsService.loadUserByUsername` 中将 UserDetails 的 `enabled` 设为 `false`（因为 `"ACTIVE".equalsIgnoreCase(user.getStatus()) == false`）；
  4. 然而在 `JwtAuthenticationFilter` 中，代码直接构造了凭据并注入上下文：
     ```java
     UserDetails userDetails = userDetailsService.loadUserByUsername(username);
     // 漏洞点：直接构造 Authentication，未校验 userDetails.isEnabled()！
     UsernamePasswordAuthenticationToken authentication =
             new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
     SecurityContextHolder.getContext().setAuthentication(authentication);
     ```
  5. 此时只要该用户的 JWT 尚未过期，其请求便能顺利通过 Spring Security 过滤器链进入 Controller。由于 `GoodsService`、`OrderService`、`ReviewService` 并未在每个方法内再次重复比对 `user.getStatus()`，被冻结的恶意用户在 Token 过期前仍能继续下单、修改商品或发表评价！
- **修复建议**：
  在 `JwtAuthenticationFilter.java` 第 54 行加载 `userDetails` 后，增加对账户启用状态的显式断言：
  ```java
  if (!userDetails.isEnabled()) {
      log.warn("用户已被冻结/禁用，拒绝访问: {}", username);
      response.setStatus(HttpServletResponse.SC_FORBIDDEN);
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      Result<Void> result = Result.error(ResultCode.FORBIDDEN.getCode(), "账号已被冻结或禁用，请联系平台管理员");
      response.getWriter().write(new ObjectMapper().writeValueAsString(result));
      return; // 阻断过滤链，直接返回 403
  }
  ```

---

### 2. IDOR（水平越权漏洞）全量代码扫描

安全审计团队对所有暴露 `/{id}` 的 Controller 进行了穿透式代码级审计：

1. **订单详情接口：`GET /api/orders/{id}`**
   - **审计结论**：**SECURE (PASS)**
   - **验证代码**（[`OrderServiceImpl.java:L404-L408`](file:///d:/wkk/Second-hand%20trading%20platform/backend/src/main/java/com/campustrade/service/impl/OrderServiceImpl.java#L404-L408)）：
     ```java
     boolean isBuyer = Objects.equals(order.getBuyerId(), currentUserId);
     boolean isSeller = Objects.equals(order.getSellerId(), currentUserId);
     if (!isBuyer && !isSeller) {
         throw new OrderBusinessException(403, "无权查看该订单详情");
     }
     ```
   - 彻底阻断了任何第三方用户窃取非本人参与订单信息的可能。
2. **订单双向评价查询：`GET /api/reviews/order/{orderId}`**
   - **审计结论**：**SECURE (PASS)**
   - **验证代码**（[`ReviewServiceImpl.java:L221-L225`](file:///d:/wkk/Second-hand%20trading%20platform/backend/src/main/java/com/campustrade/service/impl/ReviewServiceImpl.java#L221-L225)）：
     ```java
     boolean isBuyer = currentUserId != null && Objects.equals(order.getBuyerId(), currentUserId);
     boolean isSeller = currentUserId != null && Objects.equals(order.getSellerId(), currentUserId);
     if (!isBuyer && !isSeller) {
         throw new AccessDeniedException("您不是该订单的参与方，无权查看订单评价信息");
     }
     ```
3. **商品编辑与下架：`PUT /goods/{id}`, `DELETE /goods/{id}`**
   - **审计结论**：**SECURE (PASS)**
   - 均强制校验 `goods.getSellerId().equals(userId)`，非本人操作一律抛出 `AccessDeniedException`。
4. **举报工单接口**：
   - 用户端仅开放 `GET /api/reports/my`，直接使用 `SecurityUtils.getCurrentUserId()` 过滤自身提交的记录，未对外开放根据 `reportId` 随意拉取数据的接口，完全消除越权隐患。

---

### 3. 用户输入安全与文件上传漏洞审计

1. **SQL 注入专项检查**：
   - 全局扫描全部 Mapper 接口与 SQL 调用；
   - 整个项目没有任何一处使用 MyBatis 的 `${}` 字符串拼接，全部使用 `#{}` 参数化占位符；动态条件全部使用 `LambdaQueryWrapper` 生成预编译 SQL；
   - **结论**：**零 SQL 注入风险，100% 达标**。
2. **图片文件上传安全（MinIO）**：
   - **合规项**：
     - 文件大小由 Spring 与 Service 双重限定最大 5MB；
     - 后缀名强白名单限定（`.jpg`, `.jpeg`, `.png`, `.webp`）；
     - Content-Type 校验必须以 `image/` 开头；
     - 物理存储文件名采用 `UUID.randomUUID()`，杜绝目录穿越攻击（Directory Traversal `../`）。
   - **潜在缺陷（SEC-03）**：
     - 仅校验了 HTTP 传输头中的 Content-Type 与文件扩展名，未读取文件前几个字节的二进制魔数（Magic Bytes）。
     - **攻击场景**：攻击者将恶意 HTML/JS 脚本重命名为 `avatar.png`，并伪造 `Content-Type: image/png` 上传至 MinIO；若在同域名或未配置沙箱的 Web 端以附件形式打开，可能触发同源脚本执行。
     - **加固建议**：在 `FileServiceImpl` 中增加 Java 内置的 `ImageIO.read(inputStream)` 或引入 `tika-core` 校验真实文件魔数。

---

### 4. 管理员权限与审计安全

1. **双重角色权限收口**：
   - 路由层保护：`SecurityConfig.java` 中通过 `.requestMatchers("/admin/**", "/api/admin/**").hasRole("ADMIN")` 进行网关级前置拦截；
   - 控制器层保护：`AdminReportController.java` 类级别标注 `@PreAuthorize("hasRole('ADMIN')")`；
   - 经测试，普通合法用户即使伪造请求头访问任何管理接口，均严格返回 `403 Forbidden`。
2. **审计日志（Audit Log）不可篡改性**：
   - 每次下架商品、屏蔽评价、恢复评价、冻结用户，均强制插入 `campus_trade.admin_audit_log`；
   - 审计流水中的 `admin_id` 与 `admin_username` **直接取自服务端经过认证的 `SecurityContext`**，客户端无法伪造他人名义操作；
   - 留存客户端真实 IP（智能解析 `X-Forwarded-For`、`X-Real-IP`），日志链路闭环。
