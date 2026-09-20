# 参与开发（CONTRIBUTING）

本文说明在本仓库开发、验证、提交代码时需要遵守的约定。**提交前请确保质量门禁全绿**，
并把"改了什么、怎么验证的"写进提交信息（本项目的历史提交就是这个风格）。

- 项目现状与运维：[README.md](README.md)
- 变更历史与各阶段验证结论：[CHANGELOG.md](CHANGELOG.md)
- 架构与设计文档：[docs/README.md](docs/README.md)

---

## 一、本地环境准备

| 组件 | 版本要求 | 说明 |
| :--- | :--- | :--- |
| JDK | **OpenJDK 21 LTS** | 后端运行时；低于 21 会在编译期报一堆难懂的错误 |
| Maven | 3.9+ | 后端构建与测试 |
| Flutter | 3.x（Dart 3.x） | 前端分析与测试 |
| Docker | 24+ 与 Compose v2+ | 本地中间件编排；**后端测试也需要**（Testcontainers） |
| PostgreSQL 16 / Redis 7.4 / MinIO | 见 `docker-compose.yml` | 开发中间件（镜像 tag 已固定到补丁版本） |

### 1. 工具链解析（没有硬编码盘符）

脚本按固定顺序解析 JDK / Maven / Flutter / Docker：**环境变量 → 系统 PATH → 中文报错指引**。
希望持久化本机路径（不动系统环境变量）时，在项目根目录创建 `.env.tools`（已 git-ignore，**纯 ASCII**）：

```
JAVA_HOME=D:\tools\jdk21
MAVEN_HOME=D:\tools\maven
FLUTTER_ROOT=D:\tools\flutter
```

真实环境变量优先级更高；`=` 两侧不要留空格。可用 `backend\resolve-toolchain.cmd` 查看当前解析结果。

### 2. 中间件与运行

```bash
cp .env.example .env      # 按注释替换 CHANGE_ME_* 占位值（.env 已 git-ignore，绝不提交）
docker compose up -d      # PostgreSQL 16 / Redis 7 / MinIO，均只绑 127.0.0.1
cd backend  && mvn spring-boot:run     # 或双击 backend/run-backend.cmd（自动加载 .env）
cd frontend && flutter run -d chrome   # API 基址默认 http://127.0.0.1:8080/api
```

Windows 上等价的一键方式：`.\start.bat`（完整启动）与 `.\stop.bat`（停止）。

> **不要**删除或重建 `campustrade*` 数据卷，也不要随意 `docker compose down -v`：
> 开发卷里是你自己的数据。后端测试用 Testcontainers 的临时容器，不会碰开发卷。

---

## 二、质量门禁（唯一入口）

```bash
# 一键：后端测试 + 前端静态分析 + 前端测试（任一失败即非 0 退出）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/quality-gate.ps1
.\start.ps1 -Mode 6                  # 同一入口（选项 6 直接调用上面的脚本）

# 单项
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\quality-gate.ps1 -Only backend
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\quality-gate.ps1 -Only analyze
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\quality-gate.ps1 -Only flutter-test
```

- **门禁会先检查 Docker**：后端测试的 PostgreSQL / Redis / MinIO 由 Testcontainers 在测试 JVM 内
  现拉现用，Docker 不可用时会打印中文原因与启动方法并 `exit 1`，不会让英文异常抛进日志。
  只跑前端两项（`-Only analyze` / `-Only flutter-test`）不需要 Docker。
- **CI 与本地一致**：`.github/workflows/ci.yml` 跑的就是同样的命令；CI 不注入 `JWT_SECRET`、
  也不需要 `services`（测试自带随机密钥与临时中间件）。
- **不要**在测试里依赖本机 `.env`：门禁刻意不加载 `.env`（否则测试会去给"没设口令的临时 Redis"发 AUTH）。

**当前基线**：后端 `mvn -B test` **255 项**全绿、`flutter analyze` **0 issue**、`flutter test` **176 项**全绿。
任何"只是改文档/脚本"的改动同样要跑一遍门禁确认没有连带影响。

---

## 三、编码与文件编码约定

| 文件类型 | 编码要求 | 原因 |
| :--- | :--- | :--- |
| `.bat` / `.cmd` | **纯 ASCII**（中文一律不写） | cmd.exe 按控制台代码页（GBK/936）解析；UTF-8 中文会吞掉引号、错位行，命令静默失效 |
| `.ps1` | **UTF-8 with BOM** | PowerShell 5.1 依赖 BOM 才能正确读取中文；无 BOM 的中文会乱码 |
| `.java` / `.sql` / `.yml` / `.md` / `.dart` | UTF-8（不加 BOM） | 常规源码与文档 |
| `.env` / `.env.example` / `.env.tools` | **纯 ASCII** | cmd 逐行解析；UTF-8 中文注释的尾字节会吃掉行尾换行，导致下一行配置被静默跳过 |

其它约定：

- 中文提示**只写在 `.ps1` 与文档里**；`.bat` / `.cmd` 只做纯 ASCII 转发器；
- 脚本里**不要写死盘符**：走 `scripts/toolchain.ps1` 的解析函数；
- 脚本里每个外部命令（`docker` / `mvn` / `flutter`）都要**检查退出码**，失败时打印中文原因并非 0 退出；
- 脚本**不回显任何口令**（数据库 / Redis / MinIO 一律提示"见 .env"）；
- 异步/并发逻辑的注释要写清"为什么这样做"（例如：为什么 `NOT VALID`、为什么不做整行回写），
  便于后来者判断能否改动。

---

## 四、数据库与迁移约定

1. **已应用的迁移是只读的**：`backend/src/main/resources/db/migration/V1..V12` 一律**不得修改**——
   改注释也会改变文件 checksum，导致 Flyway 校验失败、应用起不来。需要补的说明写进 README / docs。
2. **新增迁移只能往后编号**（如 V13__xxx.sql），并满足：
   - 可重复执行（`IF EXISTS` / `IF NOT EXISTS` / `DO` 块判类型）；
   - 文件头注释写清：目的、锁与耗时、可重复执行性、对既有数据的影响；
   - **绝不删改业务数据**（历史脏数据由人工按脚本处理，不由迁移替用户决定）。
3. **`/db/migration` 是 schema 的唯一真相源**：`docker/postgres/init.sql` 只负责 `CREATE SCHEMA` 与授权，
   不要在 `init.sql` 里复制建表语句（一份定义两处维护必然漂移）。
4. **约束补历史数据用 `NOT VALID`**：`ADD CONSTRAINT ... FOREIGN KEY`（不带 `NOT VALID`）会全表校验并持
   `ACCESS EXCLUSIVE` 锁；历史可能已有孤儿行时用 `NOT VALID`（只约束新行），收口步骤走
   `backend/docs/data-cleanup-orphans.sql` 的"盘点 → 备份 → 按业务取舍 → `VALIDATE CONSTRAINT`"。
5. **测试用的中间件是临时的**：镜像 tag 固定并与 `docker-compose.yml` 保持一致
   （`postgres:16.15` / `redis:7.4.11` / `minio/minio:RELEASE.2024-10-13T13-34-11Z`），
   在 `backend/src/test/java/com/campustrade/support/TestContainersConfig.java` 中统一维护。
6. **注释不要写死迁移版本区间**（例如"V1..V9 负责建表"）：迁移会持续增加，写死必然过时，
   改成"由 db/migration 下的版本化迁移负责"。

---

## 五、测试约定

- **后端**：`cd backend && mvn -B test`。
  - 新增用例放在 `backend/src/test/java/com/campustrade/` 下，命名与既有文件风格一致
    （`CampusTradeStageN*Tests`）；并发、N+1、幂等这类"难复现"的问题优先写成可回归的断言
    （如 SQL 条数 `assertTrue(statements <= 8)`）；
  - **凭据一律运行时生成**：不要写任何可用密钥 / 口令字面量（源码会被安全门禁扫描）；
    测试的 `jwt.secret`、容器口令由 `TestContainersConfig` / `TestCredentials` 提供；
  - 测试不得读写开发库或开发 Redis（走 Testcontainers）。
- **前端**：`cd frontend && flutter analyze && flutter test`。
  - 修 bug 时优先补一个能复现的 widget / 单元测试（本项目"错误态与空态分离""昵称回退"等就是这么做回归的）；
  - `integration_test/` 下的冒烟测试不被 `flutter test` 收集，需要 chromedriver 与可用的 8080 后端，
    改动它时请在文件头写清运行前提。
- **新增依赖/工具后**：确认门禁命令没有变化（`CI 命令 = 本地门禁命令`），必要时同步更新
  `README.md`、`docs/README.md` 与 `CHANGELOG.md`。

---

## 六、提交信息风格

沿用现有历史的风格：`<类型>(<范围>): <一句话标题>` + 正文分点写"改了什么 / 为什么 / 怎么验证的"。

- 类型：`feat` / `fix` / `perf` / `refactor` / `chore` / `docs`；范围用阶段或批次（如 `阶段7`、`批次1`、`终审修复`）；
- 标题与正文用中文，正文里**给出实测数据**（测试项数、`EXPLAIN` 结果、并发实测、日志摘录），
  不要只写"优化了性能"；
- 与实测不符的结论宁可不写；未验证的功能明确标注"未验证 / 未能真实运行"（历史提交就是这样处理的）；
- 一个提交尽量只做一件事；大范围改动（如阶段加固）在正文里按"安全 / 数据一致性 / 工程整洁"分组；
- **绝不提交**：`.env`、`.env.tools`、任何真实口令或密钥、`target/`、`build/`、日志文件、IDE 本地配置。

## 七、文档约定

- 阶段报告放 `docs/stageN/`，并在 `docs/README.md` 的「阶段报告索引」登记；
- 在 `CHANGELOG.md` 顶部追加一条（日期 + 提交短 hash + 主要变更 + 验证结论）；
- `docs/final-audit/` 下的历史审计报告**正文保持原样**（它们是当时的快照），如需说明已过时，
  在抬头加"历史报告（结论已过时）"横幅，并把"当前状态"指向 README / CHANGELOG；
- 文档里的示例凭据一律用占位符（`CHANGE_ME_*`、`<强口令>`），不要出现任何可用口令；
- 本仓库当前**未声明 LICENSE**：是否添加授权文件属于项目所有者的决定，请勿自行添加。
