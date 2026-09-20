# Stage 3.5-A 总结与交付报告：数据库迁移与 Redis 数据一致性加固

CampusTrade 校园二手交易平台已圆满完成 **Stage 3.5-A：数据库迁移与 Redis 数据一致性加固** 的全部代码审计、Flyway 自动化版本迁移引入、Redis 缓存恢复与并发一致性加固、全量自动化测试验证。

---

## 1. 审计发现 (Audit Findings)

1. **Flyway 数据库迁移状态**：
   - 审计前 `backend/pom.xml` 中**完全未引入 Flyway 依赖**（既无 `flyway-core` 也无 `flyway-database-postgresql`）。
   - `backend/src/main/resources` 下**完全不存在 `db/migration` 目录**，亦无任何 `V1/V2/V3` 脚本。
   - `application.yml` 中无任何 `spring.flyway` 配置。
   - 数据库中的 11 张表（Stage 1: `user`, `campus_school`, `student_verify`, `user_credit`; Stage 2: `category`, `goods`, `goods_image`, `goods_tag`; Stage 3: `favorite`, `browse_history`, `search_history`）全部由 `docker/postgres/init.sql` 及手工 `psql` 脚本创建。
   - **核心问题明确结论**：**Stage 3 新增的 favorite、browse_history、search_history 在审计前不存在正式 Flyway migration**。
2. **Schema 命名空间检查**：
   - 检查了所有 11 个实体类，`@TableName` 均采用表名（如 `@TableName("favorite")`、`@TableName("\"user\"")`），未带前缀。
   - 全局由 `mybatis-plus.global-config.db-config.schema: campus_trade` 统一前缀，不存在硬编码 `campus_trade.campus_trade.xxx` 错误。
3. **Favorite 数据一致性与 Redis 风险**：
   - **事实源倒置风险**：虽然业务设计上以 DB 为准，但原实现中 `addFavorite` 和 `removeFavorite` 在 Redis key 不存在时盲目执行 `increment` 或 `decrement`，若遇到 Redis 重启或 key 被驱逐，会脏写入 1 或 0，导致与 DB 真实数据严重脱节。
   - **并发重复收藏拦截处理**：并发请求穿透第一道 `selectCount` 检查时，DB `UNIQUE(user_id, goods_id)` 抛出 `DataIntegrityViolationException`，此前未转译为 400 业务异常，会导致向前端抛出 500 异常。
   - **缺少 Redis 宕机容错**：在 `@Transactional` 中若 Redis 抛异常会导致成功的 DB 写入被回滚，违反了“DB 为事实源”原则。

---

## 2. Flyway 当前状态 (Flyway Current Status)

- **版本规范**：引入与 Spring Boot 3.3.4 配套的 Flyway 10.x（`flyway-core` + `flyway-database-postgresql`）。
- **迁移历史配置**：
  - `spring.flyway.baseline-on-migrate: true`
  - `spring.flyway.baseline-version: 0`
  - `spring.flyway.schemas: campus_trade`
  - `spring.flyway.default-schema: campus_trade`
  - `spring.flyway.locations: classpath:db/migration`
- **迁移脚本**（全部包含 `CREATE TABLE IF NOT EXISTS` 与 `CREATE INDEX IF NOT EXISTS`，对已有库与全新库均支持平滑初始化/升级）：
  - `V1__init_user_and_auth_schema.sql`：Stage 1 用户与校园认证体系表 + 高校字典种子数据；
  - `V2__init_goods_and_category_schema.sql`：Stage 2 商品发布与分类浏览体系表 + 分类种子数据；
  - `V3__init_interaction_schema.sql`：Stage 3 收藏、浏览足迹与搜索历史表 + 唯一约束与索引。
- **验证结果**：
  在 PostgreSQL 容器中查询 `campus_trade.flyway_schema_history`：
  - Version 0 (Baseline): `SUCCESS = true`
  - Version 1 (`V1__init_user_and_auth_schema.sql`): `SUCCESS = true`
  - Version 2 (`V2__init_goods_and_category_schema.sql`): `SUCCESS = true`
  - Version 3 (`V3__init_interaction_schema.sql`): `SUCCESS = true`

---

## 3. 修改文件清单 (Modified / Created Files)

1. `backend/pom.xml`：新增 `flyway-core` 与 `flyway-database-postgresql` 依赖；
2. `backend/src/main/resources/application.yml`：增加 `spring.flyway` 配置；
3. `backend/src/main/resources/db/migration/V1__init_user_and_auth_schema.sql`：[NEW] Stage 1 迁移脚本；
4. `backend/src/main/resources/db/migration/V2__init_goods_and_category_schema.sql`：[NEW] Stage 2 迁移脚本；
5. `backend/src/main/resources/db/migration/V3__init_interaction_schema.sql`：[NEW] Stage 3 迁移脚本；
6. `docker/postgres/init.sql`：同步添加 `idx_favorite_user_time` 索引，确保与 V3 脚本 100% 一致；
7. `backend/src/main/java/com/campustrade/service/FavoriteService.java`：增加 `refreshFavoriteCount(Long goodsId)` 接口；
8. `backend/src/main/java/com/campustrade/service/impl/FavoriteServiceImpl.java`：
   - 改造 `addFavorite`：并发冲突捕获转译 400，Redis key 缺失时调用自愈刷新，Redis 异常降级防穿透；
   - 改造 `removeFavorite`：Redis 递减保护与缺失自愈；
   - 改造 `getFavoriteCount`：缓存丢失、脏数据、负数时强制从 DB 重新计算回填；
   - 实现 `refreshFavoriteCount`：原子回填 DB 真实计数值；
9. `backend/src/main/java/com/campustrade/exception/GlobalExceptionHandler.java`：增加 `DataIntegrityViolationException` 统一 400 兜底；
10. `backend/src/test/java/com/campustrade/CampusTradeStage35ATests.java`：[NEW] Stage 3.5-A 专属测试套件（9 项关键场景）。

---

## 4. Redis 一致性方案 (Redis Consistency Design)

- **核心原则**：PostgreSQL = Source of Truth；Redis = Cache。
- **写流程（先写 DB，后更 Cache）**：
  - 收藏：DB 成功插入后，检查 Redis Key 是否存在。若存在则执行 `increment`；若不存在则从 DB 重算真实计数回填 Redis。
  - 取消收藏：DB 成功删除后，检查 Redis Key 是否存在。若存在则执行 `decrement`（保底 `>= 0`）；若不存在则从 DB 重算真实计数回填 Redis。
- **故障隔离保护**：
  - 对 Redis 操作使用 `try-catch` 包裹。如果 Redis 发生网络超时或断连，仅记录 warn 日志，**绝不回滚已成功提交的数据库事务**。
  - 数据会在下一次查询或缓存自愈时被自动修正。

---

## 5. Redis 恢复方案 (Redis Recovery Mechanism)

在 `FavoriteServiceImpl` 中定义 `refreshFavoriteCount(Long goodsId)`：
1. **触发时机**：
   - `getFavoriteCount(goodsId)` 发现 Redis 中 key 不存在时；
   - Redis 中缓存值损坏（非数字类型或负数脏数据）时；
   - `addFavorite` / `removeFavorite` 执行时发现 Redis Key 不存在时；
   - Redis 服务重启或 `FLUSHALL` 发生后。
2. **恢复算法**：
   - 执行 `SELECT COUNT(*) FROM campus_trade.favorite WHERE goods_id = ?`；
   - 获取准确的非负计数值 `total`；
   - 执行 `redisTemplate.opsForValue().set(redisKey, total)` 原子回填缓存；
   - 返回 `total`，彻底根除“缓存丢失时返回 0 或覆盖数据库”的问题。

---

## 6. 并发处理方案 (Concurrency Safety)

1. **唯一约束保底**：
   - 数据库表 `campus_trade.favorite` 建立了物理级唯一索引 `uk_favorite_user_goods UNIQUE(user_id, goods_id)`；
   - 即使多线程并发穿透了应用层前置的 `selectCount` 校验，也必然只有一个线程能完成 DB `INSERT`；
   - 发生冲突的线程抛出 `DataIntegrityViolationException`，被捕获转译为语义明确的 `BusinessException(400, "您已收藏过该商品")`；
   - 冲突线程被拦截后不会进入 Redis 更新逻辑，彻底杜绝了 `DB = 1, Redis = 2` 的计数飘增现象。
2. **非负保底**：
   - `removeFavorite` 与 `getFavoriteCount` 增加下限截断保护与自愈校验，确保 Redis 中计数值永远 `>= 0`。

---

## 7. 新增测试 (Added Tests)

在 `CampusTradeStage35ATests` 中设计并验证了 9 项核心场景：
1. `test01_FlywayMigrationHistoryValidation`：验证 `flyway_schema_history` 中 V1、V2、V3 均已成功生效且无 schema 重复；
2. `test02_AddAndRemoveFavorite_DbAndRedisConsistency`：验证正常收藏与取消收藏后 DB 与 Redis 严格一致；
3. `test03_DuplicateFavorite_PreventedWithoutRedisDrift`：验证重复收藏被唯一索引拦截，返回 400，且 Redis 计数不飘增；
4. `test04_RedisKeyAbsent_GetFavoriteCountRecoversFromDb`：验证 Redis key 丢失时，`getFavoriteCount` 绝不返回 0，而是自动从 DB 恢复真实值；
5. `test05_RedisKeyAbsent_AddFavoriteRecoversFromDb`：验证 Redis key 缺失时新收藏不会脏写入 1，而是准确恢复为 DB 真实总数；
6. `test06_RedisKeyAbsent_RemoveFavoriteRecoversFromDb`：验证 Redis key 缺失时取消收藏不会脏写入 -1 或 0，而是同步为 DB 真实剩余数；
7. `test07_RedisCountCannotBeNegative_AndCorruptedDataRecovers`：验证人工注入负数或垃圾字符串时缓存自动自愈为有效非负值；
8. `test08_UserIsolation_UserBDoesNotAffectUserA`：验证用户 A 与用户 B 收藏状态完全隔离；
9. `test09_ConcurrentFavorite_SameUserSameGoods`：多线程高并发同时点击收藏，验证最终 DB 严格只有 1 条记录，返回 1 个 200 与 9 个 400，Redis 严格为 1。

---

## 8. `mvn test` 完整执行结果

```text
[INFO] Running com.campustrade.CampusTradeApplicationTests (3 tests)
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.campustrade.CampusTradeStage1Tests (10 tests)
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.campustrade.CampusTradeStage2Tests (11 tests)
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.campustrade.CampusTradeStage3Tests (12 tests)
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.campustrade.CampusTradeStage35ATests (9 tests)
=== Flyway Migrations Applied ===
Version: 0, Description: << Flyway Baseline >>, Success: true
Version: 1, Description: init user and auth schema, Success: true
Version: 2, Description: init goods and category schema, Success: true
Version: 3, Description: init interaction schema, Success: true
并发结果统计: 成功 200: 1, 业务拦截 400: 9, 其他异常: 0
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] Results:
[INFO] 
[INFO] Tests run: 45, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] Total time: 12.454 s
[INFO] ------------------------------------------------------------------------
```
**全量 45 个测试用例 100% 通过！**

---

## 9. 是否修改了既有行为 (Behavior Changes)

- **无负面既有行为破坏**：原有的 RESTful 接口路径、请求参数结构、返回结果 VO 完全保持不变；
- **积极行为加固**：
  - 当高并发下重复点击收藏时，从原先可能抛出非预期 500 异常升级为优雅返回 400 业务提示（“您已收藏过该商品”）；
  - 当 Redis 缓存丢失时，不再返回错误的 0，而是自动自愈回填真实计数值；
  - 启动阶段自动由 Flyway 负责数据库版本对齐与基线管理。

---

## 10. 剩余风险 (Remaining Risks)

1. **分布式极大并发下的极短时间窗竞争**：
   - 当前在单应用实例与中等并发下，DB 唯一约束 + 自愈机制能 100% 确保数据最终一致性；若未来扩展至数十个微服务节点且存在秒杀级收藏场景，可评估增加 Redis Lua 脚本或延迟双删保证强实时一致性（符合当前阶段不过度引入 MQ/Redisson 的规范要求）。
2. **PostgreSQL 容器首次部署**：
   - 首次部署新环境时，若未挂载 `init.sql`，Flyway 会自动从 V1 执行至 V3 完成建表；若挂载了 `init.sql`，由于启用了 `baseline-on-migrate: true`，Flyway 同样会自动以幂等方式执行，无兼容风险。

---

## 11. 最终结论 (Final Conclusion)

### **PASS (通过)**

本阶段严格遵守任务边界，无任何越权业务开发，全面消除了数据库迁移盲区与缓存数据漂移漏洞，全量测试全部绿灯。
根据指令，现已停止所有后续操作，等待您的下一步审阅指示！
