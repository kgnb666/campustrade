# Stage 3.5-E 总结与交付报告：搜索与 Redis 工程细节优化

CampusTrade 校园二手交易平台已圆满完成 **Stage 3.5-E：搜索与 Redis 工程细节优化**。本阶段严格聚焦于已有能力（热搜、搜索历史、Redis Key 体系、收藏计数、浏览量计数）的工程鲁棒性治理，坚决未引入订单、支付、MQ、Elasticsearch、微服务或集群等非当前阶段复杂度。

---

## 1. 搜索词规范化方案 (Search Keyword Normalization)

针对用户搜索关键词进入 `campus_trade.search_history` 与 Redis `search:hot` 之前的清洗与标准化，设计并落地了专用的 [`SearchKeywordUtils`](file:///d:/wkk/Second-hand%20trading%20platform/backend/src/main/java/com/campustrade/common/util/SearchKeywordUtils.java) 工具类：

### 1.1 标准化清洗处理流水线
1. **空引用防御**：对 `null` 直接返回 `null`；
2. **多重连续空白折叠**：
   - 匹配字符集：标准空白（`\s` 含空格、`\t`、`\n`、`\r`、`\f`）、中文全角空格（`\u3000`）、不间断空格（`\u00A0`）、零宽空格（`\u200B`）以及零宽无间断空格（`\uFEFF`）；
   - 正则折叠：`rawKeyword.replaceAll("[\\s\\u3000\\u00A0\\u200B\\uFEFF]+", " ").trim()`；
   - 效果：将诸如 `"   iPad     Pro    2024   "` 规范化折叠为 `"iPad Pro 2024"`，消除脏字符膨胀；
3. **空串过滤拦截**：清洗后长度为 0 的纯空白输入直接返回 `null`，不进入任何后续存储；
4. **长度安全收敛**：严格对齐 PostgreSQL 数据库中 `VARCHAR(100)` 的物理约束，对超过 100 字符的输入安全截断为 100 字符并再次 `trim()`，根除底层 SQL 溢出报错；
5. **大小写与语义保留策略**：
   - **中文语义**：中文无大小写概念，空白折叠后保持完整纯净；
   - **英文与品牌词**：针对 `iPhone 15`、`MacBook Air`、`iPad` 等英文与中英混排关键词，**保留原词展示大小写**。
   - **设计考量**：在校园二手场景中，热搜榜单需直接向前端 Chip 标签展示。若强转为全小写（如 `iphone`、`macbook`），会导致品牌视觉体验严重降级；同时避免混淆特定中英缩写。

---

## 2. 热搜垃圾数据防御 (Hot Search Junk Data Prevention)

### 2.1 拦截机制
- 在 `GoodsServiceImpl.searchGoods`、`pageGoods` 及 `SearchHistoryServiceImpl.recordSearch` 入口处统一调用 `SearchKeywordUtils.normalize(keyword)`：
  ```java
  String normalized = SearchKeywordUtils.normalize(keyword);
  if (normalized == null) {
      return; // 快速短路
  }
  ```
- **验证断言**：
  - `keyword = ""`、`keyword = "   "`、`"\t\n  \r "`、`"\u3000\u200B"` 等无意义空输入；
  - 既不会在 PostgreSQL `search_history` 表中插入空行；
  - 也绝对不会在 Redis `search:hot` ZSet 中执行 `ZINCRBY` 创建空 member。

---

## 3. Redis Key 审计与集中规范化 (Redis Key Audit & Standards)

### 3.1 审计前现状
此前项目中各个服务分散定义私有 Key 前缀，甚至在测试类中硬编码字符串，存在散落 Magic String 与隐式重复定义风险：
- `FavoriteServiceImpl`: `private static final String FAVORITE_KEY_PREFIX = "goods:favorite:";`
- `GoodsServiceImpl`: `private static final String VIEW_KEY_PREFIX = "goods:view:";`
- `SearchHistoryServiceImpl`: `private static final String HOT_SEARCH_KEY = "search:hot";`
- `JwtAuthenticationFilter`: `public static final String BLACKLIST_PREFIX = "jwt:blacklist:";`
- `AuthServiceImpl`: `public static final String REFRESH_TOKEN_PREFIX = "jwt:refresh:";`
- `StudentVerifyServiceImpl`: `public static final String VERIFY_CODE_PREFIX = "student:verify:";`

### 3.2 治理与集中规范化 ([`RedisKeyConstants.java`](file:///d:/wkk/Second-hand%20trading%20platform/backend/src/main/java/com/campustrade/common/constant/RedisKeyConstants.java))
建立全系统统一的 Redis Key 命名与静态构造规范，遵循 `业务模块:实体类型[:标识符]` 命名分层：

| 常量名称 | Redis 数据类型 | Key 结构规范 | 业务说明与 TTL 策略 |
| :--- | :---: | :--- | :--- |
| `GOODS_FAVORITE_PREFIX` | String | `goods:favorite:{goodsId}` | 商品收藏总数（非负整数），永久或自愈更新 |
| `GOODS_VIEW_PREFIX` | String | `goods:view:{goodsId}` | 商品浏览量未持久化增量，通过定时/主动同步扣减 |
| `SEARCH_HOT` | Sorted Set | `search:hot` | 全站热门搜索词排行榜，Score 为热度权重 |
| `JWT_BLACKLIST_PREFIX` | String | `jwt:blacklist:{token}` | 用户登出黑名单，TTL 为 Token 剩余有效期 |
| `JWT_REFRESH_PREFIX` | String | `jwt:refresh:{userId}` | 刷新凭证存储，TTL 为 7 天 |
| `STUDENT_VERIFY_PREFIX` | String | `student:verify:{phone}` | 学生认证验证码，TTL 为 5 分钟 |

提供标准构造方法：`goodsFavoriteKey(id)`、`goodsViewKey(id)`、`searchHotKey()`、`jwtBlacklistKey(token)`、`studentVerifyKey(phone)`、`jwtRefreshKey(userId)`，服务层全面收敛复用，消灭散落字符串。

---

## 4. Redis 生命周期分析与扩展演进 (Redis Lifecycle Analysis)

### 4.1 `search:hot` 长期增长问题评估
- **内存消耗与长尾垃圾**：Redis ZSet 每增加一个成员需额外维护 Dict 与 Skiplist 节点开销。若无上限增长，大量一次性、冷僻长尾词会沉淀在内存中。
- **时效性失真**：若只有单纯累加，半年前甚至一年前的高频词（如“2023考研英语”）将永远霸占热搜榜首，阻碍当季最新流行词上升。

### 4.2 本阶段落地的轻量控制
遵循“不要为了高级而增加复杂定时任务”的原则，避免引入 Spring Task/Quartz/MQ/衰减衰半衰期计算，采用**容量安全水位软修剪**策略：
- 在 `SearchHistoryServiceImpl.recordSearch` 累加热度时，检查 ZSet 元素总数；
- 当总数超出安全阈值（`MAX_HOT_SEARCH_MEMBERS = 1000`）时，通过 `removeRange(hotKey, 0, removeCount - 1)` 一次性修剪掉得分最低的末尾长尾成员；
- 兼具极低复杂度与强保底效果，杜绝内存无限膨胀。

### 4.3 未来演进扩展点 (Future Extension Points)
当业务规模扩张至多校区、百万搜索级时，推荐平滑演进方案：
1. **日滚动键 + ZUNIONSTORE 权重合并**：
   - 写入：`ZINCRBY search:hot:YYYYMMDD 1 keyword`，为每个日 Key 设置 7 天 TTL（Redis 自身负责自然淘汰）；
   - 读取：通过 Redis `ZUNIONSTORE` 将最近 3 天或 7 天的 Key 聚合（可按天赋予递减权重，如今天 1.0，昨天 0.7，前天 0.4），天然实现时间衰减；
2. **轻量定时衰减 (Score Decay)**：
   - 每日凌晨执行单一 Lua 脚本将所有 member 的 score 乘以衰减系数（如 `0.8`），并将分值小于阈值的成员移除。

---

## 5. Redis 与数据库核心原则 (PostgreSQL = Source of Truth)

严格落实架构底线：**PostgreSQL 是唯一真实数据源（Source of Truth），Redis 仅作为缓存与计数加速层**。

### 5.1 收藏计数自愈闭环 (`FavoriteServiceImpl`)
- `getFavoriteCount(goodsId)` 优先读取 Redis；
- **缓存穿透/丢失保护**：若 Redis Key 不存在、或 Redis 宕机、或缓存数据损坏为负数，**绝不判定为 0**，而是自动触发 `refreshFavoriteCount(goodsId)`，直接查询 PostgreSQL 的 `select count(*) from campus_trade.favorite where goods_id=?`，并将真实结果重新回填 Redis。

### 5.2 浏览量计数容错保护 (`GoodsServiceImpl`)
- `getGoodsDetail(id)` 中获取浏览量：
  ```java
  int totalViews = (goods.getViewCount() != null ? goods.getViewCount() : 0)
          + (redisViewDelta != null ? redisViewDelta.intValue() : 0);
  ```
- 为 Redis `increment(viewKey)` 增加 `try-catch` 容错：若 Redis 发生瞬时故障或丢包，`redisViewDelta` 优雅降级为 0，页面展示严格以数据库持久化的 `goods.getViewCount()` 为基准，**绝不抛出 500 异常，绝不把浏览量清零**。
- `syncViewCounts()` 定期将 Redis 增量累计到 PostgreSQL `goods.view_count`，并原子扣减已持久化的 delta，实现数据安全收敛。

---

## 6. 测试套件与验证结果 (Test Suite & Results)

在 `backend/src/test/java/com/campustrade/CampusTradeStage35ETests.java` 中构建了 10 项高覆盖度自动化测试：

| 编号 | 测试用例方法名 | 验证重点 | 执行结果 |
| :---: | :--- | :--- | :---: |
| 1 | `test01_empty_and_whitespace_keywords_never_enter_redis_or_db` | 空串、纯空格、Tab、换行符过滤，DB与Redis零写入 | ✅ PASS |
| 2 | `test02_whitespace_collapse_and_trim_normalization` | 连续空白折叠、全角空格替换、首尾trim | ✅ PASS |
| 3 | `test03_casing_preservation` | `iPhone 15 Pro` 等品牌词大小写完整保留 | ✅ PASS |
| 4 | `test04_super_long_keyword_truncation_to_100` | 180+ 字符关键词安全截断至 100 字符无 DB 溢出 | ✅ PASS |
| 5 | `test05_hot_search_read_and_pagination_limit` | 热搜权重降序排列、limit 极限值 (-1, 9999) 防爆 | ✅ PASS |
| 6 | `test06_hot_search_fallback_when_redis_empty_or_absent` | Redis 热搜失效时优雅返回校园默认推荐列表 | ✅ PASS |
| 7 | `test07_redis_key_constants_uniformity` | `RedisKeyConstants` 命名与结构规范断言 | ✅ PASS |
| 8 | `test08_favorite_counter_redis_lost_recovery_source_of_truth` | Redis 缓存完全丢失后，从 DB 恢复并自愈回填 | ✅ PASS |
| 9 | `test09_view_count_redis_lost_source_of_truth` | Redis Key 不存在时，浏览量严格以 DB 基数为准 | ✅ PASS |
| 10 | `test10_sync_view_counts_from_redis_to_db` | 浏览量增量从 Redis 同步至 DB 并原子扣减 | ✅ PASS |

---

## 7. 全量质量门禁与回归结果

| 检验项目 | 执行命令 | 执行结果 | 状态 |
| :--- | :--- | :--- | :---: |
| **Stage 3.5-E 专项测试** | `mvn test -Dtest=CampusTradeStage35ETests` | `Tests run: 10, Failures: 0, Errors: 0` | ✅ PASS |
| **后端全量回归测试** | `mvn test` | `Tests run: 99, Failures: 0, Errors: 0` (覆盖 Stage 0~3.5-E) | ✅ PASS |
| **前端静态代码检查** | `flutter analyze` | `No issues found! (ran in 2.7s)` | ✅ PASS |
| **前端全量回归测试** | `flutter test` | `00:02 +23: All tests passed!` (覆盖 Stage 1~3.5-D) | ✅ PASS |

---

## 8. 是否存在新的风险 (Risk Assessment)

- **无新引入风险**：
  1. 搜索词标准化工具纯粹增强了防垃圾与健壮性，未改动 SQL 查询主体逻辑；
  2. 大小写保留策略兼容已有测试与前端展示习惯；
  3. Redis Key 常量完全兼容已有命名；
  4. Redis 操作全面包裹异常保护，即使 Redis 宕机，PostgreSQL 仍作为 Source of Truth 提供完整兜底；
  5. 全量 99 项后端测试与 23 项前端测试全部通过，无破坏性改动。

---

## 9. 综合验收结论

> **验收结论：PASS**
>
> CampusTrade 搜索词标准化、热搜垃圾防范、统一 Redis Key 治理、Source of Truth 兜底以及热搜生命周期保护已全部高标准落地并被测试锁定。
