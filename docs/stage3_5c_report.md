# Stage 3.5-C 总结与交付报告：权限隔离与异常边界测试加固

CampusTrade 校园二手交易平台已圆满完成 **Stage 3.5-C：权限隔离与异常边界测试加固** 的全量接口安全审计、权限边界修复、跨用户隔离加固、参数与分页极值防御、并发防重保障及前后端全量回归测试。

---

## 1. 审计发现与加固目标 (Audit Findings & Hardening Goals)

在对 Stage 3 已有模块（Favorite、Browse History、Search History、Goods、AI）进行安全审计时，发现了以下关键边界与防护隐患：

1. **未登录拦截一致性**：
   - 收藏（`POST/DELETE/GET check/GET list`）、浏览足迹（`GET history/list`）、个人搜索历史（`GET goods/search/history`）、AI 助手三大接口（`POST ai/goods/**`）必须严格收敛为需认证接口，未登录访问必须稳定返回 401。
   - 公开接口（`GET goods/list`, `GET goods/search`, `GET goods/search/hot`, `GET goods/{id}`）必须继续稳定支持未登录匿名访问，不因空认证凭据崩溃。
2. **跨用户数据隔离 (Horizontal Privilege Separation)**：
   - **Favorite**：用户 A 收藏后，用户 B `check` 必须为 `false`；用户 B 试图删除 A 的收藏必须被拒绝；用户 B 的收藏列表绝不能包含用户 A 的商品。
   - **Browse History**：用户 A 浏览商品产生的足迹，不能泄露在用户 B 的足迹列表中。
   - **Search History**：用户 A 搜索的关键词，用户 B 的个人搜索历史中绝不能包含。
3. **参数与类型异常统一拦截**：
   - 当用户传入非法非数字 ID（例如 `/goods/abc`、`/favorite/abc`）时，Spring 默认抛出 `MethodArgumentTypeMismatchException`，若未精细化处理易导致 500 异常；
   - 负数 ID（`-1`）、零（`0`）以及不存在的商品 ID（`9999999`）需稳定返回 404 或 400，杜绝底层 SQL 报错；
   - 已下架或逻辑删除的商品（`OFF_SHELF`）不得允许收藏。
4. **分页极值与 OOM 防御**：
   - 分页参数（`page`, `size`）可能受到恶意攻击（如 `size = 999999`），若未做限制会导致单次全表拉取甚至触发 JVM OutOfMemoryError (OOM)；
   - 非法负数（`page = -1`, `size = -1`）或 `size = 0` 需自动纠偏为默认分页规范。
5. **搜索输入与 SQL 安全边界**：
   - 空串或纯空格关键词需快速短路，不能向全站热搜 ZSet 中插入空白垃圾词；
   - 超长关键词（如 200+ 字符）需截断，防止数据库 `VARCHAR(100)` 溢出异常；
   - SQL 注入特殊字符（`' OR '1'='1`、`DROP TABLE`、`%%` 等）与 Emoji 特殊编码需具备免疫防线。
6. **并发收藏防重与取消幂等**：
   - 同一用户并发发起多个相同收藏请求时，数据库基于唯一索引 `(user_id, goods_id)` 拦截并发插入，服务层必须将 `DataIntegrityViolationException` 优雅转换为友好业务响应，且 Redis 计数器严格只加 1。
   - 取消未收藏商品幂等友好返回，且 Redis 计数器保底绝不低于 0。

---

## 2. 核心加固与代码改动清单 (Core Enhancements)

### 2.1 全局异常处理器加固 (`GlobalExceptionHandler.java`)
- 新增 `MethodArgumentTypeMismatchException` 处理：将非数字非法路径参数转换为友好的 HTTP 400 业务响应，消除了框架默认 500/报错页面；
- 新增 `HttpRequestMethodNotSupportedException` 处理：将不支持的 HTTP 请求方式统一捕获为 HTTP 405 响应。

### 2.2 收藏服务层边界加固 (`FavoriteServiceImpl.java`)
- **ID 校验与下架检查**：在 `addFavorite` 中显式拦截 `goodsId == null || goodsId <= 0`；同时查询商品状态，若为 `null` 或 `"OFF_SHELF"` 立即抛出 404 业务异常；
- **并发唯一冲突优雅处理**：捕获并发重复插入时的 `DataIntegrityViolationException`，拦截为 `400 "您已收藏过该商品"`；
- **分页极值收敛**：`pageFavorites` 对请求的 `size` 强制收敛至 `Math.min(size, 100)`，防止巨量分页查询；
- **取消收藏校验**：在 `removeFavorite` 中对非法参数进行拦截，未收藏商品取消时幂等提示 `400 "您尚未收藏该商品"`；Redis 计数器递减后若小于 0 则保底重置为 0。

### 2.3 商品服务层边界加固 (`GoodsServiceImpl.java`)
- **分页边界安全收敛**：`pageGoods` 对 `size` 进行收敛：`int size = (queryDTO.getSize() != null && queryDTO.getSize() > 0) ? Math.min(queryDTO.getSize(), 100) : 10;`；
- **搜索关键词超长截断**：`searchGoods` 检查关键词长度，超长时截断至 100 字符；
- **商品详情 ID 防护**：`getGoodsDetail` 检查 `id == null || id <= 0`，未命中直接抛出 404 业务异常。

### 2.4 历史与搜索足迹加固 (`BrowseHistoryServiceImpl.java` & `SearchHistoryServiceImpl.java`)
- **足迹分页收敛**：`pageHistory` 限制单页最大返回 100 条；
- **搜索历史安全写入与去重**：`recordSearch` 过滤空串与纯空格，超长关键词安全截断至 100 字符；
- **热搜榜单与个人历史上限**：`getHotSearches` 与 `getUserRecentSearches` 均收敛上限至 `Math.min(limit, 100)`。

---

## 3. 跨用户数据隔离验证 (Data Isolation Verification)

设计并实现了 `User A`（清华大学认证用户）与 `User B`（北京大学认证用户）的双用户隔离自动化测试：

```
+-------------------------------------------------------------------------------+
| 用户 A (Token A)                                 用户 B (Token B)             |
+-------------------------------------------------------------------------------+
| 1. Favorite 隔离                                                              |
|    POST /favorite/{goodsId} (成功)                                            |
|    GET  /favorite/check/{goodsId} -> true        GET  /favorite/check -> false|
|    GET  /favorite/list -> 包含该商品             GET  /favorite/list -> 不包含|
|                                                  DELETE /favorite/{goodsId}   |
|                                                  -> 400 "您尚未收藏该商品"    |
| 2. Browse History 隔离                                                        |
|    GET  /goods/{goodsId} (浏览并记录足迹)                                     |
|    GET  /history/list -> 包含该足迹              GET  /history/list -> 不包含 |
| 3. Search History 隔离                                                        |
|    GET  /goods/search?keyword=UserA_search_xxx                                |
|    GET  /goods/search/history -> 包含专属词      GET  /goods/search/history   |
|                                                  -> 绝不包含用户 A 专属词     |
+-------------------------------------------------------------------------------+
```

---

## 4. 并发防重与数据一致性验证 (Concurrency & Idempotency)

在多线程高并发场景下针对 `POST /favorite/{goodsId}` 与 `DELETE /favorite/{goodsId}` 进行极限压力验证：
- **10 线程并发收藏同一商品**：
  - 使用 `CountDownLatch` 保证 10 个线程在同一纳秒并发触发；
  - 结果：**1 次请求成功 (HTTP 200, code=200)**，**其余 9 次请求全部被优雅拦截为 400 (code=400)**；
  - 数据库检查：`select count(*) from campus_trade.favorite where user_id=? and goods_id=?` **严格精确为 1 条**；
  - Redis 检查：`goods:favorite:{goodsId}` 计数器**精确为 1**，绝不因并发冲突导致飘增。
- **取消收藏幂等与非负保底**：
  - 对未收藏商品调用 `DELETE /favorite/{goodsId}` 幂等返回 `400 "您尚未收藏该商品"`；
  - 重复调用取消收藏同样稳定返回 400；
  - Redis 计数器保底验证 `>= 0`，绝不出现负数。

---

## 5. 自动化测试套件矩阵 (`CampusTradeStage35CTests.java`)

在 `backend/src/test/java/com/campustrade/CampusTradeStage35CTests.java` 中构建了覆盖六大维度的 23 个自动化测试用例，全量执行通过：

| 序号 | 测试方法名 | 测试分类与断言目标 | 执行结果 |
| :--- | :--- | :--- | :---: |
| 1 | `test01_unauthenticated_favorite_post` | 未登录 POST /favorite/{id} 返回 401 | ✅ PASS |
| 2 | `test02_unauthenticated_favorite_delete` | 未登录 DELETE /favorite/{id} 返回 401 | ✅ PASS |
| 3 | `test03_unauthenticated_favorite_check` | 未登录 GET /favorite/check/{id} 返回 401 | ✅ PASS |
| 4 | `test04_unauthenticated_favorite_list` | 未登录 GET /favorite/list 返回 401 | ✅ PASS |
| 5 | `test05_unauthenticated_history_list` | 未登录 GET /history/list 返回 401 | ✅ PASS |
| 6 | `test06_unauthenticated_search_history` | 未登录 GET /goods/search/history 返回 401 | ✅ PASS |
| 7 | `test07_unauthenticated_ai_endpoints` | 未登录 POST /ai/goods/** 返回 401 | ✅ PASS |
| 8 | `test08_public_search_endpoints_allow_unauthenticated` | 公开接口未登录正常访问返回 200 | ✅ PASS |
| 9 | `test09_user_isolation_favorite` | Favorite 跨用户隔离 (check/delete/list) | ✅ PASS |
| 10 | `test10_user_isolation_browse_history` | History 跨用户足迹完全隔离 | ✅ PASS |
| 11 | `test11_user_isolation_search_history` | Search History 跨用户搜索记录完全隔离 | ✅ PASS |
| 12 | `test12_goods_not_found_boundary` | 不存在的 goodsId (9999999) 友好返回 404 | ✅ PASS |
| 13 | `test13_goods_off_shelf_boundary` | 已下架/已删除商品收藏拦截返回 404 | ✅ PASS |
| 14 | `test14_goods_negative_and_zero_id` | 负数与 0 goodsId 安全拦截返回 404，无 DB 崩溃 | ✅ PASS |
| 15 | `test15_goods_invalid_path_variable` | 非法路径参数类型 (/goods/abc) 拦截为 400 | ✅ PASS |
| 16 | `test16_pagination_favorite_list_boundaries` | 收藏分页 0, -1 纠偏，999999 极大值安全收敛 100 | ✅ PASS |
| 17 | `test17_pagination_history_list_boundaries` | 足迹分页边界与极大值安全收敛 100 | ✅ PASS |
| 18 | `test18_pagination_goods_search_boundaries` | 商品搜索分页极大值安全收敛 100 | ✅ PASS |
| 19 | `test19_search_empty_and_whitespace_keyword` | 空串与纯空格搜索正常响应且不进热搜 | ✅ PASS |
| 20 | `test20_search_super_long_keyword` | 200+ 超长 keyword 截断无 SQL 溢出报错 | ✅ PASS |
| 21 | `test21_search_special_characters_sql_injection` | SQL 注入载荷/特殊字符/Emoji 免疫防线 | ✅ PASS |
| 22 | `test22_favorite_concurrency_duplicate_requests` | 10 线程并发重复收藏：DB 仅 1 条，Redis 计数+1 | ✅ PASS |
| 23 | `test23_remove_favorite_idempotent_and_non_negative` | 取消不存在收藏幂等 400，Redis 计数保底 >= 0 | ✅ PASS |

---

## 6. 全量前后端测试回归汇总 (Full Regression Summary)

### 6.1 后端全量测试回归 (`mvn test`)
- **执行命令**：`mvn test -pl . --no-transfer-progress`
- **执行范围**：涵盖 Stage 0、Stage 1、Stage 2、Stage 3、Stage 3.5-A、Stage 3.5-B、Stage 3.5-C 的所有测试套件。
- **执行结果**：
  ```
  [INFO] Tests run: 89, Failures: 0, Errors: 0, Skipped: 0
  [INFO] BUILD SUCCESS
  [INFO] Total time: 15.504 s
  ```
- **通过率**：**89 / 89 (100% PASS)**，零失败、零跳过、零回归缺陷。

### 6.2 前端代码静态检查 (`flutter analyze`)
- **执行命令**：`flutter analyze`
- **执行结果**：
  ```
  Analyzing frontend...
  No issues found! (ran in 3.8s)
  ```
- **通过率**：**0 warnings, 0 errors**。

### 6.3 前端单元与组件集成测试 (`flutter test`)
- **执行命令**：`flutter test`
- **执行结果**：
  ```
  00:01 +12: All tests passed!
  ```
- **通过率**：**12 / 12 (100% PASS)**。

### 6.4 前端 Web 打包构建 (`flutter build web`)
- **执行命令**：`flutter build web --no-tree-shake-icons`
- **执行结果**：
  ```
  Compiling lib\main.dart for the Web... 27.0s
  √ Built build\web
  ```
- **产物状态**：成功构建生产级 Web 部署包。

---

## 7. 交付物清单 (Artifacts Inventory)

| 交付物类别 | 路径 / 文件名 | 说明 |
| :--- | :--- | :--- |
| **测试套件** | `backend/src/test/java/com/campustrade/CampusTradeStage35CTests.java` | 包含 23 个严格断言的 Stage 3.5-C 权限与边界测试类 |
| **全局异常处理** | `backend/src/main/java/com/campustrade/exception/GlobalExceptionHandler.java` | 增强类型不匹配与方法不支持异常捕获 |
| **收藏服务** | `backend/src/main/java/com/campustrade/service/impl/FavoriteServiceImpl.java` | 包含商品校验、并发防重、分页收敛、非负保底 |
| **商品服务** | `backend/src/main/java/com/campustrade/service/impl/GoodsServiceImpl.java` | 包含分页收敛、超长关键词截断、非法 ID 校验 |
| **足迹服务** | `backend/src/main/java/com/campustrade/service/impl/BrowseHistoryServiceImpl.java` | 包含分页极值收敛与安全保护 |
| **搜索历史服务** | `backend/src/main/java/com/campustrade/service/impl/SearchHistoryServiceImpl.java` | 包含空串过滤、超长截断、列表上限收敛 |
| **阶段报告** | `docs/stage3_5c_report.md` | 本报告文档 |

---

## 8. 阶段规范遵守与后续准备 (Compliance & Next Steps)

- **严格边界控制**：本阶段完全遵循指示，**未提前引入**订单交易系统、支付模块、即时通讯聊天、评价系统或管理后台，且**未修改**任何 AI Prompt 内容；
- **全链路一致性**：权限隔离、分页安全、并发幂等与数据一致性机制全面闭环；
- **系统状态**：系统已具备高可靠的权限隔离与异常容错防线，具备向下一阶段（如 Stage 4：订单交易与支付流转）平稳演进的基础。
