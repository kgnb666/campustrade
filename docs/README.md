# CampusTrade 校园二手交易平台 - 详细设计与技术文档

本文档为 **CampusTrade（校园二手交易平台）** 的架构与技术环境规范说明。
面向"想看懂这个项目怎么跑起来、怎么部署"的读者；操作步骤以项目根目录 [README.md](../README.md) 为准。

---

## 一、项目介绍

CampusTrade 是一个面向高校大学生的校园闲置二手交易平台，核心功能已全部落地：

- **校园身份认证**：学号 + 校园邮箱验证码核验，验证码只走"真实邮件（生产） / 服务端日志（仅本地开发）"两条通道，
  它是发布商品的硬前置，也是卖家"已认证"标识的唯一依据；
- **二手商品流转**：商品发布（多图 + 分类 + 成色 + 标签）、列表 / 搜索 / 热搜、详情、上下架、
  浏览足迹与收藏；
- **交易订单闭环**：下单锁定 → 卖家确认 → 线下自提 → 完成 / 取消，含商品快照与状态机约束；
- **信用与评价**：信用分变动流水（幂等约束）、双向评价、评价点赞、信用等级；
- **平台治理**：统一举报工单（商品 / 评价 / 用户）、管理员处理与操作审计日志；
- **AI 智能赋能**：接入 DeepSeek 完成闲置估价、文案智能美化、智能分类（未配置 API Key 时优雅降级）；
- **安全与稳定性**：JWT 双令牌（access + refresh）与黑名单、登录失败锁定、注册限流、
  统一异常与统一响应体、TraceId 贯穿日志、浏览量异步刷盘。

各阶段成果：阶段 1 工程地基与 CI、阶段 2 认证安全、阶段 3 校园认证、阶段 4 数据一致性（V10）、
阶段 5 契约收敛、阶段 6 前端稳定性、阶段 7 性能优化（V11）、阶段 8 生产交付与文档校正。

---

## 二、技术架构

```
+-------------------------------------------------------------+
|                      CampusTrade 总体架构                    |
+-------------------------------------------------------------+
| 前端层 (Frontend): Flutter 3.x (Dart 3.x)                    |
| - 状态管理 & 路由: GetX                                       |
| - 网络请求: Dio（拦截器 + Token 自动刷新）                     |
| - 本地安全存储: Flutter Secure Storage                        |
| - 平台支持: Web / Android / iOS / Desktop                     |
+-------------------------------------------------------------+
                              |
                              | HTTP RESTful APIs  (/api)
                              v
+-------------------------------------------------------------+
| 后端服务层 (Backend): Spring Boot 3.3.4 (Java 21)             |
| - ORM: MyBatis-Plus 3.5.7 / 连接池: HikariCP                  |
| - 数据库迁移: Flyway（V1..V12，schema 单一真相源）             |
| - 安全: Spring Security 6 + JJWT 0.12.6                       |
| - 邮件: spring-boot-starter-mail（校园认证验证码）             |
+-------------------------------------------------------------+
                              |
       +----------------------+----------------------+
       |                      |                      |
       v                      v                      v
+---------------+     +---------------+     +---------------+
|  PostgreSQL   |     |    Redis 7    |     |  MinIO (S3)   |
| 关系型数据库   |     | 缓存与会话     |     | 对象文件存储   |
| schema:       |     | 限流/验证码/   |     | 商品图/举报    |
| campus_trade  |     | 锁/浏览计数    |     | 证据图         |
| 端口 15435     |     | 端口 6379     |     | 端口 9000/9001 |
+---------------+     +---------------+     +---------------+
```

### 关键设计约定

| 主题 | 约定 |
| :--- | :--- |
| **Schema 真相源** | 业务表结构只由 Flyway 迁移定义；`docker/postgres/init.sql` 仅建 `campus_trade` schema 与授权 |
| **配置来源** | 只走环境变量（本地由 `.env` 注入）：`.env.example` 是变量清单模板，不含任何可用凭据 |
| **环境隔离** | `prod` profile 下敏感项无可用默认值，缺失即拒绝启动（中文 fail-fast）；`test` profile 用 Testcontainers 自带中间件 |
| **认证** | `JWT_SECRET` 必填（≥32 字节，拒绝历史默认密钥）；access 2h / refresh 7d；退出登录写黑名单 |
| **统一响应** | 所有接口返回 `{code, message, data, timestamp}`；异常经 `GlobalExceptionHandler` 归一 |
| **端口暴露** | 开发编排中间件仅绑 `127.0.0.1`；生产编排中间件不发布端口，只在内部网络可达 |

---

## 三、开发环境要求

| 组件 / 工具 | 推荐版本 | 说明 |
| :--- | :--- | :--- |
| **操作系统** | Windows 10/11, macOS, Linux | 跨平台开发（一键脚本目前只覆盖 Windows） |
| **JDK** | OpenJDK 21 LTS | 后端核心运行时（脚本按"环境变量 → PATH → 报错指引"解析） |
| **构建工具** | Apache Maven 3.9+ | 依赖管理与打包 |
| **Flutter** | Flutter 3.x (Dart 3.x) | 移动与多端开发 SDK |
| **容器引擎** | Docker 24+ & Docker Compose v2+ | 中间件本地编排（后端测试用 Testcontainers，也需要可用的 Docker） |
| **数据库** | PostgreSQL 16 | 核心业务数据库（开发映射到宿主 15435） |
| **缓存** | Redis 7.4 | 缓存、限流、验证码、分布式锁（**已启用口令**） |
| **对象存储** | MinIO `RELEASE.2024-10-13T13-34-11Z` | 兼容 S3 的私有存储（镜像 tag 固定，不用 latest） |

---

## 四、启动方式

### 1. 基础设施启动（Docker Compose）

```bash
cp .env.example .env     # 按注释替换 CHANGE_ME_* 占位值（至少填好数据库/Redis/MinIO 口令与 JWT_SECRET）
docker compose up -d
docker compose ps
```

服务与端口（**均只绑定 127.0.0.1**，局域网/公网不可达）：

| 服务 | 地址 | 说明 |
| :--- | :--- | :--- |
| PostgreSQL 16 | `127.0.0.1:15435` | 库名 `campustrade`，schema `campus_trade`。宿主 5432 常被本机已有服务占用，故开发映射为 15435；`SPRING_DATASOURCE_PORT` 必须与 `POSTGRES_PORT` 一致 |
| Redis 7 | `127.0.0.1:6379` | 需口令：`REDIS_PASSWORD`（容器 `--requirepass`）与后端 `SPRING_DATA_REDIS_PASSWORD` 必须一致 |
| MinIO S3 API | `127.0.0.1:9000` | 桶名 `campustrade` |
| MinIO 控制台 | `http://127.0.0.1:9001` | 账号/口令见 `.env`（`MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD`） |

> 端口冲突时改 `.env` 里的 `POSTGRES_PORT` / `REDIS_PORT` / `MINIO_PORT` / `MINIO_CONSOLE_PORT`，
> 同时把 `SPRING_DATASOURCE_PORT` / `SPRING_DATA_REDIS_PORT` / `MINIO_ENDPOINT` / `MINIO_URL_PREFIX` 改成对应值。

### 2. 后端服务启动

```bash
cd backend
mvn -B test              # 255 项；中间件由 Testcontainers 现拉现用，不碰开发库（需要可用的 Docker）
mvn spring-boot:run      # 或双击 backend/run-backend.cmd（自动加载项目根目录 .env）
```
后端监听 `http://127.0.0.1:8080`，上下文路径 `/api`。
首次启动会由 Flyway 从空库执行全部迁移（V1..V12）建出完整结构（详见第五节）。

### 3. 前端应用启动

```bash
cd frontend
flutter pub get
flutter analyze          # 期望 0 issue
flutter test             # 176 项
flutter run -d chrome    # 或双击 frontend/run-frontend.cmd run -d chrome
```
Web 端 API 基址默认 `http://127.0.0.1:8080/api`（`lib/config/app_config.dart`），
用 `--dart-define=API_BASE_URL=...` 覆盖；Web 部署（SPA 回退、Nginx、缓存）见 [frontend/README.md](../frontend/README.md)。

---

## 五、数据库与迁移

### 5.1 单一真相源

- **业务表结构唯一来源**：`backend/src/main/resources/db/migration/V1..V12__*.sql`。
- `docker/postgres/init.sql`：只做 `CREATE SCHEMA IF NOT EXISTS campus_trade`、授权与默认 `search_path`，
  **不含任何业务建表语句**。它仅在数据卷首次初始化时执行一次；删掉它，Flyway 也会自行创建 schema。
  历史上这里曾复制过一份与 Flyway 重复的建表语句，已随阶段 8 移除（重复定义必然漂移）。
- 生产 profile `spring.flyway.baseline-on-migrate: false`：库结构来路不明时启动失败，而不是自动打基线。

### 5.2 迁移脚本

| 版本 | 内容 |
| :--- | :--- |
| V1 | 用户与认证（`user`、`campus_school`、`student_verify`、`user_credit`） |
| V2 | 商品与分类（`category`、`goods`、`goods_image`、`goods_tag`） |
| V3 | 互动（`favorite`、`browse_history`、`search_history`） |
| V4 | 订单（`trade_order` 及状态索引、活跃单唯一索引） |
| V5 | 信用体系升级（`user_credit` 扩充字段、`user_credit_log` 幂等流水） |
| V6 | 评价领域（`review`） |
| V7 | 平台治理（`report`、`admin_audit_log`） |
| V8 | 评价点赞（`review_like`） |
| V9 | 新增高校（广西师范大学）种子数据 |
| V10 | 数据一致性约束加固 |
| V11 | 性能索引 |
| V12 | 校园邮箱唯一性（`SUCCESS` 部分唯一索引）与 12 条补齐外键（均 `NOT VALID`） |

> 空库验证结论（阶段 8 实测，当时迁移到 V11）：在独立 compose project + 独立端口的全新实例上，
> Flyway 从 0 张表一路执行到 V11，`flyway_schema_history` 记录 V1..V11 **全部 success**，
> 建出 18 张表（17 张业务表 + 迁移历史表）与 63 个索引，随后 `GET /api/school/list` 返回 200。
> 批次 1 新增的 V12（`student_verify` 部分唯一索引 + 12 条 `NOT VALID` 外键）同样在 Testcontainers
> 全新库与开发库上实测 success；`NOT VALID` 的语义与历史孤儿行收口步骤见根 README 的
> 「约束与历史脏数据」与「迁移后的数据清理」两节。

---

## 六、生产交付

| 交付物 | 说明 |
| :--- | :--- |
| `backend/Dockerfile` | 多阶段构建（maven + JDK 21 → JRE 21 alpine）；非 root（uid 10001）；`EXPOSE 8080`；内置 `HEALTHCHECK` 探活 `/api/school/list`；不含任何凭据 |
| `docker-compose.prod.yml` | app + postgres + redis + minio；`depends_on: service_healthy`；中间件不发布端口；镜像 tag 固定；日志轮转与资源上限；Redis 强制口令；镜像 `campustrade-backend:<tag>` |
| 生产 profile | `application-prod.yml` + `ProdSecretsGuard`：数据库/Redis/MinIO/CORS 缺项即拒绝启动；日志固定 INFO 且关闭 MyBatis SQL 打印；连接池与超时收敛 |
| 前端 | `flutter build web` + SPA 回退部署说明见 `frontend/README.md` |

部署步骤摘要见根 [README.md](../README.md) 的"生产部署步骤"一节，
变量清单、运维建议（`random_page_cost=1.1`、备份机制缺失）也在该节。

---

## 七、项目目录说明

```
CampusTrade/
├── backend/                            # Spring Boot 3 + Java 21 后端
│   ├── Dockerfile                      # 生产镜像（多阶段、非 root、健康检查）
│   ├── .dockerignore                   # 构建上下文裁剪（排除 target/、logs/）
│   ├── run-backend.cmd                 # 本地启动（ASCII；加载 .env、解析工具链）
│   ├── resolve-toolchain.cmd           # JDK/Maven 解析（可单独运行诊断）
│   ├── check-port.cmd                  # 8080 占用者诊断
│   ├── docs/                           # 后端模块级说明
│   ├── logs/                           # 运行日志（logs/campustrade.log，[DEV-ONLY] 验证码在此）
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/campustrade/
│       │   ├── common/                 # Result / ResultCode / 常量（含 RedisKeyConstants）/ 工具
│       │   ├── config/                 # MyBatis-Plus、Redis、MinIO、Jackson、WebMvc、
│       │   │                           # DeepSeek、VerifyProperties、VerifyMailProdGuard、ProdSecretsGuard
│       │   ├── controller/             # 17 个 REST 控制器（auth/user/student/goods/category/order/
│       │   │                           # review/favorite/history/report/admin/ai/file/school）
│       │   ├── dto/                    # 请求 DTO（按领域分包：order/ report/ review/）
│       │   ├── entity/                 # 18 个实体（与 Flyway 表一一对应）
│       │   ├── enums/                  # 领域枚举（订单/信用/举报/评价/商品状态）
│       │   ├── event/ listener/        # 领域事件与监听（评价 -> 信用变动）
│       │   ├── exception/              # BusinessException / OrderBusinessException / 全局异常处理
│       │   ├── mapper/                 # MyBatis-Plus Mapper（含 GoodsSqlProvider）
│       │   ├── security/               # Spring Security 配置、JWT 过滤器与 Provider
│       │   ├── service/                # 业务接口与实现（含 ai/ 子包与定时任务）
│       │   └── vo/                     # 视图对象
│       ├── main/resources/
│       │   ├── application.yml          # 基础配置（本地开发默认值）
│       │   ├── application-prod.yml     # 生产覆盖（敏感项无默认值、日志收敛）
│       │   ├── logback-spring.xml        # 控制台 + 按天滚动文件（UTF-8）
│       │   ├── META-INF/spring.factories # 注册生产敏感配置守卫（EnvironmentPostProcessor）
│       │   └── db/migration/V1..V12__*.sql # 建表唯一真相源
│       └── test/
│           ├── java/com/campustrade/    # 测试用例 + support/（Testcontainers 装配）
│           └── resources/               # application-test.yml、spring.factories 等
├── frontend/                           # Flutter 3 多端前端
│   ├── README.md                       # 前端说明 + Web 部署（flutter build web / SPA 回退）
│   ├── run-frontend.cmd                # 启动转发（ASCII；解析 FLUTTER_ROOT/PATH）
│   ├── lib/
│   │   ├── api/                        # Dio 封装与各领域 API 定义
│   │   ├── config/                     # AppConfig（API 基址、分页常量等）
│   │   ├── models/                     # 数据模型
│   │   ├── pages/                      # 页面（首页/搜索/详情/发布/订单/消息/个人中心/认证…）
│   │   ├── routes/                     # GetX 路由
│   │   ├── services/                   # 网络、存储、登录态等全局服务
│   │   ├── utils/ widgets/             # 工具与通用组件
│   │   └── main.dart
│   ├── test/                           # Widget/单元测试（176 项）
│   └── web/                            # Web 入口（index.html、manifest、icons）
├── docker/
│   └── postgres/init.sql               # 仅 CREATE SCHEMA + 授权（无业务建表语句）
├── docs/                               # 设计与阶段报告
│   ├── README.md                       # 本文件
│   ├── final-audit/                    # 阶段终审报告（含"历史报告（已过时）"抬头）+ 终审修复报告
│   └── stage3*/ stage4/ stage5/ stage6/ stage7/ stage8/ # 各阶段过程报告
├── scripts/
│   ├── toolchain.ps1                   # 工具链解析（环境变量 → PATH → 报错指引）
│   └── quality-gate.ps1                # 质量门禁唯一入口（Docker 前置检查 + 后端测试 + 前端分析 + 前端测试）
├── .env.example                        # 环境变量模板（占位符，无可用凭据）
├── .env.tools                          # （本机私有，已 git-ignore）工具链路径
├── CHANGELOG.md                        # 变更历史（按时间倒序，含各阶段日期与提交短 hash）
├── CONTRIBUTING.md                     # 参与开发约定（环境 / 门禁 / 编码 / 迁移 / 提交风格）
├── docker-compose.yml                  # 本地开发编排（127.0.0.1 + Redis 口令 + 日志轮转）
├── docker-compose.prod.yml             # 生产编排（无中间件端口 + healthcheck 依赖 + 固定 tag）
├── start.bat / start.ps1               # 一键启动（.bat 纯 ASCII 转发器）
├── stop.bat / stop.ps1                 # 一键停止
└── README.md                           # 项目根说明（快速开始 / 端口 / 变量清单 / 门禁 / 生产部署）
```

---

### 7.1 阶段报告索引

| 阶段 | 目录 | 主要报告 | 提交 |
| :--- | :--- | :--- | :--- |
| Stage 1 | （见 `final-audit/`） | 工程地基与可观测性 | `769aca3` |
| Stage 2 | （见 `final-audit/`） | 认证与令牌安全加固 | `918c503` |
| Stage 3 | `stage3_*_report.md` | 校园认证可信化（A–F 系列报告） | `24edcb9` |
| Stage 4 | `stage4/` | 数据一致性与不变式（A / B1 / B2 / C / D 系列 + 领域设计） | `3b7891a` |
| Stage 5 | `stage5/` | 接口契约与领域模型收敛（A–F 系列） | `7250889` |
| Stage 6 | `stage6/` | 前端稳定性与错误处理（A–D 系列） | `a9ea6a2` |
| Stage 7 | `stage7/` | 性能优化：索引、检索转义、N+1 消除、前端体验 | `51ff667` |
| Stage 8 | `stage8/` | 生产交付与文档校正 | `0669c3c` |
| 终审 | `final-audit/` | 7 份审计报告（均带"历史报告，结论已过时"抬头）+ 终审修复 / 批次 1 / 批次 2 报告 | `45ce200` / `afa595d` / `d740cb7` |

> 逐条变更、验证结论与测试项数变化见根目录 [CHANGELOG.md](../CHANGELOG.md)。

---

## 八、质量门禁

```bash
# 一键：后端测试 + 前端 analyze + 前端 test（任一失败即非 0 退出）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/quality-gate.ps1
.\start.ps1 -Mode 6     # 同一入口（选项 6 直接调用上面的脚本）

# 单项（不需要 Docker）：-Only analyze / -Only flutter-test
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/quality-gate.ps1 -Only analyze

# CI：.github/workflows/ci.yml（push/PR 触发，后端 + 前端两个 job；命令与本地门禁一致）
```

门禁在跑测试前会先检查 Docker（后端测试的 PostgreSQL / Redis / MinIO 由 Testcontainers 现拉现用），
不可用时打印中文原因与启动方法并以非 0 退出；因此 `JWT_SECRET` 等环境变量既不需要在本地导出，
也不需要在 CI 注入（测试自带随机密钥，见 `TestContainersConfig`）。

**基线（批次 2 实测）**：后端 `mvn -B test` **255 项**全绿、`flutter analyze` 0 issue、`flutter test` **176 项**全绿。
（阶段 8 交付时为 242 / 142；测试项数变化见 [CHANGELOG.md](../CHANGELOG.md)。）
