# CampusTrade 校园二手交易平台 Stage 8 完成报告

> **阶段名称**：Stage 8：生产交付与文档校正
> **编制日期**：2026-09-20
> **对应提交**：`0669c3c` `chore(阶段8): 生产交付与文档校正`
> **所属版本**：CampusTrade Backend v0.0.1-SNAPSHOT（Java 21 / Spring Boot 3.3.4 / PostgreSQL 16 / Redis 7）
> **状态**：已完成（COMPLETED）
> **自动化测试结果**：后端 `mvn -B test` **242/242 全绿**；前端 `flutter analyze` **0 issue**、`flutter test` **142/142 全绿**。
> **核心产出**：生产编排与镜像（`backend/Dockerfile`、`docker-compose.prod.yml`）、`ProdSecretsGuard` 启动期
> 敏感项 fail-fast、开发编排加固（中间件只绑回环、Redis 强制口令、tag 固定）、脚本退出码与工具链解析统一、
> `init.sql` 去重（schema 单一真相源）、README / docs 全面校正。

---

## 一、阶段目标

把"能跑通"推进到"能交付"：补齐生产部署所需的配置隔离、镜像、编排、启动期守卫，
并把此前文档中与实现不一致的地方（阶段、端口、变量、门禁命令、已不存在的接口文档页）全部校正到与代码一致。

## 二、配置与环境隔离

| 变更 | 内容 |
| :--- | :--- |
| `.env.example` | 所有凭据改为 `CHANGE_ME_*` **占位符**，不含任何可用口令（安全门禁扫描硬编码凭据） |
| `backend/src/main/resources/application.yml` | 清空数据库口令 / MinIO secret 的运行期默认值：缺失即报错，而不是"回落弱默认值" |
| `application-prod.yml` | 重写：敏感项无默认值、Hikari / Redis 超时收敛、`spring.flyway.baseline-on-migrate=false`（来路不明的库宁可启动失败，也不自动打基线）、优雅停机（`server.shutdown=graceful` + 30s）、日志固定 INFO 且关闭 MyBatis SQL 打印 |
| `ProdSecretsGuard`（`EnvironmentPostProcessor`） | 在 Flyway 之前校验 14 个必填环境变量、拒绝占位值、拒绝 CORS 通配；缺失即**中文报错拒绝启动** |

## 三、生产交付物

| 交付物 | 要点 |
| :--- | :--- |
| `backend/Dockerfile` | 多阶段（maven + JDK 21 → JRE 21 alpine）；非 root（uid 10001）；`EXPOSE 8080`；内置 `HEALTHCHECK` 探活 `/api/school/list`；镜像内**无任何**凭据 / `.env` |
| `backend/.dockerignore` | 构建上下文只保留 `pom.xml` 与 `src/`（排除 `target/`、`logs/`、`.mimosa/` 等） |
| `docker-compose.prod.yml` | 独立 project name `campustrade-prod`（避免误 down 掉开发栈）；中间件**零端口发布**；`depends_on: service_healthy`；tag 全固定；日志轮转 + 资源上限；Redis 强制口令；app 只读根文件系统 + `tmpfs` + `no-new-privileges` |
| `docker-compose.yml`（开发） | 中间件仅绑 `127.0.0.1`；Redis 启用口令；镜像 tag 固定；日志轮转与资源上限 |
| `frontend/README.md` | `flutter build web`、`--dart-define` 注入 API 基址、Nginx SPA 回退、缓存与排障 |

## 四、脚本与 schema

- **脚本退出码**：所有外部命令（`docker compose` / `mvn` / `flutter`）都检查退出码，失败即中文提示 + 非 0 退出；
  选项 6（跑测试）按测试结果决定退出码，且不再打印"启动完成导航"（顺带修掉 PowerShell 未消费管道输出导致 `exit 0` 的真实缺陷）；
- **工具链解析**：统一为「环境变量 → 系统 PATH → 中文报错指引」（新增 `scripts/toolchain.ps1` 与 `backend/resolve-toolchain.cmd`），
  删除硬编码盘符；脚本不再回显数据库 / MinIO 明文口令；
- **Schema 单一真相源**：`docker/postgres/init.sql` 只保留 `CREATE SCHEMA` 与授权，
  删除 `docker/postgres/stage1_tables.sql` / `stage2_tables.sql` 等与 Flyway 重复的建表副本
  （同一张表两份定义必然漂移）。

## 五、文档校正

- 根 `README.md` 与 `docs/README.md` 全面校正：阶段状态、端口（开发库映射 15435）、环境变量清单、
  门禁命令、生产部署步骤、备份与 `random_page_cost` 建议；
- 移除不存在的 Swagger 在线文档死链（**未**引入 `springdoc`：不为"文档页"新增未请求依赖与暴露面）。

## 六、验证证据（本阶段实测）

| 验证项 | 结论 |
| :--- | :--- |
| 空库迁移 | 独立 compose project + 独立端口的全新实例：Flyway 从 0 张表执行到 V11 全部 `success`，建出 18 张表与 63 个索引，`GET /api/school/list` 返回 200 |
| 用户卷安全 | 全流程前后用户数据卷逐字节未变（验证用独立 project 与独立卷） |
| 生产栈实跑 | prod 编排实际跑通：容器内非 root、根文件系统只读、中间件零端口发布 |
| 缺变量 fail-fast | 缺少敏感环境变量时 `docker compose ... up -d` / 应用启动**非 0 退出**并给出中文原因 |
| 脚本退出码 | 选项 6 在测试失败时 `exit 1` 且不打印成功横幅 |
| 质量门禁 | 后端 242 项、前端 analyze 0 issue + 142 项 |

## 七、后续批次承接（诚实说明）

本阶段交付时迁移到 V11、测试基线为 242 / 142。此后：

- **终审修复**（`45ce200`）补掉资料回写覆盖冻结状态、订单详情串单等问题，并给 7 份修复前审计报告加"历史报告"抬头；
- **批次 1**（`afa595d`）新增 **V12**（`student_verify` 部分唯一索引 + 12 条 `NOT VALID` 外键）、
  可信代理解析、AI / 校园邮箱配额与 token 端点限流、上传加固，后端测试升至 255；
- **批次 2**（`d740cb7`）前端控制器作用域 / CancelToken / 页面守卫 / AppLogger，前端测试升至 176；
- 本阶段遗留的三项工程化问题（门禁缺 Docker 前置检查、CI 有一步空转的 `JWT_SECRET` 生成、
  镜像 tag 可复现性与 digest 记录、CHANGELOG / CONTRIBUTING / stage7、stage8 报告缺失）
  已在本批次处理，详见 [CHANGELOG.md](../../CHANGELOG.md)。

当前状态以根 `README.md`、「当前状态」清单与 `CHANGELOG.md` 为准；本报告描述的是 Stage 8 交付时的真实状态。
