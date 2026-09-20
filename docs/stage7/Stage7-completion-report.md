# CampusTrade 校园二手交易平台 Stage 7 完成报告

> **阶段名称**：Stage 7：索引、检索、N+1 与前端体验优化
> **编制日期**：2026-09-20
> **对应提交**：`51ff667` `perf(阶段7): 索引、检索、N+1 与前端体验优化`
> **所属版本**：CampusTrade Backend v0.0.1-SNAPSHOT（Java 21 / Spring Boot 3.3.4 / PostgreSQL 16 / Redis 7）
> **状态**：已完成（COMPLETED）
> **自动化测试结果**：后端 `mvn -B test` **242/242 全绿**（本阶段新增 6 项）；前端 `flutter analyze` **0 issue**、
> `flutter test` **142/142 全绿**（本阶段新增 3 项）。
> **核心产出**：Flyway V11 索引治理（新增 2 条复合索引 / 删除 5 条实测零使用冗余索引 / `goods.view_count` 扩容为
> BIGINT）、关键词 `LIKE` 通配符转义、管理员工单列表 N+1 消除（242 → 5 条 SQL + 回归护栏）、
> 前端缩略图统一下采样、列表 loading 语义与首屏占位优化。

---

## 一、阶段目标

Stage 7 只做"性能与体验"，**不改变任何业务规则、不改变任何接口字段**：

1. 让商品列表这类"状态过滤 + 时间倒序"的高频查询真正走索引，而不是扫全表后过滤；
2. 把检索关键词里的 `%` `_` `\` 等通配符按字面量处理（原实现可被"关键词即通配符"命中全表或绕过预期筛选）；
3. 消除管理员工单列表的 N+1 查询，并把"SQL 条数"变成可回归的断言，而不是靠人工观察；
4. 前端统一按显示尺寸做图片下采样，去掉整屏 loading 与发布后不刷新等体验问题。

---

## 二、后端：索引治理（Flyway V11）

`backend/src/main/resources/db/migration/V11__performance_indexes.sql`，三件事：

| 动作 | 内容 | 依据 |
| :--- | :--- | :--- |
| 新增复合索引 | `goods(status, created_time DESC)`、`goods(status, category_id, created_time DESC)` | 200k 行实验库上 `EXPLAIN (ANALYZE, BUFFERS)` 实测：迁移前是 `Index Scan Backward using idx_goods_created_time` + `Rows Removed by Filter: 80000`，即为取 10 行在售商品要先翻过 8 万行非在售数据 |
| 删除冗余索引 | 5 条（列集合被唯一索引或更宽的复合索引完全承接） | `SELECT relname, indexrelname, idx_scan FROM pg_stat_user_indexes WHERE schemaname='campus_trade'` 二次确认 `idx_scan = 0`，不是凭直觉删 |
| 列扩容 | `goods.view_count` 由 `INT4` 改 `BIGINT` | 累计计数器在 `2^31` 处会溢出；用 `DO` 块判类型，语句可重复执行 |

迁移脚本本身**可重复执行**（全部 `IF EXISTS` / `IF NOT EXISTS`），并且不读写任何业务数据行。
脚本头部写明了 `CREATE INDEX` 会短暂持有 `SHARE` 锁的取舍与低峰执行建议（与 V1..V10 的写法一致）。

## 三、后端：关键词检索安全化

- `SearchKeywordUtils` 统一转义 `%`、`_`、`\` 并显式 `ESCAPE`，SQL 仍然保持参数绑定（不拼接字符串）；
- 实测行为变化：输入 `_` 由"命中全部 425 条"变为 **0 条**（字面量下划线本就不应匹配任意字符）；
  输入 `%` 时精确命中含 `%` 的 **41 条**；
- **评估后决定不引入 `pg_trgm`**，并给出依据：GIN 索引无法满足 `ORDER BY created_time DESC` 的排序需求，
  且 2 字中文词的 trigram 无法有效利用索引 —— 引依赖若解决不了真实瓶颈，只是增加维护面。

## 四、后端：N+1 消除与批量读写

| 项 | 迁移前 | 迁移后 |
| :--- | :--- | :--- |
| 管理员工单列表 | `pageSize=100` 时单请求 **242 条 SQL**（与 pageSize 线性相关） | **5 条 SQL**，且与 pageSize 解耦 |
| 回归护栏 | 无（靠人工观察） | `CampusTradeStage7PerfTests` 内 `assertTrue(statements <= 8)`，SQL 条数劣化会直接让测试失败（`SqlStatementCounter` 统计） |
| `listMyGoods` | 无分页，逐条读 Redis | 支持分页（默认行为不变），Redis 改批量读 |
| 商品图片 / 标签 | 逐条写入 | 批量写入 |

## 五、前端：缩略图、loading 语义与首屏

- 新增 `frontend/lib/widgets/goods_thumbnail.dart`：按"显示尺寸 × DPR"推导 `cacheWidth` / `cacheHeight`，
  加载中与加载失败使用同尺寸占位（避免布局跳动）；**9 处 `Image.network` 全部收敛到该组件**；
- 商品列表整屏 loading 改为 `isLoading && list.isEmpty`（有数据时刷新不再整屏闪烁）；
- 集市发布返回后自动刷新列表，发布结果即时可见；
- 分页 `size` 魔法值收敛到 `AppConfig`；
- `frontend/web/index.html` 增加品牌化首屏占位（跟随深色模式、首帧后淡出、15s 兜底），消除白屏。

## 六、验证与证据

- 后端：`mvn -B test` → **242 项全绿**（新增 6 项性能/检索测试）；
- 前端：`flutter analyze` → 0 issue；`flutter test` → **142 项全绿**（新增 3 项）；
- 索引收益：索引使用统计（`pg_stat_user_indexes`）与 `EXPLAIN (ANALYZE, BUFFERS)` 前后对比见提交说明与
  V11 迁移脚本内的注释（保留命令与关键输出，便于复现）；
- N+1 收益：由测试内的 SQL 计数断言固化，不依赖人工观察。

## 七、本阶段遗留（已转交后续处理）

| 遗留项 | 说明 | 后续状态 |
| :--- | :--- | :--- |
| `random_page_cost` 与 SSD 不匹配 | PostgreSQL 默认 4.0 按机械盘标定，SSD 云盘上低估索引扫描收益 | 已写入根 README「生产运维建议」 |
| 数据库备份机制缺失 | 命名卷不是备份，宿主机损坏/误删卷会一起丢 | 已写入根 README「生产运维建议」 |
| Redis 内存策略 | `noeviction` 的理由与告警要求 | 已写入根 README「生产运维建议」与 `docker-compose.prod.yml` 注释 |

> 说明：V11 之后 schema 继续演进（批次 1 新增 V12）。历史报告中的"迁移到 V11"描述的是本阶段的真实状态，
> 当前迁移清单以根 README「Schema 单一真相源」与 `backend/src/main/resources/db/migration/` 为准。
