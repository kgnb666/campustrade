# CampusTrade 校园二手交易平台 Stage 5-B 工程完成报告

> **阶段**：Stage 5-B（信用领域数据库迁移与模型落地实现）  
> **状态**：已完成 (COMPLETED)  
> **自动化测试结果**：后端 134/134 全量通过 (Failures: 0, Errors: 0)；前端 `flutter analyze` 0 issues。  
> **核心产出**：Flyway V5 数据库迁移脚本、`user_credit` 结构升级、`user_credit_log` 审计流水体系、信用实体与 Mapper、`CreditService` 领域服务、事务闭环与强幂等防重机制。

---

## 目录
- [一、修改与新增文件列表](#一修改与新增文件列表)
- [二、Flyway V5 迁移执行结果](#二flyway-v5-迁移执行结果)
- [三、数据库结构验证](#三数据库结构验证)
- [四、信用领域模型说明](#四信用领域模型说明)
- [五、积分事务流程与闭环机制](#五积分事务流程与闭环机制)
- [六、流水幂等与防重机制](#六流水幂等与防重机制)
- [七、自动化测试验证矩阵](#七自动化测试验证矩阵)
- [八、风险评估与防范说明](#八风险评估与防范说明)
- [九、Stage 5-C 准入条件判定](#九stage-5-c-准入条件判定)

---

## 一、修改与新增文件列表

| 序号 | 文件路径 | 类型 | 说明 |
| :--- | :--- | :--- | :--- |
| 1 | `backend/src/main/resources/db/migration/V5__upgrade_credit_domain.sql` | **新增** | Flyway V5 数据库迁移脚本：升级 `user_credit` 表、增加 CHECK 范围约束、创建 `user_credit_log` 流水表及唯一幂等索引 |
| 2 | `backend/src/main/java/com/campustrade/enums/CreditLevel.java` | **新增** | 信用等级枚举 (`EXCELLENT`, `GOOD`, `FAIR`, `POOR`)，提供分数自动映射方法 `fromScore` |
| 3 | `backend/src/main/java/com/campustrade/enums/CreditChangeType.java` | **新增** | 信用变更类型枚举 (`TRADE_COMPLETED`, `TRADE_CANCEL_PENALTY`, `REVIEW_GOOD`, `REVIEW_BAD`, `ADMIN_ADJUST`) |
| 4 | `backend/src/main/java/com/campustrade/entity/UserCredit.java` | **升级** | 扩展 `completedCount`, `cancelCount`, `creditLevel`, `updatedTime` 字段 |
| 5 | `backend/src/main/java/com/campustrade/entity/UserCreditLog.java` | **新增** | 信用变更审计流水持久化实体，映射 `user_credit_log` |
| 6 | `backend/src/main/java/com/campustrade/mapper/UserCreditLogMapper.java` | **新增** | 继承 MyBatis-Plus `BaseMapper<UserCreditLog>` |
| 7 | `backend/src/main/java/com/campustrade/service/CreditService.java` | **新增** | 信用领域服务接口 (`getOrCreateCredit`, `addCredit`, `deductCredit`) |
| 8 | `backend/src/main/java/com/campustrade/service/impl/CreditServiceImpl.java` | **新增** | 信用领域服务实现类：提供声明式事务保护、前置流水幂等拦截、`[0, 200]` 分值钳位及等级自动重算 |
| 9 | `backend/src/test/java/com/campustrade/CampusTradeStage5BTests.java` | **新增** | Stage 5-B 专项目动化测试套件（覆盖全部 10 项数据库与业务指标） |
| 10| `backend/src/test/java/com/campustrade/CampusTradeStage4B1Tests.java` | **调整** | 兼容性重构：断言逻辑由绝对等于 4 调整为精准匹配 V4 版本信息，确保迁移历史向前兼容 |
| 11| `docs/stage5/Stage5-B-credit-domain-report.md` | **新增** | 本工程实施与验收报告 |

---

## 二、Flyway V5 迁移执行结果

Spring Boot 启动与测试运行阶段，Flyway 成功自动检测并应用了 `V5__upgrade_credit_domain.sql`。

- **Flyway Info 审计日志**：
  ```
  2026-09-17 23:35:08.575 [main] INFO  org.flywaydb.core.FlywayExecutor - Database: jdbc:postgresql://127.0.0.1:15435/campustrade (PostgreSQL 16.15)
  2026-09-17 23:35:08.640 [main] INFO  o.f.core.internal.command.DbValidate - Successfully validated 6 migrations
  2026-09-17 23:35:08.664 [main] INFO  o.f.core.internal.command.DbMigrate - Current version of schema "campus_trade": 4
  2026-09-17 23:35:08.702 [main] INFO  o.f.core.internal.command.DbMigrate - Migrating schema "campus_trade" to version "5 - upgrade credit domain"
  2026-09-17 23:35:08.751 [main] INFO  o.f.core.internal.command.DbMigrate - Successfully applied 1 migration to schema "campus_trade", now at version v5
  ```
- **迁移版本确认**：当前最新数据库 Schema 版本为 **5** (`upgrade credit domain`)，状态为 `SUCCESS`。

---

## 三、数据库结构验证

### 1. `campus_trade.user_credit` 升级后完整字段
| 字段名 | 数据类型 | 约束 | 默认值 | 作用说明 |
| :--- | :--- | :--- | :--- | :--- |
| `id` | BIGINT | PRIMARY KEY | - | 档案唯一ID |
| `user_id` | BIGINT | UNIQUE NOT NULL | - | 关联用户唯一标识 |
| `credit_score` | INT | NOT NULL, CHECK(0~200) | 100 | 综合信用积分（基准100分） |
| `trade_count` | INT | NOT NULL | 0 | 参与交易总次数 |
| `good_review_count` | INT | NOT NULL | 0 | 好评次数 |
| `bad_review_count` | INT | NOT NULL | 0 | 差评次数 |
| `completed_count` | BIGINT | NOT NULL | 0 | **[V5新增]** 实际顺利履约完成笔数 |
| `cancel_count` | BIGINT | NOT NULL | 0 | **[V5新增]** 违约取消或主动取消笔数 |
| `credit_level` | VARCHAR(20) | NOT NULL | 'GOOD' | **[V5新增]** 评级 (EXCELLENT, GOOD, FAIR, POOR) |
| `created_time` | TIMESTAMP | NOT NULL | CURRENT_TIMESTAMP | 初始创建时间 |
| `updated_time` | TIMESTAMP | - | CURRENT_TIMESTAMP | **[V5新增]** 最近一次分值或状态更新时间 |

- **物理约束验证**：`chk_user_credit_score_range` CHECK 约束生效，数据库底层确保 `credit_score >= 0 AND credit_score <= 200`。

### 2. `campus_trade.user_credit_log` 结构与索引
| 字段名 | 数据类型 | 约束 | 作用说明 |
| :--- | :--- | :--- | :--- |
| `id` | BIGSERIAL | PRIMARY KEY | 流水主键自增 ID |
| `user_id` | BIGINT | NOT NULL | 发生积分变动的用户 ID |
| `change_type` | VARCHAR(50) | NOT NULL | 变动类型 (TRADE_COMPLETED 等) |
| `change_score` | INTEGER | NOT NULL | 变动分值（正整数或负整数） |
| `before_score` | INTEGER | NOT NULL | 变动前积分快照 |
| `after_score` | INTEGER | NOT NULL | 变动后积分快照 |
| `related_type` | VARCHAR(50) | - | 关联业务实体类型（如 ORDER, REVIEW） |
| `related_id` | BIGINT | - | 关联业务主键 ID |
| `reason` | VARCHAR(500) | - | 变动原因说明 |
| `created_time` | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 流水记录产生时间 |

- **物理索引**：
  - `uk_credit_log_idempotent` UNIQUE `(user_id, related_type, related_id, change_type)`：强幂等索引；
  - `idx_credit_log_user_time` INDEX `(user_id, created_time DESC)`：用户流水逆序分页查询索引；
  - `idx_credit_log_related` INDEX `(related_type, related_id)`：业务实体关联溯源索引。

---

## 四、信用领域模型说明

### 1. 评级规则（CreditLevel）
严格遵循白盒可解释原则：
- `130 ~ 200` 分：`EXCELLENT`（信用极好）
- `100 ~ 129` 分：`GOOD`（信用良好，默认基准）
- `80 ~ 99` 分：`FAIR`（信用中等）
- `0 ~ 79` 分：`POOR`（信用较低）

### 2. 变更类型（CreditChangeType）
- `TRADE_COMPLETED`：订单完成加分（鼓励履约）
- `TRADE_CANCEL_PENALTY`：待面交违约取消惩罚扣分
- `REVIEW_GOOD`：好评加分（预留 Stage 5-D 接入）
- `REVIEW_BAD`：差评扣分（预留 Stage 5-D 接入）
- `ADMIN_ADJUST`：管理员人工调账（预留争议处理接入）

---

## 五、积分事务流程与闭环机制

所有对用户信用积分与指标的写操作均封装在 `CreditServiceImpl` 中，强制处于 `@Transactional(rollbackFor = Exception.class)` 控制下：

```
[调用端请求: addCredit / deductCredit]
                 │
                 ▼
      [1. 前置参数与非空校验]
                 │
                 ▼
[2. 幂等校验: 查询 user_credit_log (user_id, related_type, related_id, change_type)]
          ├─────────────── 已存在 ───────────────► [直接返回当前信用，不重复变更]
          │
      不存在流水
          │
          ▼
[3. getOrCreateCredit(userId) 并获取 beforeScore]
                 │
                 ▼
[4. 钳位运算: afterScore = min(200, max(0, beforeScore ± score))]
                 │
                 ▼
[5. 等级联动: newLevel = CreditLevel.fromScore(afterScore)]
                 │
                 ▼
[6. 更新 user_credit (积分、等级、completedCount / cancelCount、updatedTime)]
                 │
                 ▼
[7. 插入 user_credit_log 审计流水 (变动分值、前后快照、关联ID、原因)]
                 │
                 ▼
         [8. 提交事务，返回 UserCredit]
```

- **原子性保证**：测试用例 `test10_transactional_rollback` 验证了在模拟异常抛出时，`user_credit` 更新与 `user_credit_log` 插入同时完整回滚，无残留脏数据。

---

## 六、流水幂等与防重机制

为应对网络重试、并发点击、异步消息重复消费等典型分布式/网络问题，设计了**应用层 + 数据库层**双重幂等机制：
1. **应用层拦截**：  
   `CreditServiceImpl` 在执行更新前，先按唯一维度 `(userId, relatedType, relatedId, changeType)` 查询流水表。若命中已处理记录，直接输出 Warn 日志并原样返回用户信用档案，避免执行不必要的更新 SQL。
2. **数据库层终极防线**：  
   数据库上的唯一索引 `uk_credit_log_idempotent` 确保即使在毫秒级极限并发下两笔相同请求同时穿透应用层，第二笔请求在 `insert` 时也必将被 PostgreSQL 唯一索引冲突捕获并回滚，绝对杜绝重复加分/扣分。

---

## 七、自动化测试验证矩阵

执行专项目动化测试：
```powershell
$env:JAVA_HOME = "D:\yp3\.tools\jdk21"
& "D:\yp3\.tools\maven\bin\mvn.cmd" test -Dtest=CampusTradeStage5BTests
```

### 测试结果（10/10 全部通过，耗时 0.154s）
| 测试方法 | 验证目标 | 预期结果 | 实测状态 |
| :--- | :--- | :--- | :--- |
| `test01_flyway_v5_applied_successfully` | Flyway V5 迁移执行与版本号 | Version=5, State=SUCCESS | ✅ PASS |
| `test02_user_credit_expanded_columns_exist` | `user_credit` 新增 4 个字段与 CHECK 约束 | 字段存在，CHECK 存在 | ✅ PASS |
| `test03_user_credit_log_table_and_indexes_exist` | `user_credit_log` 10 个字段与幂等唯一索引 | 结构完整，唯一索引生效 | ✅ PASS |
| `test04_first_get_or_create_credit` | 首次获取初始化信用 | score=100, level=GOOD, 计数=0 | ✅ PASS |
| `test05_add_credit_generates_log` | 增加积分与流水记录 | 100+2=102, 生成 TRADE_COMPLETED 流水 | ✅ PASS |
| `test06_deduct_credit_changes_level_to_fair` | 扣减积分与等级下调 | 102-5=97, 等级自动变为 FAIR | ✅ PASS |
| `test07_duplicate_event_is_idempotent` | 幂等请求防重 | 再次调用加分，分值保持 97 不变 | ✅ PASS |
| `test08_credit_score_upper_bound` | 积分上限钳位 | 198+10 -> 200 (EXCELLENT) | ✅ PASS |
| `test09_credit_score_lower_bound` | 积分下限钳位 | 2-10 -> 0 (POOR) | ✅ PASS |
| `test10_transactional_rollback` | 异常场景事务原子回滚 | 积分保持 100，流水未入库 | ✅ PASS |

### 全量测试与前端验证
- **全量测试套件**：`mvn test` 执行，**`Tests run: 134, Failures: 0, Errors: 0, Skipped: 0`**（涵盖 Stage 0 ~ Stage 5-B 全部历史测试）。
- **前端静态分析**：`flutter analyze` 执行，**`No issues found!`**。

---

## 八、风险评估与防范说明

1. **存量数据安全性**：  
   `V5__upgrade_credit_domain.sql` 中新增的 4 个字段均配置了 `DEFAULT` 默认值，执行为向后兼容的非阻塞 ALTER 操作，现存用户数据不受任何损害。
2. **已有业务零侵入**：  
   本阶段严格遵守阶段边界，**未修改** `OrderService`、`GoodsService` 及用户认证体系核心代码，已有的 `completeOrder` 原有逻辑保持原样运行，未发生破坏。
3. **分值溢出风险**：  
   在 Java 业务层（Math.min/max）与 PostgreSQL 数据库层（CHECK 约束）实施了双层边界防线，杜绝任何超出 `0 ~ 200` 范围的异常数值。

---

## 九、Stage 5-C 准入条件判定

| 准入验收准则 | 检查项 | 判定结果 |
| :--- | :--- | :--- |
| **1. 数据库版本就绪** | Flyway V5 迁移成功应用，版本跃迁为 5 | ✅ 达成 |
| **2. 核心数据表与索引完备** | `user_credit` 扩充 4 字段，`user_credit_log` 表与 3 个索引就绪 | ✅ 达成 |
| **3. 领域模型与实体映射** | `UserCredit`, `UserCreditLog`, `CreditLevel`, `CreditChangeType` 就绪 | ✅ 达成 |
| **4. 领域服务与事务闭环** | `CreditService` 封装完成，支持原子更新与等级重算 | ✅ 达成 |
| **5. 幂等与防重机制** | 业务与底层唯一索引双重幂等机制验证通过 | ✅ 达成 |
| **6. 自动化回归测试** | 134 项单元测试 100% 通过，前端 0 警告 | ✅ 达成 |

### 最终结论
各项核心基础设施与验收指标已全面满足，**正式判定：READY FOR Stage 5-C（评价系统与信用联动）**。
