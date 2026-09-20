# Stage 3 完成报告：交易互动增强 + AI商品助手

CampusTrade 校园二手交易平台已圆满完成 **Stage 3：交易互动增强 + AI商品助手** 阶段的全部系统设计、接口研发、AI 安全治理与全栈自动化测试验证。

严格遵守开发准则，本阶段**坚决未提前开发**：
- ❌ 订单系统（下单、流转、核销）
- ❌ 支付与资金结算
- ❌ WebSocket 即时通讯（聊天）
- ❌ 交易后评价体系
- ❌ 后台管理系统

---

## 一、本阶段完成内容概览

1. **收藏系统（Favorite）**：
   - 支持学生对在售商品的收藏与取消收藏；
   - 数据库唯一约束杜绝重复收藏；
   - Redis 原子计数器维护实时商品被收藏量；
   - 提供分页倒序的“我的收藏”列表，展示商品成色、首图与价格。
2. **浏览历史（Browse History）**：
   - 访问商品详情时自动记录/异步刷新用户浏览足迹；
   - 保证同用户同商品只留存单行记录（Upsert 机制），避免数据膨胀；
   - 提供分页倒序的“浏览足迹”列表，带浏览时间标签。
3. **搜索增强与热搜排行榜（Search Enhancement）**：
   - 多维聚合搜索，支持关键词、分类、学校、价格区间与综合排序；
   - 搜索行为自动落库已登录用户搜索足迹表；
   - Redis Sorted Set（ZSet）实时计算全站搜索词权重并生成 Top 10 热搜榜。
4. **DeepSeek AI 商品助手（AI Goods Assistant）**：
   - 独立服务层 `service/ai`，集中统一管理 Prompt 常量；
   - 严格安全风控（标题限制 100 字、描述限制 1000 字、HTTP 请求 15 秒超时、自动过滤 Markdown 代码块）；
   - 优雅降级策略：在 API 离线、超时或未配 Key 时，无缝切换本地规则算法，确保 100% 具备高质量返回，零异常卡死；
   - 三大核心 AI 赋能：AI 帮写描述与卖点提炼、AI 推荐商品分类、AI 智能估价建议。
5. **Flutter 客户端交互闭环**：
   - 新增 `AiGoodsAssistantSheet`：**严格显式预览结果，经用户主动确认点击采纳才填入表单，严禁无感自动覆盖**；
   - 增强 `CreateGoodsPage`：接入 AI 提示横幅与三大 AI 助手入口；
   - 增强 `GoodsDetailPage`：支持红心收藏状态切换与收藏数实时更新；
   - 增强 `GoodsListPage`：新增【🔥 热搜】横向快捷搜索标签栏；
   - 新增 `FavoritePage`（我的收藏）与 `HistoryPage`（浏览足迹）；
   - 拓展 `ProfilePage`（个人中心）导流入口。

---

## 二、数据库变更详情

在 `campus_trade` Schema 中新增 3 张表及专属索引，已同步配置在 `docker/postgres/init.sql`：

```sql
-- 1. 商品收藏表
CREATE TABLE IF NOT EXISTS campus_trade.favorite (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES campus_trade.user(id) ON DELETE CASCADE,
    goods_id BIGINT NOT NULL REFERENCES campus_trade.goods(id) ON DELETE CASCADE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_favorite_user_goods UNIQUE (user_id, goods_id)
);
CREATE INDEX IF NOT EXISTS idx_favorite_user_time ON campus_trade.favorite(user_id, created_time DESC);

-- 2. 浏览足迹表
CREATE TABLE IF NOT EXISTS campus_trade.browse_history (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES campus_trade.user(id) ON DELETE CASCADE,
    goods_id BIGINT NOT NULL REFERENCES campus_trade.goods(id) ON DELETE CASCADE,
    browse_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_browse_history_user_goods UNIQUE (user_id, goods_id)
);
CREATE INDEX IF NOT EXISTS idx_browse_history_user_time ON campus_trade.browse_history(user_id, browse_time DESC);

-- 3. 搜索历史表
CREATE TABLE IF NOT EXISTS campus_trade.search_history (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES campus_trade.user(id) ON DELETE CASCADE,
    keyword VARCHAR(100) NOT NULL,
    search_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_search_history_user_time ON campus_trade.search_history(user_id, search_time DESC);
```

---

## 三、Redis Key 设计与数据结构说明

| Key 格式 | 数据结构 | 业务说明 | 读写策略 |
| :--- | :--- | :--- | :--- |
| `goods:favorite:{goodsId}` | `String` (Integer) | 单个商品的总收藏量计数器 | 收藏时 `INCR`，取消收藏时 `DECR`（下限保底 0），查询商品详情时读取 |
| `search:hot` | `ZSet` (Sorted Set) | 全站热门搜索词排行榜 | 用户搜索关键词时 `ZINCRBY search:hot 1 keyword`；获取榜单时 `ZREVRANGE search:hot 0 9` 取 Top 10 |
| `goods:view:{goodsId}` | `String` (Integer) | Stage 2 延续的商品详情实时浏览量计数 | 查看详情时 `INCR`，合并数据库基数返回 |

---

## 四、DeepSeek AI 助手服务设计

```
[Flutter 发布闲置表单] ---> POST /api/ai/goods/* ---> [AiGoodsController]
                                                            |
                                                   [安全风控校验] (title<=100, desc<=1000)
                                                            |
                                                   [AiGoodsService]
                                                            |
                                                +-----------+-----------+
                                                |                       |
                                        [DeepSeek API 正常]     [API异常/Key未配/超时]
                                                |                       |
                                        [DeepSeekClient]        [本地规则降级引擎]
                                                |                       |
                                                +-----------+-----------+
                                                            |
                                                   [统计 costMs 耗时]
                                                            |
                                             [AiGoodsAssistantSheet 预览卡片]
                                                            |
                                            +---------------+---------------+
                                            |                               |
                                      [用户确认采纳]                   [用户放弃]
                                            |                               |
                                     写入表单输入框                      保留用户原输入
```

### 1. 提示词管理 (`AiPromptConstants`)
- **智能润色**：系统 Prompt 明确定义助手身份为“高校二手交易文案专家”，强制返回符合 JSON 契约的 `{title, description, highlights}`，提取 3~5 个精炼校园交易卖点标签（如“考研利器”、“九成新保真”）。
- **智能分类**：基于标题与描述，智能匹配数据库标准类目，输出推荐品类名称及详细理由。
- **智能估价**：根据商品成色、参考原价与校园折旧行情，给出合理建议售价及建议区间。

### 2. 安全风控与边界控制
- **输入截断与防注入**：标题上限 100 字符，原描述上限 1000 字符；
- **HTTP 超时保护**：`RestTemplate` 配置 15 秒连接与读取超时，拦截慢请求拖垮系统线程池；
- **格式净化**：自动剔除大模型可能包裹的 ````json ... ```` 标记，稳健转为 JSON 树解析。

### 3. 本地高可用降级方案 (Fallback)
当外部网络抖动、API 额度耗尽或未配置 API Key 时，触发自动捕获机制并切换至本地算法规则库：
- 描述：结合标题与成色自动生成规整的校园自提文案与亮点标签；
- 分类：利用关键词字典快速推导目标分类；
- 估价：依据成色档位（全新 80%、95新 65%、9成新 50%、7成新 35%）结合原价或品类参考均价动态核算。

---

## 五、后端核心接口列表

| HTTP 方法 | 接口路径 | 鉴权要求 | 业务说明 |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/favorite/{goodsId}` | 登录用户 | 收藏指定商品，Redis 收藏计数加 1 |
| `DELETE` | `/api/favorite/{goodsId}` | 登录用户 | 取消收藏商品，Redis 收藏计数减 1 |
| `GET` | `/api/favorite/check/{goodsId}` | 登录用户 | 检查当前用户是否已收藏该商品 |
| `GET` | `/api/favorite/list` | 登录用户 | 分页获取我的收藏商品列表（倒序） |
| `GET` | `/api/history/list` | 登录用户 | 分页获取当前用户的浏览历史足迹（倒序） |
| `GET` | `/api/goods/search` | 公开/登录 | 增强搜索商品，自动记录搜索历史与热搜词权重 |
| `GET` | `/api/goods/search/hot` | 公开 | 获取全站热搜词 Top 10 榜单 |
| `GET` | `/api/goods/search/history` | 登录用户 | 获取当前登录用户的历史搜索词列表 |
| `POST` | `/api/ai/goods/description` | 登录用户 | AI 帮写润色商品标题、详情与卖点标签 |
| `POST` | `/api/ai/goods/category` | 登录用户 | AI 智能推荐商品所属分类及匹配理由 |
| `POST` | `/api/ai/goods/price` | 登录用户 | AI 智能评估商品售价、区间与估价依据 |

---

## 六、前端页面与交互说明

1. **`AiGoodsAssistantSheet`（底部交互弹窗组件）**：
   - 包含 DeepSeek V3 官方标签与加载微动效；
   - 采用预览卡片布局，清晰展示建议内容、依据理由与生成耗时（ms）；
   - **交互原则：提供【采纳并填入】与【放弃】双按钮，用户必须主动点击“采纳”，数据才填入表单输入框，绝对杜绝静默覆盖现有内容**。
2. **`CreateGoodsPage`（发布商品页面）**：
   - 顶部增加 AI 助手蓝紫色温馨指引横幅；
   - 分类旁增加【AI 推荐分类】按钮，采纳后自动联动 Dropdown 选中；
   - 价格旁增加【AI 智能估价】按钮，采纳后自动填入售价框；
   - 描述框上方增加【AI 帮写描述】按钮，采纳后自动填充描述并展示卖点。
3. **`GoodsDetailPage`（商品详情页）**：
   - 详情头部展示 `浏览量 · 收藏量` 统计；
   - AppBar 与底部操作栏均集成红心收藏切换，具备防抖与乐观更新；
   - 打开详情页自动在服务端落库/刷新浏览足迹。
4. **`GoodsListPage`（校园集市）**：
   - 顶部搜索框下方呈现【🔥 热搜】横向滚动 Chips，点击热搜词秒级触发展开检索。
5. **`FavoritePage` 与 `HistoryPage`（我的收藏与浏览足迹）**：
   - 支持下拉刷新与上拉触底分页加载；
   - 商品卡片展示首图、标题、成色、价格与已下架状态标签；收藏列表支持直接取消收藏。
6. **`ProfilePage`（个人中心）**：
   - 在“校园信誉档案”下方无缝新增“我的收藏”与“浏览足迹”入口。

---

## 七、自动化测试结果（后端 12 个测试点明细）

在 `CampusTradeStage3Tests.java` 中设计并执行了全部 12 个核心测试场景：

| 编号 | 测试方法名 | 测试验证场景 | 结果 |
| :---: | :--- | :--- | :---: |
| 1 | `test01_AddFavoriteSuccess_AndRedisIncrement` | 用户收藏商品成功，Redis `goods:favorite:{id}` 自增至 1 | **PASSED** |
| 2 | `test02_PreventDuplicateFavorite` | 重复收藏触发防重检查，唯一索引拦截并返回友好提示 | **PASSED** |
| 3 | `test03_RemoveFavoriteSuccess_AndRedisDecrement` | 取消收藏成功，Redis `goods:favorite:{id}` 计数减为 0 | **PASSED** |
| 4 | `test04_CheckFavoriteStatus` | 准确查询收藏状态，已收藏返回 true，未收藏返回 false | **PASSED** |
| 5 | `test05_GetFavoriteList` | 分页查询收藏列表成功，包含首图、成色、价格等关键信息 | **PASSED** |
| 6 | `test06_BrowseHistoryRecordAndUpsert` | 查看详情自动写入足迹；再次查看刷新时间戳，无重复行 | **PASSED** |
| 7 | `test07_GetBrowseHistoryList` | 分页查询足迹列表成功，严格按浏览时间倒序返回 | **PASSED** |
| 8 | `test08_SearchGoodsRecordsHistoryAndZSet` | 关键词搜索成功写入 `search_history`，自增 Redis `search:hot` | **PASSED** |
| 9 | `test09_GetHotSearchWords` | 成功从 Redis ZSet 读取全站 Top 10 热搜榜单 | **PASSED** |
| 10 | `test10_AiGenerateDescription_FallbackAndReal` | AI 描述润色成功，返回建议标题、描述正文与卖点标签 | **PASSED** |
| 11 | `test11_AiRecommendCategory_FallbackAndReal` | AI 智能分类匹配成功，返回推荐品类与依据理由 | **PASSED** |
| 12 | `test12_AiSuggestPrice_FallbackAndReal` | AI 智能估价成功，返回建议价、价格区间与评估原因 | **PASSED** |

### 全量后端测试汇总 (`mvn test`)
```text
[INFO] Running com.campustrade.CampusTradeApplicationTests (3 tests) -> 0 failures
[INFO] Running com.campustrade.CampusTradeStage1Tests (10 tests) -> 0 failures
[INFO] Running com.campustrade.CampusTradeStage2Tests (11 tests) -> 0 failures
[INFO] Running com.campustrade.CampusTradeStage3Tests (12 tests) -> 0 failures
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] Total time: 9.307 s
[INFO] ------------------------------------------------------------------------
```
**全量 36 个测试用例 100% 成功通过！**

---

## 八、前端测试与分析结果

### 1. 静态代码分析 (`flutter analyze`)
```bash
flutter analyze
Analyzing frontend...
No issues found! (ran in 3.3s)
```
**0 errors / 0 warnings / 0 issues**。

### 2. 自动化 Widget 测试 (`flutter test`)
覆盖全平台路由与交互组件共 12 项测试用例：
```text
00:00 +0: 1. 验证首页渲染与 Stage 1 标志
00:00 +1: 2. 验证登录页面渲染与表单组件
00:00 +2: 3. 验证注册页面渲染与表单组件
00:00 +3: 4. 验证个人中心未登录与已登录状态渲染
00:00 +4: 5. 验证校园认证页面组件
00:00 +5: 6. 验证商品列表页面渲染与分类搜索组件
00:00 +6: 7. 验证发布商品页面表单组件渲染
00:00 +7: 8. 验证我的发布商品页面渲染
00:01 +8: 9. 验证发布页面 AI 助手三大功能入口与横幅
00:01 +9: 10. 验证我的收藏页面渲染与空状态
00:01 +10: 11. 验证浏览足迹页面渲染与空状态
00:01 +11: 12. 验证个人中心包含我的收藏与浏览足迹入口
00:01 +12: All tests passed!
```
**12 项前端测试全部通过！**

### 3. Web 端生产编译构建 (`flutter build web`)
```text
Compiling lib\main.dart for the Web...                             23.5s
√ Built build\web
```
生产发布物编译打包成功，无树摇异常或语法破坏。

---

## 九、阶段守则遵守确认

- [x] **未提前开发订单系统**：无 Order 表、无订单状态机、无下单与核销接口；
- [x] **未提前开发支付体系**：无微信/支付宝/银联 SDK，无资金账户表；
- [x] **未提前开发 WebSocket 即时通讯**：详情页“联系卖家”维持弹窗提示占位，无 WebSocket 连接；
- [x] **未提前开发评价体系**：仅保留 Stage 1 的信用分数据展示，无评价打分与评论发布流；
- [x] **未提前开发管理后台**：聚焦 C 端学生互动体验与智能化；
- [x] **AI 安全与用户知情权**：所有 AI 生成结果具备独立弹窗预览卡片，由用户二次确认点击采纳才填入表单。

---

## 十、代码目录树变更

```
CampusTrade/
├── docker/
│   └── postgres/
│       └── init.sql                                    # [MODIFY] 增补 favorite, browse_history, search_history 表及索引
├── backend/
│   ├── pom.xml                                         # [MODIFY] 固定 surefire-plugin 的 Windows 临时目录参数
│   └── src/
│       ├── main/
│       │   ├── java/com/campustrade/
│       │   │   ├── config/
│       │   │   │   └── DeepSeekProperties.java         # [NEW] DeepSeek API 配置属性
│       │   │   ├── controller/
│       │   │   │   ├── AiGoodsController.java          # [NEW] AI 描述/分类/估价接口
│       │   │   │   ├── BrowseHistoryController.java    # [NEW] 浏览历史接口
│       │   │   │   ├── FavoriteController.java         # [NEW] 收藏管理接口
│       │   │   │   └── GoodsController.java            # [MODIFY] 接入增强搜索、热搜榜、搜索足迹接口
│       │   │   ├── dto/
│       │   │   │   ├── AiCategoryDTO.java              # [NEW]
│       │   │   │   ├── AiDescriptionDTO.java           # [NEW]
│       │   │   │   └── AiPriceDTO.java                 # [NEW]
│       │   │   ├── entity/
│       │   │   │   ├── BrowseHistory.java              # [NEW]
│       │   │   │   ├── Favorite.java                   # [NEW]
│       │   │   │   └── SearchHistory.java              # [NEW]
│       │   │   ├── mapper/
│       │   │   │   ├── BrowseHistoryMapper.java        # [NEW]
│       │   │   │   ├── FavoriteMapper.java             # [NEW]
│       │   │   │   └── SearchHistoryMapper.java        # [NEW]
│       │   │   ├── service/
│       │   │   │   ├── BrowseHistoryService.java       # [NEW]
│       │   │   │   ├── FavoriteService.java            # [NEW]
│       │   │   │   ├── SearchHistoryService.java       # [NEW]
│       │   │   │   ├── ai/
│       │   │   │   │   ├── AiGoodsService.java         # [NEW]
│       │   │   │   │   ├── AiPromptConstants.java      # [NEW] 集中 Prompt 常量
│       │   │   │   │   ├── DeepSeekClient.java         # [NEW] 具备超时与反解析的客户端
│       │   │   │   │   └── impl/AiGoodsServiceImpl.java# [NEW] 风控、降级与实现
│       │   │   │   └── impl/
│       │   │   │       ├── BrowseHistoryServiceImpl.java # [NEW]
│       │   │   │       ├── FavoriteServiceImpl.java    # [NEW]
│       │   │   │       ├── GoodsServiceImpl.java       # [MODIFY] 浏览足迹自动落库
│       │   │   │       └── SearchHistoryServiceImpl.java# [NEW]
│       │   │   └── vo/
│       │   │       ├── AiCategoryVO.java               # [NEW]
│       │   │       ├── AiDescriptionVO.java            # [NEW]
│       │   │       ├── AiPriceVO.java                  # [NEW]
│       │   │       ├── BrowseHistoryVO.java            # [NEW]
│       │   │       ├── FavoriteVO.java                 # [NEW]
│       │   │       └── GoodsDetailVO.java              # [MODIFY] 增补 isFavorite 与 favoriteCount
│       │   └── resources/
│       │       └── application.yml                     # [MODIFY] 添加 deepseek: 配置节点
│       └── test/java/com/campustrade/
│           └── CampusTradeStage3Tests.java             # [NEW] Stage 3 全部 12 项场景集成测试
└── frontend/
    ├── lib/
    │   ├── controllers/
    │   │   ├── favorite_controller.dart                # [NEW] 收藏状态控制器
    │   │   ├── goods_controller.dart                   # [MODIFY] 接入热搜与搜索历史
    │   │   └── history_controller.dart                 # [NEW] 浏览历史控制器
    │   ├── models/
    │   │   ├── ai_model.dart                           # [NEW] AI 返回模型
    │   │   ├── favorite_model.dart                     # [NEW] 收藏数据模型
    │   │   ├── goods_model.dart                        # [MODIFY] 增补收藏状态属性
    │   │   └── history_model.dart                      # [NEW] 足迹数据模型
    │   ├── pages/
    │   │   ├── favorite/
    │   │   │   └── favorite_page.dart                  # [NEW] 我的收藏页面
    │   │   ├── goods/
    │   │   │   ├── create_goods_page.dart              # [MODIFY] 接入 AI 助手弹窗与横幅
    │   │   │   ├── goods_detail_page.dart              # [MODIFY] 接入收藏切换与计数显示
    │   │   │   └── goods_list_page.dart                # [MODIFY] 接入热搜词横向滚动条
    │   │   ├── history/
    │   │   │   └── history_page.dart                   # [NEW] 浏览足迹页面
    │   │   └── profile/
    │   │       └── profile_page.dart                   # [MODIFY] 增补收藏与足迹菜单项
    │   ├── routes/
    │   │   ├── app_pages.dart                          # [MODIFY] 注册 /favorite 与 /history
    │   │   └── app_routes.dart                         # [MODIFY] 增补路由常量
    │   ├── services/
    │   │   ├── ai_service.dart                         # [NEW] AI 助手网络请求
    │   │   ├── favorite_service.dart                   # [NEW] 收藏网络服务
    │   │   ├── goods_service.dart                      # [MODIFY] 增补 searchGoods, getHotSearches, getSearchHistory
    │   │   └── history_service.dart                    # [NEW] 浏览历史网络服务
    │   └── widgets/
    │       └── ai_goods_assistant_sheet.dart           # [NEW] DeepSeek AI 助手底部弹窗
    └── test/
        └── widget_test.dart                            # [MODIFY] 增补 Stage 3 四项 Widget 测试
```

---

## 十一、遇到的问题与解决方案

1. **Windows 环境下 Netty/Lettuce Loopback 报错**：
   - *现象*：Windows 运行 Spring Boot 集成测试时，Lettuce 尝试使用 Unix Domain Sockets 触发 `Unable to establish loopback connection`。
   - *方案*：在 `pom.xml` 的 `maven-surefire-plugin` 中配置 `<argLine>-Djava.io.tmpdir=C:\Temp -Djdk.net.unixdomain.tmpdir=C:\Temp</argLine>`，永久固化解决。
2. **PostgreSQL MyBatis-Plus Schema 拼接陷阱**：
   - *现象*：若在 `@TableName` 中显式指定 `@TableName("campus_trade.favorite")`，由于 `application.yml` 已配置全局 `schema: campus_trade`，会导致 SQL 被拼接为 `campus_trade.campus_trade.favorite`。
   - *方案*：实体统一采用 `@TableName("favorite")`，由全局 schema 机制自动前缀。
3. **DeepSeek API 格式与鲁棒性**：
   - *现象*：部分模型输出会带有 ````json ... ```` 的 markdown 代码块，直接反序列化会报 JSON parse error。
   - *方案*：在 `DeepSeekClient` 与 `AiGoodsServiceImpl` 中编写正则提取有效 JSON 对象字符串，并在异常时平滑进入规则降级。
4. **Dart 3 弃用多下划线命名警告**：
   - *现象*：`(_, __)` 在 `flutter analyze` 中产生 `unnecessary_underscores` 提示。
   - *方案*：规范统一替换为显式语义参数 `(context, index)` 与 `(context, error, stackTrace)`，做到全工程 0 issues。

---

## 十二、下一阶段准备建议

建议在用户审核批准后，进入下一阶段：
**Stage 4：交易订单系统与校园自提/面交流转闭环**
- 订单生成（买家下单锁定商品 `LOCKED`）；
- 交易状态机（待确认 -> 待面交 -> 已完成 / 已取消）；
- 线下校园自提码核销；
- 订单与商品库存状态联动。
