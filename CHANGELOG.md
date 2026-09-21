# 变更历史（CHANGELOG）

本文件记录 CampusTrade 校园二手交易平台的**主要变更与验证结论**，按时间倒序排列。
每条都给出日期与提交短 hash，验证结论只写实际跑过的结果（测试项数、实测行为）。

- 项目从"能跑通"到"能交付"的完整路径：初始基线 → Stage 1–8 → 终审修复 → 批次 1/2 → 工程化与文档收尾。
- 参与开发前请先读 [CONTRIBUTING.md](CONTRIBUTING.md)（环境准备 / 门禁命令 / 编码与迁移约定 / 提交风格）。
- 测试基线的演进见文末「测试基线演进」表。

---

## 2026-09-21 — 后端端口改到 8081，并收敛为「只在一处配置」（工作区未提交）

**为什么换端口**：本机 8080 已被同机的另一个项目长期占用（图书管理系统，`com.library.Application`）。
CampusTrade 此前把 8080 写死在至少 7 处：`application.yml`、`start.ps1`、`stop.ps1`、
`backend/run-backend.cmd`、`backend/check-port.cmd`、`backend/Dockerfile`、`docker-compose.prod.yml`、
前端默认基址与文档。只改一个数字必然留下"启动器去 8080 找、后端其实在 8081"这类半通状态，
所以本次把端口收敛到**一处**，其余位置一律改为读同一个值。

**单一来源**：项目根目录 `.env` 的 `BACKEND_PORT`（模板 `.env.example`，当前 `8081`）。读取链路：

| 读取方 | 怎么拿到端口 |
| :--- | :--- |
| `backend/run-backend.cmd` | 解析 `.env` 后导出为环境变量 `SERVER_PORT`（Spring Boot 绑定 `server.port` 的约定名）；已在外部显式导出的 `SERVER_PORT` 优先（Spring Boot 自带的覆盖约定，便于"不改 `.env` 跑一个隔离端口实例"），控制台会打印端口来源 |
| `backend/src/main/resources/application.yml` | `server.port: ${SERVER_PORT:8081}`（读不到时回退 8081） |
| `start.ps1` | 读 `.env` 的 `BACKEND_PORT`：占用预检、占用者归属判定、启动中被抢占判定、等待循环、提示 URL、末尾导航全部用它 |
| `stop.ps1` | 读同一个键，决定"停止哪个端口的后端进程" |
| `backend/check-port.cmd` | 缺省读同一个键，也可用第一个参数显式覆盖（便于诊断任意端口） |
| `frontend/lib/config/app_config.dart` | 编译期常量默认 `http://127.0.0.1:8081/api`（与 `BACKEND_PORT` 同号，`--dart-define=API_BASE_URL` 仍可覆盖） |

生产编排是独立部署物，不读本地 `.env`：`docker-compose.prod.yml` 注入 `SERVER_PORT=8081`，
`backend/Dockerfile` 的 `EXPOSE 8081` / `HEALTHCHECK` 探针与之一致，容器内外同号
（不再有"容器内 8080 / 宿主机 8081"两套心智模型）；宿主发布端口可用 `APP_PORT` 调整。

**如何再改端口**：只改 `.env` 的 `BACKEND_PORT`，并把 `.env` 的 `API_BASE_URL` 与
`frontend/lib/config/app_config.dart` 的 `defaultValue` 改成同号（Flutter 不读 `.env`，这是唯一
需要人工同步的一处）；启动脚本、停止脚本、占用诊断、后端端口与文档示例 URL 都会跟着变。
生产要换内部端口则改 `docker-compose.prod.yml` 的 `SERVER_PORT` / `ports` / `healthcheck` 与 Dockerfile。

**验证（实际跑过的结果，均在 8080 被图书管理系统占用（PID 5964）的前提下）**

- **关键验收**：`start.bat` 选 4 → `[*] 等待本项目后端监听 8081` → `[成功] 后端服务已就绪！
  (http://127.0.0.1:8081/api)`，末尾导航的 API 地址也是 8081；
  `curl http://127.0.0.1:8081/api/school/list` → **HTTP 200**（返回真实高校 JSON）；
  后端日志 `Tomcat started on port 8081 (http) with context path '/api'`。
  跑前跑后查询 8080 占用者：PID 均为 **5964**（同一进程、同一启动时间）→ 图书管理系统未受任何影响。
- **冲突路径仍有效**：`python -m http.server 8081` 占住 8081 后 `start.bat` 选 4 →
  `[警告] 端口 8081 已被其它程序占用，CampusTrade 后端无法启动。占用进程: python.exe (PID 19168)`，
  退出码 **1**；改用选 1（完整启动）同样中止，Flutter 进程数 前 0 → 后 0（**未拉起前端**）。
- **改端口只需改一处**：把 `.env` 的 `BACKEND_PORT` 临时改为 `8082`，`start.bat` 选 4 →
  等待提示与成功 URL 均为 8082，`curl http://127.0.0.1:8082/api/school/list` → 200，
  日志 `Tomcat started on port 8082`；验证完成后已把 `.env` 改回 8081（`.env` 为 git-ignored 本地文件）。
- **端口来源可解释（`SERVER_PORT` 显式覆盖优先）**：`set SERVER_PORT=18082 && run-backend.cmd` →
  控制台 `[*] Port  : 18082 (context path /api; source: environment variable SERVER_PORT)`，
  `curl http://127.0.0.1:18082/api/school/list` → 200（应用确实落在 18082，而不是 `.env` 的 8081）；
  未显式导出时同一行为 `.env` 值、再兜底 8081（三个分支用与脚本逐字相同的批处理片段实测：
  缺省 → `8081 / built-in default`；`BACKEND_PORT=8082` → `8082 / .env BACKEND_PORT`；
  `BACKEND_PORT=8082` + `SERVER_PORT=18082` → `18082 / environment variable SERVER_PORT`）。
- **前端默认基址**：`flutter build web` 产物 `build/web/main.dart.js` 中 `127.0.0.1:8081/api`
  出现 2 次、`8080/api` 0 次（该产物经 `python -m http.server` 实际服务后再从 HTTP 取回核对，结果一致）；
  `lib/config/app_config.dart` 默认值与 README / docs / CONTRIBUTING 的示例 URL 全部一致。
- **门禁**：`cd backend && mvn -B test` → `Tests run: 257, Failures: 0, Errors: 0, Skipped: 0`（BUILD SUCCESS）；
  `cd frontend && flutter analyze` → `No issues found!`；`flutter test` → `+187: All tests passed!`。
- **编码抽检**：`start.ps1` / `stop.ps1` 前 3 字节 = `ef bb bf`（UTF-8 with BOM）；
  `start.bat` / `stop.bat` / `backend/run-backend.cmd` / `backend/check-port.cmd` 经 `file` 判定为
  ASCII 且全文件无 0x80 以上字节。
- **占用诊断脚本**：`backend/check-port.cmd 8080` 报
  `Port 8080 is held by another program: com.library.Application from D:/wkk/Campus Library Borrowing System (PID 5964)`
  并退出 1（只读判定，不碰对方进程）；`backend/check-port.cmd`（无参数）按 `.env` 取 8081；
  非法参数（`abc`）会提示并回退 8081，避免"查询失败被误判成端口空闲"。

**受影响文件**：`.env`（本地，git-ignored）、`.env.example`、`backend/src/main/resources/application.yml`、
`backend/run-backend.cmd`、`backend/check-port.cmd`、`start.ps1`、`stop.ps1`、
`backend/Dockerfile`、`docker-compose.prod.yml`、`frontend/lib/config/app_config.dart`、
`frontend/test/stage7d_status_contract_test.dart`、`frontend/test/order_api_test.dart`（两处仅 mock 基址字符串，
断言语义未变）、`frontend/integration_test/app_smoke_test.dart`（注释）、`README.md`、`docs/README.md`、
`frontend/README.md`、`CONTRIBUTING.md`。

> `docs/stage*/`、`docs/final-audit/` 里的历史快照按约定**不改**（它们是当时的记录，不是现状说明）。

---

## 2026-09-21 — 代码整洁、测试收敛与小加固（本批次，工作区未提交）

**范围**：JWT 校验比对 userId、认证测试用例自治、商品详情查询数评估、HTML 转义策略落地与守护测试、
前端 `_serverMessage` / pageSize / 版本号收敛、后端测试裸状态字面量枚举化。

**变更**

- **JWT 校验同时比对令牌内 userId（安全小加固）**：`JwtAuthenticationFilter` 此前只按令牌 `sub`（用户名）
  查库，不比对令牌里的 `userId`——当前没有账号删除路径所以不可利用，但一旦将来加入"注销/彻底删除"，
  旧令牌会在同名账号被重建后认证成**另一个用户**。现在比对不一致即 401
  （日志给出双方 id 与令牌指纹，不打印令牌原文），并在同一次请求里只解析一次令牌载荷
  （原先 `validateToken` + `getTokenType` + `getUsername` 各解析一次，即 3 次 HMAC 验签 → 1 次）。
- **`AuthSecurityEnhanceTest` 用例自治**：移除 `@TestMethodOrder(OrderAnnotation.class)` 与
  `accessToken` / `refreshToken` / `userId` 静态字段，改为每个用例在 `@BeforeEach` 里自带前置
  （随机账号 → 注册 → 登录）。此前"单独运行某个用例"必然失败（前置用例没跑、静态字段为 null），
  且 5 号用例（删除 Refresh 白名单）一旦先于 4 号执行就会让 4 号假失败。每个用例另分配独立来源 IP，
  避免注册接口"单 IP 每小时 20 次"的限频被同一测试 JVM 内多个测试类的注册次数累加撞成 429。
  新增 1 项用例：令牌 userId 与库中用户不一致 → 401（并对照同次前置签发的合法令牌仍为 200）。
- **`StudentVerifyStatus` 枚举补齐**：`student_verify.verify_status` 此前没有枚举，取值在实体默认值、
  服务层两个常量与查询条件里各写一遍字面量（而"SUCCESS 只能由验证码核销成功写入"是一条安全边界）。
  新增 `com.campustrade.enums.StudentVerifyStatus`（PENDING / SUCCESS，与 V10 的
  `chk_student_verify_status_domain` 及 V12 的部分唯一索引同域；刻意不含 DB 不允许的 FAILED），
  生产侧（实体默认值、`StudentVerifyServiceImpl`、`GoodsServiceImpl`、`Report`）全部改为引用枚举。
- **测试裸状态字面量收敛**：18 个后端测试类里 122 处领域状态字面量 → 2 处（**收敛 120 处**）。
  唯一保留的两处是 `CampusTradeStage7DTests` 里"枚举取值域 vs V10 CHECK 约束"的契约断言：
  那里的字面量是被比对的**外部真相**（数据库取值域），改成枚举自比会变成恒真、反而失去护栏作用。
  另把 `CampusTradeDataIntegrityTests` 里 5 处测试内部的 `"SUCCESS"` 完成标记提为具名常量
  `OUTCOME_SUCCESS`（明确它不是领域状态，因此不引用枚举）。
- **商品详情串行查询（评估结论：保持现状）**：用 MyBatis 拦截器（`SqlStatementCounter`）实测
  `getGoodsDetail` 单请求 SQL 条数：**匿名访客 8 条、已登录访客 11 条**（多出的 3 条是浏览足迹写入路径
  的 1 查 1 写与"是否已收藏"的 1 查；收藏数走 Redis 缓存命中，未落库）。8 条全部是主键或带索引的单行查询，
  50 次调用平均 ~10.5ms/次（含 Redis INCR 与足迹写入）。合并候选（卖家 user + user_credit + student_verify、
  图片 + 标签）每项只能省 1 条语句，却各需要一条手写 join/union SQL 与手工映射，并要求在不改变语义的前提下
  复现 `LIMIT 1`、null 兜底与索引使用；并行化则会在非事务方法里为单个请求同时占用多连接
  （Hikari 开发 10 / 生产 20），引入新的连接池压力失败模式。收益（数毫秒）小于风险，**不改**。
- **`HtmlEscapeUtils` 使用策略落地**：类文档与 README 新增「安全约定：用户内容的存储与转义」章节，
  写明"唯一展示端是 Flutter 纯文本渲染、后端无 HTML 模板，因此按原文存取；新增 HTML 展示端必须在输出点
  调用 `escape()`"。新增守护测试 `BackendHtmlSurfaceGuardTests`：扫描 `src/main/resources`，
  一旦出现 `templates/` 或 `.html/.ftl/.vm/.mustache/.jsp` 等模板类文件即失败（已实测：放入
  `templates/*.html` 与 `static/**/*.html` 都会失败，移除后通过）。
- **前端 `_serverMessage` 统一**：`GoodsService` / `FavoriteService` / `HistoryService` 三份重复的
  "取服务端 message"实现删除，统一为 `api_error.dart` 的 `serverMessageOf` / `serverMessageOr`
  （`describeApiError` 也复用同一份解析）。新增 `test/final_api_error_test.dart` 钉住
  "服务端 message 优先 / 缺省回退本中文案 / 状态码保留"。
- **前端分页 size 默认值收敛到 `AppConfig`**：`goods_service`（2 处）、`favorite_service`、
  `history_service`、`order_api`、`review_api`（2 处）里写死的 `int size = 10/20` 改为引用
  `AppConfig.*PageSize`，接口默认值与调用侧不再各写一份。
- **`AppConfig.appVersion` 与 pubspec 同步**：`1.0.0 (Stage 0)` → `1.0.0+1`（与 `pubspec.yaml` 一致），
  并删掉首页两处用户可见的阶段号文案（"Stage 1：…" / "Stage 2：…"，项目已到阶段 8，页面还停在阶段 1/2）。
  新增 `test/final_config_sync_test.dart`：①`AppConfig.appVersion` 必须等于 pubspec 的 `version`；
  ②`lib/` 下不得出现含 `Stage <数字>` 的字符串字面量（注释里的历史阶段名不受影响）。
  刻意不引入 `package_info_plus`：为一个展示用版本号拉入平台插件不划算，改用测试保证同步。

**验证**

- 后端 `mvn -B test`：**257 项全绿**（基线 255 + 新增 2：`AuthSecurityEnhanceTest` 的 userId 比对用例、
  `BackendHtmlSurfaceGuardTests`）。
- `AuthSecurityEnhanceTest` 逐个用例单独运行验证：`#test04_refreshTokenSuccess`、
  `#test05_refreshFailsWhenNotInRedisWhitelist`、`#test06_tokenUserIdMismatchIsRejected` 均
  `Tests run: 1, Failures: 0, Errors: 0`。
- JWT userId 不匹配实测：`token.userId=2101724373833118274` vs `db.userId=2101724373832118274` →
  `HTTP 401 body={"code":401,"message":"未登录或登录已失效，请重新登录",...}`，同时日志输出
  `令牌内 userId 与数据库用户不一致，拒绝认证: username=..., tokenUserId=..., dbUserId=..., tokenFp=10e377bd`；
  同次前置签发的合法令牌仍为 200。
- 前端 `flutter analyze` **0 issue**、`flutter test` **187 项全绿**（基线 176 + 新增 11）。

---

## 2026-09-21 — 工程化与文档收尾（批次 3，工作区未提交）

**范围**：质量门禁前置检查、CI 与本地门禁一致性、镜像可复现性、CHANGELOG / CONTRIBUTING、阶段 7/8 报告与运维章节。

**变更**

- **门禁 Docker 前置检查**：`scripts/quality-gate.ps1` 复用 `scripts/toolchain.ps1` 的 `Resolve-DockerCmd` /
  `Test-DockerAvailable`，在跑测试前确认 Docker 可用：不可用时打印中文原因（为什么需要 Docker：
  测试中间件由 Testcontainers 现拉现用）与解决办法（启动 Docker Desktop / `docker info` 自检 /
  `-Only analyze`、`-Only flutter-test` 跳过后端测试）并以 **非 0** 退出，不再让 `mvn test`
  把英文异常抛进日志。只跑前端两项时不检查 Docker。
- **CI 与本地门禁对齐**：`.github/workflows/ci.yml` 删除"生成一次性 JWT 密钥"步骤 —— 它不是安全措施而是**空转**：
  测试的 `jwt.secret` 由 `TestContainersConfig` 在测试 JVM 内随机生成，并以最高优先级的 `PropertySource`
  注入，任何外部 `JWT_SECRET` 都会被覆盖。确认工作流中没有其它 `SPRING_*` / `MINIO_*` env 需要清理
  （测试走 Testcontainers，本就没有）。CI 命令与本地门禁命令保持一致。
- **镜像可复现性**：`backend/Dockerfile` 运行阶段基础镜像由浮动的 `eclipse-temurin:21-jre-alpine`
  固定为 **`eclipse-temurin:21.0.12_8-jre-alpine-3.24`**（Docker Hub Tags 页查得：浮动的 `21-jre-alpine`
  当前指向该版本；manifest list digest `sha256:1a29e1fe…`）；`dependency:go-offline` 去掉 `|| true`
  （不再静默吞掉依赖解析失败）。`TestContainersConfig` 的测试容器镜像固定为 `postgres:16.15` /
  `redis:7.4.11` / `minio/minio:RELEASE.2024-10-13T13-34-11Z`，与 `docker-compose.yml` 一致
  （MinIO 原为 `minio/minio:latest`）。README 生产部署章节补充"镜像 tag ↔ pom 版本"对应关系与
  **digest 记录方法**。同一批把残留的旧 tag 表述一并清掉：`.github/workflows/ci.yml` 步骤注释里的
  "postgres:16 / redis:7 / minio" 改为"镜像 tag 固定、见 TestContainersConfig"，
  `TestContainersConfig` 类注释里的 `GenericContainer("redis:7")` 改为引用 `REDIS_IMAGE` 常量
  （写死 tag 的注释会在下次升级时又变成假信息）。
- **新增文档**：`CHANGELOG.md`（本文件）、`CONTRIBUTING.md`（环境 / 门禁 / 编码约定 / 迁移与测试约定 / 提交风格）、
  `docs/stage7/Stage7-completion-report.md`、`docs/stage8/Stage8-completion-report.md`，
  并更新 `docs/README.md` 索引（阶段报告索引表 + V12 + 基线）。
- **README 运维章节补全**：① 上线前必须执行的 **Redis 历史明文 refresh token 清理**
  （`jwt:refresh:*` 中值以 `eyJ` 开头的键，附盘点/清理命令与影响说明）；② **V10 在线 DDL 与锁语义**
  （`ADD CONSTRAINT` 全表校验 + `ACCESS EXCLUSIVE`；两条外键为 `NOT VALID` 只约束新行；
  `VALIDATE CONSTRAINT` 可在线执行）；③ 可信反向代理 `SECURITY_TRUSTED_PROXIES`（已存在，核对）；
  ④ 备份与 `random_page_cost` 建议（已存在，核对）。
- **测试基建注释去版本号**：`TestContainersConfig` / `application-test.yml` / `testcontainers-postgres-init.sql`
  中"V1..V9 / 从 V1 迁移到 V9"改为不含具体版本区间的表述（迁移现为 V1..V12，写死区间必然过时）。
- **历史审计报告抬头校正**：`docs/final-audit/` 7 份报告的"当前状态"基线由 242 / 142 更新为 **255 / 176**，
  消除批次 1/2 后产生的新矛盾。

**验证**

- 停 Docker（`docker desktop stop`）跑门禁 → 中文指引 + `exit 1`（未启动任何测试进程）；此时
  `-Only analyze` 仍可跑通（`exit 0`，实测 0 issue）；`docker desktop start` 后引擎与 19 个容器全部恢复，
  重跑门禁 → `exit 0`；
- `docker compose -f docker-compose.prod.yml config` 通过（退出码 0，`image: campustrade-backend:0.0.1`）；
- `docker build` 实测：`--no-cache-filter=build` 强制构建阶段不被缓存命中 → `dependency:go-offline`
  **BUILD SUCCESS（8 min 05 s，冷缓存）**、`package` **BUILD SUCCESS（19.3 s）**，整体 `exit 0`，
  证明去掉 `|| true` 后依赖解析仍然完整通过、且固定 tag 可解析（`eclipse-temurin:21.0.12_8-jre-alpine-3.24`
  解析到 digest `sha256:1a29e1fe…`，与 Dockerfile 注释一致）；
- 后端 `mvn -B test` **255 项全绿**；`flutter analyze` **0 issue**；`flutter test` **176 项全绿**。

---

## 2026-09-21 — 批次 2：前端架构与可观测性收尾 `d740cb7`

**范围**：控制器作用域、请求取消、页面守卫、日志收敛、覆盖补齐。

**变更**

- **控制器作用域**：7 个 `GetPage` 配 `binding`（`lazyPut`，非 `permanent`）；同类型共存的页面用 tag 区分
  （`OrderController.tagMyOrders` / `tagCreate`、`GoodsController.tagMyGoods`）；新增 `PageControllerScope`
  统一取用与释放。修掉"跨页错误态串页"（别处下单失败后，"我的订单"显示"创建订单失败"）
  与"我的发布"预热集市 4 个无关请求的副作用。
- **CancelToken**：api / service 层支持取消，控制器在 `refresh` / `onClose` 时 `cancel()`，旧请求不再占用网络或回写状态。
- **自动登录失败清理**：`tryAutoLogin` 失败改为 `clearAll()`，不再残留 7 天有效的 refresh token
  导致"自动登录失败后被静默重登"。
- **页面级提示**：统一走 `safeSnackbar` + 补 29 处 `mounted` 守卫（接单/完成/取消、发布、详情、列表返回等）。
- **可观测性**：新增 `lib/utils/app_logger.dart`（debug 输出、release 静默但保留可注入的 `onErrorReport` 钩子）；
  裸 `debugPrint` 79 处 → 4 处（仅 logger 自身），关键错误走 `error` / `warn`。
- **覆盖补齐**：新增 3 个测试文件共 34 条（控制器作用域与跨页隔离、CancelToken 取消、`tryAutoLogin` 清理、
  页面守卫、409/429 文案映射、此前无覆盖的页面 widget 测试）；新增
  `integration_test/app_smoke_test.dart` + `test_driver`（运行时随机账号、自清理）。
  **诚实说明**：该冒烟测试在本机因缺 chromedriver、Windows 未开启开发者模式（插件符号链接）、
  8080 被另一项目占用而**未能真实运行**，文件头写明运行命令；`integration_test` 不被 `flutter test` 收集。

**验证**：`flutter analyze` 0 issue、`flutter test` **176 项全绿**（基线 142 + 34）。

---

## 2026-09-21 — 批次 1：后端安全与数据一致性收尾 `afa595d`

**范围**：终审剩余项的安全与数据一致性收口。

**变更**

- **可信代理解析**：`ClientIpUtils` 默认不再采信 `X-Forwarded-For` / `X-Real-IP`（只用 `remoteAddr`）；
  仅当 `remoteAddr` 命中 `security.trusted-proxies`（CIDR / 精确 IP）时从右向左取首个非可信地址。
  修复"IP 维度限流可被伪造头绕过"与"伪造共享出口 IP 反向锁人"。
- **AI 助手配额**：按 `userId` 10 次/分钟 + 50 次/天，超限 429（此前仅需登录，可无限消耗额度）。
- **校园邮箱发送配额**：改为（发起人 `userId` × 目标邮箱）3 次/24h；核验失败计数同样按
  （`userId`, `email`）—— 修复"任意用户可打满他人邮箱配额使其 24h 无法认证""可恶意作废他人验证码"。
- **token 端点限流**：`/auth/refresh` 与 `/auth/logout` 按 IP 30/分钟 + 令牌指纹 10/分钟，并复用单次请求内的 JWT 解析结果。
- **上传加固**：对象 `Content-Type` 改为服务端按扩展名映射（不再取客户端值）；用 `ImageIO` 读尺寸拒绝
  单边 > 10000px 的图片（解压炸弹）；对外文案统一、细节只进日志。
- **V12 迁移**：`student_verify(school_id, school_email) WHERE verify_status='SUCCESS'` 部分唯一索引
  （建前只读排查，冲突则打印明细并跳过、不删数据）+ 12 条 `NOT VALID` 外键补齐
  （`review` / `review_like` / `browse_history` / `search_history` / `report` / `admin_audit_log` /
  `student_verify` / `user_credit`）；迁移头补锁与耗时说明及"`NOT VALID` + 事后 `VALIDATE CONSTRAINT`"运维步骤
  （开发库实测 `user_credit.user_id` 有 14 条孤儿行，正是必须 `NOT VALID` 的证据）。
- **同一邮箱重复认证** → 409 明确业务错误；`student_verify` 的 `PENDING` / `SUCCESS` 写入改为带前置条件的定点更新。
- **举报每日限流**：`INCR` 优先 + fail-closed + DB 当日计数兜底（并发 20 次实测：成功 10、429 10、不超发）。
- **工程整洁**：新增 `RedisRateLimiter` 统一计数限流器；Redis 引入 `commons-pool2` + Lettuce 连接池；
  DeepSeek 占位符统一 `CHANGE_ME_` 前缀（与 `ProdSecretsGuard` 判定一致）；5 处静默 `catch` 补日志。

**验证**：后端 **255/255**（新增 13 项）、前端 `analyze` 0 issue + **142/142**；
V12 在 Testcontainers 全新库与开发库均 `success`（Flyway 已登记 `version=12`）。

---

## 2026-09-20 — 终审修复：补掉遗留 P1 与文档可信度问题 `45ce200`

终审（三路并行：安全 / 端到端 / 代码工程）结论：无 P0，端到端 7 大流程全部通过。

**变更**

- `UserServiceImpl.updateProfile` 由整行回写改为定点更新（`LambdaUpdateWrapper` 只写资料字段）——
  阶段 4 同源缺陷的漏网：原实现会把管理员刚写入的 `status=FROZEN` 覆盖回 `ACTIVE`，
  导致冻结被静默撤销、审计与实际背离；
- `OrderController.fetchOrderDetail` 增加详情请求序号：切换订单时作废在途旧响应，
  消除"订单详情串单"（迟到的 A 订单响应覆盖 B 订单，随后用错误订单拉评价状态）；
- `ProdSecretsGuard` 增加"拒绝已知开发口令"：仓库里可被公开检索到的值一律拒绝在生产启动，
  堵住"未带 `--env-file` 而误读仓库根开发 `.env`"的路径（实测列出并拒绝启动）；
- `docs/final-audit` 的 7 份修复前审计报告加"历史报告（结论已过时）"抬头，并在 Final-Database-Audit
  追加修订说明，避免评审按"189 项测试 / V1–V8 / `init.sql` 与迁移完全同步"这类**已被推翻**的结论判断项目；
- **运行期清理**：开发 Redis 中由阶段 2 之前旧代码写入的 **37 个明文 refresh token** 已删除
  （现全部为 64 位 SHA-256 摘要）；其它环境上线时需同步执行一次相同清理（见根 README「上线前必须执行：
  清理 Redis 中的历史明文 refresh token」）。

**验证**：后端 242/242、前端 `analyze` 0 issue + 142/142；生产守卫拒绝开发口令实测通过。

---

## 2026-09-20 — Stage 8：生产交付与文档校正 `0669c3c`

**配置与隔离**

- `.env.example` 凭据全部改为 `CHANGE_ME` 占位符；`application.yml` 清空数据库口令 / MinIO secret 的运行期默认值；
- `application-prod.yml` 重写：敏感项无默认值、Hikari / Redis 超时收敛、Flyway baseline 关闭、优雅停机、
  日志 INFO + 关闭 MyBatis SQL 打印；新增 `ProdSecretsGuard`（`EnvironmentPostProcessor`，在 Flyway 之前
  校验必填环境变量、拒绝占位值、拒绝 CORS 通配，缺失即中文报错拒绝启动）。

**交付物**

- `backend/Dockerfile`（多阶段 maven → JRE21-alpine、非 root uid 10001、`HEALTHCHECK`）+ `.dockerignore`；
- `docker-compose.prod.yml`（独立 project；中间件零端口发布；tag 全固定；日志轮转 + 资源上限；
  Redis 强制口令；app 只读根文件系统 + `tmpfs` + `no-new-privileges` + `depends_on: service_healthy`）；
- `frontend/README.md`（`flutter build web`、`--dart-define` 注入 API 基址、Nginx SPA 回退与缓存排障）；
- `docker-compose.yml` 开发编排加固：中间件仅绑 `127.0.0.1`、Redis 口令、镜像 tag 固定、日志轮转与资源上限。

**脚本与 schema**

- 脚本全部改为检查退出码（`docker` / `mvn` / `flutter` 失败即非 0 + 中文原因）；选项 6 按测试结果退出，
  不再打印"启动完成导航"（顺带修掉 PowerShell 未消费管道输出导致 `exit 0` 的真实缺陷）；
- 工具链解析统一为「环境变量 → PATH → 中文指引」（新增 `scripts/toolchain.ps1` 与 `resolve-toolchain.cmd`），
  删除硬编码盘符；脚本不再回显数据库 / MinIO 明文口令；
- `docker/postgres/init.sql` 只保留 `CREATE SCHEMA`（建表唯一职责交给 Flyway），删除重复的 `stage*_tables.sql`。

**文档**：README 与 `docs/README` 全面校正（阶段 / 端口 15435 / 变量清单 / 门禁命令 / 生产部署步骤 /
备份与 `random_page_cost` 建议）；移除不存在的 Swagger 死链（不引入 springdoc，避免未请求依赖与暴露面）。

**验证**：隔离 project 全新库启动 → Flyway V1..V11 全 `success`、18 表、`GET /api/school/list` 200，
且验证前后用户卷逐字节未变；prod 栈实际跑通（非 root、只读根、中间件零发布）；prod 缺变量 `exit 1`；
选项 6 失败 `exit 1` 且无成功横幅；门禁：后端 242、前端 `analyze` 0 issue + 142。

---

## 2026-09-20 — Stage 7：索引、检索、N+1 与前端体验优化 `51ff667`

**后端**

- **V11 迁移**：新增 `goods(status, created_time DESC)` 与 `(status, category_id, created_time DESC)` 复合索引；
  删除 5 条经 `pg_stat_user_indexes.idx_scan` 二次确认零使用的冗余索引；`goods.view_count` 由 `INT4` 改
  `BIGINT`（`DO` 块判类型，可重复执行）；
- **关键词检索**：`LIKE` 保持参数绑定并对 `%`、`_`、`\` 转义 + `ESCAPE`（实测输入 `_` 由命中全部 425 条变为 0 条，
  `%` 精确命中 41 条）；评估 `pg_trgm` 后决定不加并给出依据（GIN 无法满足 `ORDER BY`，中文 2 字词不可用）；
- **N+1 消除**：管理员工单列表在 `pageSize=100` 时单请求 SQL 由 242 条降至 5 条，与 `pageSize` 解耦，
  并在测试内加 `assertTrue(statements <= 8)` 作为回归护栏；
- `listMyGoods` 支持分页（默认行为不变）并批量读 Redis；图片 / 标签改批量写入。

**前端**

- 新增 `goods_thumbnail.dart` 统一缩略图：按显示尺寸 × DPR 推导 `cacheWidth` / `cacheHeight`，
  加载中 / 失败同尺寸占位；9 处 `Image.network` 全部收敛；
- 商品列表整屏 loading 改为 `isLoading && list.isEmpty`；集市发布返回后自动刷新列表；
- 分页 `size` 魔法值收敛到 `AppConfig`；
- `web/index.html` 补品牌化首屏占位（随深色模式、首帧后淡出、15s 兜底），消除白屏。

**验证**：后端 242/242（新增 6 项）、前端 `analyze` 0 issue + 142/142（新增 3 项）；
`EXPLAIN` 与 `pg_stat_user_indexes` 前后对比见提交说明与代码注释。详见
[docs/stage7/Stage7-completion-report.md](docs/stage7/Stage7-completion-report.md)。

---

## 2026-09-20 — Stage 6：前端稳定性与错误处理 `a9ea6a2`

- **崩溃修复**：抽出 `initialOf` / `displayNameOf`（空 / 空白 / null 回退 + `runes` 防 emoji 截断），
  替换 5 处 `substring(0,1)`；昵称清空不再导致个人中心 / 首页白屏；
- **会话过期可重复**：`AuthController.login` 成功后复位去重标记，`_forceLogoutAndClear` 真实调用
  `handleSessionExpired`；同一进程第二次过期仍会清凭据并跳登录；
- **错误态与空态分离**（静默失败总根因）：服务失败改为抛 `ApiException`，控制器暴露 `errorMessage` / `hasError`，
  页面按 `isLoading → hasError → 空态 → 列表` 顺序渲染并带重试；
- **分页状态机**：`loadMore` 失败回滚页码；`refresh` 递增 `requestId` 作废在途响应；`onClose` 丢弃迟到响应；
- **身份判定**：`isSeller` 只看服务端 `buyerId` / `sellerId`，不再被跨页共享的 `currentRole` 污染；
  顺带修正评价标签方向；
- **资源与生命周期**：`await` 后补 `mounted`；弹窗 `TextEditingController` 改为弹窗内持有并 `dispose`；
  `flutter_secure_storage` 读写全部降级；
- **文案**：用户可见处不再出现 `$e`，统一走 `api_error` 映射；`catch (_) {}` 清零。

**验证**：`flutter analyze` 0 issue、`flutter test` 139 项全绿（新增 21 项）。

---

## 2026-09-20 — Stage 5：接口契约与领域模型收敛 `7250889`

**后端**：`BusinessException` 业务码映射到真实 HTTP 状态（400/401/403/404/405/409/422/429/500），响应体结构不变；
未匹配路径 404（此前 500）、不存在的资源返回真实 404（此前 HTTP 200 + body 404）；去 Web 信封污染
（Auth/User/School/StudentVerify 服务不再返回 `Result<T>`）；新增 `enums/GoodsStatus`（与 V10 `CHECK` 取值一致）
+ `GoodsSqlProvider`；新增 `CreditRule` 单一来源替换三处重复实现；XSS 处理改为保真存储 +
输出侧 `HtmlEscapeUtils` 转义 + 标记检测告警（不再用黑名单改写正文）；清理死代码。

**前端**：新增 `GoodsStatus` / `VerifyStatus` 枚举与 `OrderStatus.unknown` 分支（未知状态显示原文且不给出可点击操作）；
新增 `api_error.dart` 统一 4xx / 5xx 文案映射。

**验证**：后端 236/236（新增 10 项契约测试）、前端 `analyze` 0 issue + 118/118（新增 9 项）。

---

## 2026-09-20 — Stage 4：数据一致性与不变式 `3b7891a`

- 整行回写改为**定点更新**（商品状态跃迁 / 浏览量累加 / 管理员下架下沉为带前置条件的单条 `UPDATE` 并校验受影响行数）；
  浏览量用 `setSql` 原子累加；
- 浏览量刷盘可重入（先落库再扣减 Redis，失败回填脏集合），定时任务加分布式锁；
- 举报处理改原子条件更新（并发处理只生效一次、审计只增一条）；
- 信用审计口径修正（对账恒等式 `100 + SUM(change_score) == credit_score` 成立），幂等键纳入治理动作 key；
- 并发初始化改 `ON CONFLICT DO NOTHING / DO UPDATE`；补齐唯一约束冲突捕获（重复评价 409、用户名 / 邮箱占用 400）；
- **V10 迁移**：状态列 `CHECK`（11 条）+ 关键外键；已有孤儿数据的 `review` 外键使用 `NOT VALID`；
  孤儿排查 / 清理 SQL 见 `backend/docs/data-cleanup-orphans.sql`；种子表补 `setval`；
- 删除死代码 `incrementTradeCount`；新增 `CampusTradeDataIntegrityTests` 6 个并发 / 对账回归测试。

**验证**：后端 226/226（220 + 6 新增）；V10 在有 **520 条孤儿行**的开发库上成功应用（`success=true`）。

---

## 2026-09-20 — Stage 3：校园认证可信化 `24edcb9`

- 真实邮件下发：引入 `spring-boot-starter-mail`，SMTP 配置仅从环境变量读取（`MAIL_*`）；
- 响应与日志不再暴露验证码：`/student/verify` 改为 `Result<Void>`；验证码只存 Redis
  （顺带堵住 MyBatis DEBUG 日志把 SQL 参数打进日志文件的泄漏路径）；
- 限流与锁定：同邮箱 24h ≤5 次、同用户 10min ≤3 次、核验失败 5 次即作废；
- 本地开发通道：`verify.mail-enabled`（默认 `false`）仅在非 prod 下把验证码写入服务端日志并标注 `[DEV-ONLY]`；
  prod 强制 `true`，缺 SMTP 配置或开关即 fail-fast；
- 前端移除验证码自动填充与明文展示，改为倒计时 + 已发送提示 + 失败重试；
- 测试改为从 Redis 读取验证码（不在生产代码留测试开关）。

**验证**：响应无验证码；`mail-enabled=true` 缺 SMTP 返回明确 500 且不降级；输错 5 次作废、重发可恢复；
双向限流按预期拒绝；prod 缺配置拒绝启动；日志无验证码明文；后端 220 项保持全绿。

---

## 2026-09-20 — Stage 2：认证与令牌安全加固 `918c503`

- **JWT 密钥**：删除默认密钥，缺失 / 不足 32 字节 / 命中历史默认值一律 fail-fast；`run-backend.cmd`
  从 `.env` 注入环境变量（纯 ASCII，不回显密钥值）；
- 顺带修复 `.env` 中文注释在 GBK 控制台下吞掉行尾换行、导致后续变量被静默跳过的问题；
- **Principal**：用户不存在 / 已删除时直接 401，移除"用 token 内 role 构造 Principal"的旁路；
  角色与启用状态只来自数据库（冻结用户仍 403）；
- **refresh token**：Redis 只存 SHA-256 摘要；刷新即轮换；旧令牌重放则清空该用户会话；
  登出同时吊销 refresh 会话（此前只拉黑 access token）；
- 登录失败按用户名 + IP 计数并临时锁定；注册按 IP 限频；密码规则提升为 8 位且含字母数字；
- 日志不再打印完整 JWT / 密码 / 验证码（改为指纹）；CORS 改白名单（不再无条件通配）；
- 测试容器运行时随机注入 `jwt.secret`（源码零可用密钥字面量）。

**验证**：后端 220/220、前端 `analyze` 0 issue + 107/107；幽灵 ADMIN 令牌 401、冻结用户 403、
登出后 refresh 失效、旧 refresh 重放触发会话清空、6 次错误登录被锁定、6 位密码注册被拒、日志无令牌明文。

---

## 2026-09-20 — Stage 1：工程地基与可观测性 `769aca3`

- **版本控制**：`git init`、补全 `.gitignore`（日志 / 本地工具目录 / 原生构建缓存）、草稿移入 `.local-notes/`；
- **测试隔离**：引入 Testcontainers（PG / Redis / MinIO 一次性容器，凭据运行时随机生成），
  测试不再依赖开发中间件、不再污染开发库（实测停掉开发中间件后 220/220 全绿，开发库计数前后一致）；
- **凭据治理**：新增 `TestCredentials.randomPassword()` 消除 22 处硬编码口令；移除 `MinioProperties`
  中 `accessKey` / `secretKey` 的可用默认值；
- **surefire**：移除硬编码 `C:/Temp` 的 `java.io.tmpdir`；
- **日志**：新增 `logback-spring.xml`（控制台 + 按天滚动、UTF-8）与 `TraceIdFilter`（MDC + `X-Trace-Id` 响应头）；
- **质量门禁**：`scripts/quality-gate.ps1` 与 `.github/workflows/ci.yml`（后端测试 + 前端 analyze/test，失败即非 0）。

---

## 初始基线（Stage 1 之前，2026-09-18 审计时点）

- 后端：Stage 7 业务实现（认证 / 商品 / 订单 / 信用评价 / 治理 / AI）、**189 项**单元与集成测试全绿、
  Flyway **V1–V8**；前端：GetX + Dio 客户端；
- 该状态由 `docs/final-audit/` 下 7 份审计报告记录（**均带"历史报告（结论已过时）"抬头**，
  其中"189/189 通过""`init.sql` 与迁移 100% 同步""裁决 NEEDS OPTIMIZATION"等结论已被后续 8 个阶段推翻，
  不要作为现状依据）；
- 正是这批审计暴露的问题，构成了 Stage 1–8 与后续批次的修复清单（见 `docs/final-audit/Fix-Prompts-By-Stage.md`）。

---

## 测试基线演进

| 时点 | 提交 | 后端 `mvn -B test` | 前端 `flutter test` |
| :--- | :--- | :--- | :--- |
| 初始基线（审计） | — | 189 | — |
| Stage 1 | `769aca3` | 220 | — |
| Stage 2 | `918c503` | 220 | 107 |
| Stage 3 | `24edcb9` | 220 | — |
| Stage 4 | `3b7891a` | 226 | — |
| Stage 5 | `7250889` | 236 | 118 |
| Stage 6 | `a9ea6a2` | — | 139 |
| Stage 7 | `51ff667` | 242 | 142 |
| Stage 8 | `0669c3c` | 242 | 142 |
| 终审修复 | `45ce200` | 242 | 142 |
| 批次 1 | `afa595d` | 255 | 142 |
| 批次 2 | `d740cb7` | 255 | 176 |
| 批次 3（工程化与文档收尾） | `5544d5f` | 255 | 176 |
| 代码整洁、测试收敛与小加固 | 本批次 | 257 | 187 |

> 表格中的 `—` 表示该阶段提交未单独给出该侧计数（不代表测试未运行）。
> 每一行都要求 `flutter analyze` = 0 issue；当前基线为**后端 257 项、前端 187 项全绿**。
