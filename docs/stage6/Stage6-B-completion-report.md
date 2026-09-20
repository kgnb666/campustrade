# CampusTrade 校园二手交易平台 Stage 6-B 工程完成报告

> **阶段名称**：Stage 6-B：举报系统与管理员治理后端实现  
> **编制日期**：2026-09-18  
> **所属版本**：CampusTrade Backend v0.0.1-SNAPSHOT (Java 21, Spring Boot 3.3.4, PostgreSQL 15, Redis 7)  
> **状态**：已完成 (COMPLETED)  
> **自动化测试结果**：全量回归 **165/165 单元/集成测试全部通过** (Failures: 0, Errors: 0, 100% PASS)；Stage 6-B 专项 **15/15 场景全绿**。  
> **核心产出**：Flyway V7 数据库迁移、统一举报工单实体与 Mapper、管理员治理与操作审计体系、多态目标存在性校验与防自举报防御、物理部分唯一索引防重复工单、Redis 每日限频与 Fail-Open 容灾降级、违规评价屏蔽与信用精准冲正（Credit Reversal）、Spring Security `ROLE_ADMIN` 接口与方法双重权限隔离、`ReportController` 与 `AdminReportController` RESTful 控制器。

---

## 一、新增与修改文件清单

| 序号 | 文件路径 | 模块 / 层级 | 变更类型 | 说明 |
| :--- | :--- | :--- | :--- | :--- |
| 1 | `backend/src/main/resources/db/migration/V7__create_governance_domain.sql` | 数据库迁移 | **新增** | Flyway V7 脚本：创建 `report` 表、`admin_audit_log` 表及物理索引 |
| 2 | `docker/postgres/init.sql` | 容器初始化 | **修改** | 同步追加 Stage 6-B 治理领域表与索引定义 |
| 3 | `backend/src/main/java/com/campustrade/enums/ReportTargetType.java` | 枚举层 | **新增** | 举报目标实体类型枚举 (`GOODS`, `REVIEW`, `USER`) |
| 4 | `backend/src/main/java/com/campustrade/enums/ReportReasonType.java` | 枚举层 | **新增** | 举报原因类型枚举 (`FRAUD`, `COUNTERFEIT`, `ILLEGAL_PROHIBITED`, `HARASSMENT`, `MALICIOUS_REVIEW`, `OTHER`) |
| 5 | `backend/src/main/java/com/campustrade/enums/ReportStatus.java` | 枚举层 | **新增** | 举报工单流转状态枚举 (`PENDING`, `HANDLED_VALID`, `HANDLED_INVALID`) |
| 6 | `backend/src/main/java/com/campustrade/enums/AdminOperationType.java` | 枚举层 | **新增** | 管理员治理操作类型枚举 (`OFF_SHELF_GOODS`, `SHIELD_REVIEW`, `FREEZE_USER`, `PASS_REPORT`, `REJECT_REPORT`) |
| 7 | `backend/src/main/java/com/campustrade/entity/Report.java` | 实体层 | **新增** | 举报工单持久化实体，映射 `campus_trade.report` 数据表 |
| 8 | `backend/src/main/java/com/campustrade/entity/AdminAuditLog.java` | 实体层 | **新增** | 管理员操作审计流水持久化实体，映射 `campus_trade.admin_audit_log` 数据表 |
| 9 | `backend/src/main/java/com/campustrade/mapper/ReportMapper.java` | 数据访问层 | **新增** | 继承 MyBatis-Plus `BaseMapper<Report>` |
| 10 | `backend/src/main/java/com/campustrade/mapper/AdminAuditLogMapper.java` | 数据访问层 | **新增** | 继承 MyBatis-Plus `BaseMapper<AdminAuditLog>` |
| 11 | `backend/src/main/java/com/campustrade/dto/report/CreateReportRequest.java` | 传输层 DTO | **新增** | 用户提交举报请求入参 DTO（带 Jakarta 参数校验） |
| 12 | `backend/src/main/java/com/campustrade/dto/report/HandleReportRequest.java` | 传输层 DTO | **新增** | 管理员处理举报工单请求入参 DTO |
| 13 | `backend/src/main/java/com/campustrade/dto/report/ReportQueryRequest.java` | 传输层 DTO | **新增** | 管理员多维度分页筛选工单入参 DTO |
| 14 | `backend/src/main/java/com/campustrade/vo/report/ReportVO.java` | 视图层 VO | **新增** | 用户端举报工单视图展示对象 |
| 15 | `backend/src/main/java/com/campustrade/vo/report/AdminReportDetailVO.java` | 视图层 VO | **新增** | 管理员端举报工单详情视图（含举报人信息与目标对象业务快照） |
| 16 | `backend/src/main/java/com/campustrade/vo/report/AdminAuditLogVO.java` | 视图层 VO | **新增** | 管理员操作审计流水视图展示对象 |
| 17 | `backend/src/main/java/com/campustrade/service/ReportService.java` | 领域服务层 | **新增** | 举报工单业务接口定义 |
| 18 | `backend/src/main/java/com/campustrade/service/impl/ReportServiceImpl.java` | 领域服务层 | **新增** | 举报业务实现类（多态校验、自举报拦截、Redis 每日 10 次限频与 fail-open 容灾、部分唯一索引防重） |
| 19 | `backend/src/main/java/com/campustrade/service/AdminGovernanceService.java` | 领域服务层 | **新增** | 管理员治理与工单处理业务接口定义 |
| 20 | `backend/src/main/java/com/campustrade/service/impl/AdminGovernanceServiceImpl.java` | 领域服务层 | **新增** | 管理员治理业务实现类（状态机单向流转、下架/屏蔽/冻结动作、评价违规屏蔽信用精准冲正、强制记录审计流水） |
| 21 | `backend/src/main/java/com/campustrade/controller/ReportController.java` | API 控制层 | **新增** | 用户端举报 REST 控制器（`POST /api/reports`, `GET /api/reports/my`） |
| 22 | `backend/src/main/java/com/campustrade/controller/AdminReportController.java` | API 控制层 | **新增** | 管理员治理 REST 控制器（`GET /api/admin/reports`, `GET /api/admin/reports/{id}`, `PUT /api/admin/reports/{id}/handle`, `GET /api/admin/audit-logs`） |
| 23 | `backend/src/main/java/com/campustrade/security/SecurityConfig.java` | 安全配置 | **修改** | 路由级别收口保护：配置 `.requestMatchers("/admin/**", "/api/admin/**").hasRole("ADMIN")` |
| 24 | `backend/src/test/java/com/campustrade/CampusTradeStage6BTests.java` | 自动化测试 | **新增** | Stage 6-B 专项 15 项全链路测试套件 |
| 25 | `backend/src/test/java/com/campustrade/CampusTradeStage5DTests.java` | 自动化测试 | **调整** | 兼容性调整：Flyway 历史迁移校验改为过滤 V6 状态成功，向前兼容后续版本 |
| 26 | `docs/stage6/Stage6-B-completion-report.md` | 文档报告 | **新增** | 本实施与验收报告 |

---

## 二、数据库迁移结果与约束保障

Flyway 自动执行了 `V7__create_governance_domain.sql`，迁移状态保持 100% 成功：

1. **统一举报工单表 (`campus_trade.report`)**：
   - 字段：`id`, `reporter_id`, `target_type`, `target_id`, `reason_type`, `description`, `evidence_images`, `status`, `handled_by`, `handled_time`, `handle_result`, `created_time`, `updated_time`；
   - CHECK 约束：`chk_report_target_type` 强限制 `target_type IN ('GOODS', 'REVIEW', 'USER')`；
   - CHECK 约束：`chk_report_status` 强限制 `status IN ('PENDING', 'HANDLED_VALID', 'HANDLED_INVALID')`；
2. **物理防刷部分唯一索引 (`uk_report_active`)**：
   ```sql
   CREATE UNIQUE INDEX IF NOT EXISTS uk_report_active 
       ON campus_trade.report (reporter_id, target_type, target_id) 
       WHERE status = 'PENDING';
   ```
   - **效果**：同一用户针对同一实体在处理中状态下只能存在一条有效工单，并发提交由 PostgreSQL 物理拦截并抛出 `DuplicateKeyException`，业务转换为 `409 Conflict`。
3. **管理员操作审计流水表 (`campus_trade.admin_audit_log`)**：
   - 字段：`id`, `admin_id`, `admin_username`, `operation_type`, `target_type`, `target_id`, `before_status`, `after_status`, `reason`, `ipAddress`, `created_time`；
   - 包含 `idx_admin_audit_time`, `idx_admin_audit_target`, `idx_admin_audit_admin` 索引，确保后台治理检索高效。

---

## 三、核心领域与安全架构实现细节

### 1. 多态目标校验与自举报拦截
- **商品举报 (`GOODS`)**：校验商品存在（不存在返 404）；校验 `goods.sellerId != reporterId`（卖家不能举报自己商品，违者返 400）。
- **评价举报 (`REVIEW`)**：校验评价存在（不存在返 404）；校验 `review.reviewerId != reporterId`（评价人不能举报自己评价，违者返 400）。
- **用户举报 (`USER`)**：校验用户存在（不存在返 404）；校验 `targetId != reporterId`（用户不能举报自己，违者返 400）。

### 2. Redis 每日频控限流与 Fail-Open 容灾
- 限流键：`report:daily:limit:{reporterId}:{yyyyMMdd}`，TTL 设置为 24 小时；
- 限流阈值：自然日累计上限 **10 次**；超出立即返回 `429 Too Many Requests`；
- **Fail-Open 容灾机制**：对 Redis 操作用 try-catch 包裹，若 Redis 发生网络超时或故障，记录 WARN 日志并降级放行，保证用户安全举报渠道不因缓存故障被阻断。

### 3. 违规评价屏蔽与信用精准冲正（Credit Reversal）
当管理员采纳评价举报判定为违规，将评价置为 `AUDIT_REJECTED` 时，联动调用已有的 `CreditService` 进行冲正操作，杜绝直接 UPDATE 数据库，保证 `user_credit_log` 审计闭环：
- **原 5 星评价 (+3 分)**：调用 `creditService.deductCredit(reviewedUserId, 3, ADMIN_ADJUST, "REVIEW", reviewId, "违规好评被管理员屏蔽，追缴信用分")`；
- **原 4 星评价 (+1 分)**：调用 `creditService.deductCredit(reviewedUserId, 1, ADMIN_ADJUST, "REVIEW", reviewId, "违规好评被管理员屏蔽，追缴信用分")`；
- **原 2 星评价 (-2 分)**：调用 `creditService.addCredit(reviewedUserId, 2, ADMIN_ADJUST, "REVIEW", reviewId, "违规差评被管理员屏蔽，恢复信用分")`；
- **原 1 星评价 (-5 分)**：调用 `creditService.addCredit(reviewedUserId, 5, ADMIN_ADJUST, "REVIEW", reviewId, "违规差评被管理员屏蔽，恢复信用分")`；
- **原 3 星评价 (0 分)**：无需冲正。

### 4. 权限与审计不可伪造
- 管理员接口全线受 Spring Security `ROLE_ADMIN` 双重保护（`SecurityConfig` requestMatchers + Controller `@PreAuthorize("hasRole('ADMIN')")`）；
- 普通用户尝试调用任何 `/api/admin/**` 均返回 `403 Forbidden`；
- 审计流水中的 `adminId` 与 `adminUsername` 均从服务端当前已认证的 `SecurityContext` 中安全提取，客户端传入一律无效；
- 客户端 IP 地址智能解析 `X-Forwarded-For`、`X-Real-IP` 与 `RemoteAddr`，留存真实溯源证据。

---

## 四、自动化测试套件与回归验证

### 1. Stage 6-B 专项测试矩阵 (`CampusTradeStage6BTests`)

| 测试方法 | 覆盖业务场景 | 预期断言 | 测试结果 |
| :--- | :--- | :--- | :---: |
| `test01_flyway_v7_applied_successfully` | Flyway V7 应用与表及索引存在性 | V7 SUCCESS, 表与 `uk_report_active` 索引存在 | **PASS** |
| `test02_unauthorized_access_reports` | 未登录调用举报接口 | HTTP 401 Unauthorized | **PASS** |
| `test03_regular_user_forbidden_admin_apis` | 普通用户越权访问管理员接口 | HTTP 403 Forbidden | **PASS** |
| `test04_admin_authorized_access` | 管理员正常访问后台列表 | HTTP 200 OK, 返回工单数组 | **PASS** |
| `test05_report_non_existent_target_returns_404` | 举报不存在的实体 | HTTP 404 Not Found | **PASS** |
| `test06_self_report_defense` | 自举报防范 (商品/评价/用户) | HTTP 400 Bad Request | **PASS** |
| `test07_submit_report_success` | 正常提交举报工单 | HTTP 200, 初始状态 PENDING | **PASS** |
| `test08_duplicate_pending_report_conflict` | 针对同一目标重复提交处理中工单 | HTTP 409 Conflict | **PASS** |
| `test09_daily_rate_limit_exceeded` | 单日超过 10 次限额提交 | HTTP 429 Too Many Requests | **PASS** |
| `test10_admin_reject_invalid_report` | 管理员驳回工单 (INVALID) | 状态 HANDLED_INVALID, 记录 REJECT_REPORT 审计 | **PASS** |
| `test11_admin_accept_goods_report_and_off_shelf` | 管理员采纳商品举报 (VALID) | 状态 HANDLED_VALID, 商品变为 OFF_SHELF, 审计留痕 | **PASS** |
| `test12_shield_good_review_and_reverse_credit` | 违规 5 星好评屏蔽与追缴 | 评价 AUDIT_REJECTED, 信用分 -3, 生成 ADMIN_ADJUST 流水 | **PASS** |
| `test13_shield_bad_review_and_restore_credit` | 违规 1 星差评屏蔽与恢复 | 评价 AUDIT_REJECTED, 信用分 +5, 生成 ADMIN_ADJUST 流水 | **PASS** |
| `test14_cannot_handle_already_processed_report` | 状态机防重处理已终态工单 | HTTP 400 "工单已被处理，不可重复处理" | **PASS** |
| `test15_query_admin_audit_logs` | 管理员查询操作审计流水列表 | HTTP 200, 分页数据及操作描述正确 | **PASS** |

### 2. 全量回归测试结果
```text
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running com.campustrade.CampusTradeApplicationTests
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.campustrade.CampusTradeStage1Tests
[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.campustrade.CampusTradeStage2Tests
[INFO] Tests run: 25, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.campustrade.CampusTradeStage3Tests
[INFO] Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.campustrade.CampusTradeStage35ATests
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.campustrade.CampusTradeStage35BTests
[INFO] Tests run: 21, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.campustrade.CampusTradeStage35CTests
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.campustrade.CampusTradeStage35ETests
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.campustrade.CampusTradeStage4B1Tests
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.campustrade.CampusTradeStage4B2Tests
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.campustrade.CampusTradeStage5BTests
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.campustrade.CampusTradeStage5DTests
[INFO] Tests run: 16, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.campustrade.CampusTradeStage6BTests
[INFO] Tests run: 15, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] Results:
[INFO] 
[INFO] Tests run: 165, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] Total time: 17.574 s
[INFO] ------------------------------------------------------------------------
```

---

## 五、严格工程红线执行核查

- [x] **纯后端实现**：严格未改动任何 Flutter 页面或前端组件；
- [x] **严禁实现 `review_like`**：未实现评价点赞（延至 Stage 6-C）；
- [x] **严禁实现 WebSocket 即时通讯**：坚决 Push Back 维持轻量面交留言机制；
- [x] **严禁实现线上支付**：坚决 Push Back 规避金融二清与监管风险；
- [x] **严禁普通用户改删评价**：坚守评价一次性不可逆原则，仅由管理员通过治理审计冲正；
- [x] **历史 Flyway 迁移脚本未改动**：V1~V6 原封不动，仅新增 V7；
- [x] **无直接 UPDATE 信用表行为**：所有信用变更均通过 `CreditService` 事务与流水闭环；
- [x] **统一 Result 包装与实体解耦**：Entity 零泄露，全线采用 VO / DTO。

---

## 六、最终判定与 Gate 裁决

$$\mathbf{Stage\ 6\text{-}B\ Gate\ Decision:\ PASS}$$

> **总结**：**Stage 6-B：举报系统与管理员治理后端实现全部落地，165/165 测试 100% 通过。系统已具备生产级举报、治理下架、评价屏蔽、信用冲正与审计追溯能力，正式准入下一阶段！**
