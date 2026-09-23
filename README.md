# CampusTrade 校园二手交易平台

> 面向高校大学生的校园二手闲置交易平台，打造安全、便捷、绿色流转的校园数字集市。

---

## 快速导航

- **项目阶段**：Stage 8（生产交付与文档校正）已完成 —— 阶段 1–8 + 终审修复 + 批次 1/2 收尾全部落地
- **变更历史**：[CHANGELOG.md](CHANGELOG.md)
- **参与开发**：[CONTRIBUTING.md](CONTRIBUTING.md)（环境准备 / 门禁命令 / 编码与迁移约定 / 提交风格）
- **架构文档**：[docs/README.md](docs/README.md)
- **环境配置模板**：[.env.example](.env.example)
- **开发编排**：[docker-compose.yml](docker-compose.yml)（本机开发）
- **生产编排**：[docker-compose.prod.yml](docker-compose.prod.yml)（服务器部署）
- **生产镜像**：[backend/Dockerfile](backend/Dockerfile)
- **前端 Web 部署**：[frontend/README.md](frontend/README.md)
- **安全约定**：用户内容按原文存储、HTML 展示端必须在输出点转义（见下文「安全约定：用户内容的存储与转义」）

---

## 技术栈总览

| 模块 | 技术选型 | 说明 |
| :--- | :--- | :--- |
| **后端框架** | Spring Boot 3.3.4 + Java 21 | 高性能后端企业级开发 |
| **持久层** | MyBatis-Plus 3.5.7 + PostgreSQL 16 | 现代化 ORM 与高可靠开源关系型数据库 |
| **数据库迁移** | Flyway（V1–V12） | 建表结构的**唯一真相源**（见下文"Schema 单一真相源"） |
| **高速缓存** | Redis 7.4（开启 AOF + 强制口令） | 会话、限流、验证码、分布式锁、浏览量计数 |
| **对象存储** | MinIO（RELEASE.2024-10-13T13-34-11Z） | 兼容 AWS S3 的私有图片与文件存储 |
| **前端应用** | Flutter 3.x + Dart 3.x | 跨平台移动 App / Web 客户端 |
| **前端状态** | GetX (状态管理 & 路由体系) | 响应式轻量架构 |
| **网络通信** | Dio | 拦截器、Token 自动刷新与请求封装 |
| **本地存储** | Flutter Secure Storage | 敏感凭证高安全存储 |

---

## 极速起步

### 推荐：一键全自动启动（Windows）
在项目根目录直接**双击**运行或在终端执行（两者等价，`.bat` 只是转发到同名 `.ps1`）：
```powershell
# 批处理脚本（双击即可，按提示回车默认执行 [1] 完整启动）
.\start.bat

# 或 PowerShell 脚本（同一个实现）
.\start.ps1
```

> 脚本化调用（跳过交互提问，便于 CI / 自动化）：
> `.\start.ps1 -Mode 6` 直接跑质量门禁并用其退出码退出。

> 启动器将自动：
> 1. 检查并拉起 Docker（PostgreSQL 16、Redis 7、MinIO）；
> 2. 检查并启动后端 Spring Boot 3 服务（端口取自 `.env` 的 `BACKEND_PORT`，当前 **8081**）：
>    启动前先判断该端口占用情况，能区分「本项目后端」与「其它程序占用」，后者会报出占用者主类
>    与所属工程目录并中止，既不会重复启动也不会误杀别的工程；
> 3. 打开 Chrome 浏览器启动 Flutter Web 前端应用；后端未就绪时会直接中止，不会继续拉起前端；
> 4. 如需关闭所有服务，可双击运行 `stop.bat` 或 `stop.ps1`
>    （若该端口被非本项目进程占用，停止脚本会先询问，默认不动它）。
>
> 启动器**不会**在控制台回显任何口令（MinIO / 数据库 / Redis 口令一律提示"见 .env"）。

### 脚本结构与约定（改脚本前必读）

| 文件 | 作用 | 编码要求 |
| :--- | :--- | :--- |
| `start.bat` / `stop.bat` | 纯 ASCII 转发器，仅负责调用同名 `.ps1` | **必须保持纯 ASCII** |
| `start.ps1` / `stop.ps1` | 启动器 / 停止器的全部逻辑与中文提示 | UTF-8 **带 BOM** |
| `scripts/toolchain.ps1` | 工具链解析（环境变量 → PATH → 报错指引），被上面两个脚本点源加载 | UTF-8 **带 BOM** |
| `scripts/quality-gate.ps1` | 质量门禁唯一入口（后端测试 + 前端分析 + 前端测试），`start.ps1 -Mode 6` 直接调用它 | UTF-8 **带 BOM** |
| `backend/run-backend.cmd` | 加载 `.env` 并启动 Spring Boot，失败时给出明确提示 | **必须纯 ASCII** |
| `backend/resolve-toolchain.cmd` | 解析 JDK 21 / Maven；可单独运行用于诊断本机工具链 | **必须纯 ASCII** |
| `backend/check-port.cmd` | 诊断后端端口占用者（空闲 / 本项目 / 其它程序）；端口缺省取 `.env` 的 `BACKEND_PORT`，也可作为第一个参数传入 | **必须纯 ASCII** |
| `frontend/run-frontend.cmd` | 解析 Flutter 并执行 flutter 命令 | **必须纯 ASCII** |

> **为什么中文不能写进 `.bat` / `.cmd`**：cmd.exe 按控制台代码页（本机为 GBK/936）解析批处理文件，
> 而文件字节是 UTF-8 时会吞掉引号、错位行，命令会静默失效——历史上后端"拉不起来"正是这个原因。
> 中文提示请一律写在 `.ps1` 中，并保持"UTF-8 with BOM"保存（否则 PowerShell 5.1 同样会读错中文）。

> **退出码约定（阶段 8 起）**：脚本里所有外部命令（`docker compose` / `mvn` / `flutter`）都会检查退出码，
> 失败时打印中文原因并**以非 0 退出**；`-Mode 6`（跑测试）只以测试结果决定退出码，
> 且**不再打印"启动完成导航"横幅**（跑测试是验证，不是启动）。

### 工具链路径从哪来（没有硬编码盘符）

脚本按固定顺序解析 JDK / Maven / Flutter，任一步都不再假设安装盘符：
1. **环境变量**：`JAVA_HOME`、`MAVEN_HOME`（或 `M2_HOME`）、`FLUTTER_ROOT`；
2. **系统 PATH**：`java` / `mvn` / `flutter`；
3. **明确报错指引**：打印"缺什么、怎么装、怎么配"，不会用一个猜测的路径继续跑。

如果希望把这台机器的路径持久化（不动系统环境变量），在项目根目录创建 **`.env.tools`**（已 git-ignore，纯 ASCII）：
```
JAVA_HOME=D:\tools\jdk21
MAVEN_HOME=D:\tools\maven
FLUTTER_ROOT=D:\tools\flutter
```
它只是环境变量的本地载体，真实环境变量优先级更高；`=` 两侧不要留空格。
可随时运行 `backend\resolve-toolchain.cmd` 查看当前解析结果。

---

### 手动逐步启动

#### 1. 准备环境变量
```bash
cp .env.example .env
```
然后按需修改 `.env`：**至少要能启动**，`.env.example` 中所有 `CHANGE_ME_*` 都是占位值，按注释替换即可。
（`.env` 已 git-ignore，绝不要把真实口令提交进仓库。）

#### 2. 启动基础设施
```bash
docker compose up -d
```
> 编排要点（与阶段 8 之前不同，请注意）：
> - 中间件端口**只绑定 `127.0.0.1`**：本机可用，局域网/公网连不上（`POSTGRES_PORT=15435`、`REDIS_PORT=6379`、`MINIO_PORT=9000`、`MINIO_CONSOLE_PORT=9001`）；
> - **Redis 已启用口令**（`REDIS_PASSWORD`，容器侧 `--requirepass`，后端侧 `SPRING_DATA_REDIS_PASSWORD`，两者必须一致）；
> - 镜像 tag 固定到具体版本（`postgres:16.15`、`redis:7.4.11`、`minio/minio:RELEASE.2024-10-13T13-34-11Z`），不用 `latest`；
> - 三个服务都配了日志轮转（`max-size: 10m` / `max-file: 3`）与资源上限；
> - 业务表结构**不再由 `docker/postgres/init.sql` 创建**，见下文"Schema 单一真相源"。

#### 3. 启动后端

> **JWT_SECRET 是必填项**：后端不再内置任何默认 JWT 密钥。缺失、长度不足 32 字节、
> 或仍在沿用历史默认密钥时，`JwtTokenProvider` 会在启动期直接拒绝启动并打印中文提示。
> 本地开发用 `backend/run-backend.cmd` 启动即可：它会自动解析项目根目录 `.env`
> （`JWT_SECRET`、`SPRING_*`、`MINIO_*` 等）并导出为进程环境变量。
> 首次使用请执行 `openssl rand -hex 32` 生成密钥并填入 `.env`；
> **生产环境由部署平台注入环境变量 `JWT_SECRET`**，不要写进任何配置文件或镜像。
> 注意：`.env` / `.env.example` 必须保持纯 ASCII 文本 —— cmd 在 GBK 控制台下按行解析，
> UTF-8 中文注释的尾字节会被当成双字节字符并吞掉行尾换行，导致下一行配置被静默跳过。

```bash
cd backend
mvn spring-boot:run
```

> **校园认证（学生身份）的验证码从哪来**：校园认证是**发布商品的硬前置**，也是卖家"已认证"
> 标识的唯一依据，因此验证码**不再由接口返回**，只能从学生校园邮箱获取：
>
> | 场景 | `verify.mail-enabled` | 验证码去向 |
> | :--- | :--- | :--- |
> | 生产（profile `prod`） | `true`（`application-prod.yml` 已固定） | 经 SMTP 真实发送到校园邮箱 |
> | 本地开发（默认 profile） | `false`（默认值） | **仅**写入服务端日志文件 `backend/logs/campustrade.log` 的 `[DEV-ONLY]` 行 |
>
> - `POST /api/student/verify` 的响应 `data` 恒为 `null`，`message` 为「验证码已发送至校园邮箱（5分钟内有效）」；
>   接口路径与字段名均未变化，前端不再自动填充验证码，改为"已发送至 xxx 邮箱 + 60 秒倒计时 + 6 位输入框"；
> - **本地联调取码**：启动后端后执行
>   `Select-String -Path backend\logs\campustrade.log -Pattern '\[DEV-ONLY\]' | Select-Object -Last 1`
>   （或直接打开该日志文件搜索 `[DEV-ONLY]`）；除该行外，验证码不会出现在任何响应体或日志中；
> - **邮件发送失败**时接口返回明确业务错误（不降级为把验证码写进日志或响应），学生需重新发送；
> - **生产 fail-fast**：prod profile 下若 `verify.mail-enabled=false` 或 `MAIL_*` 缺项，
>   `VerifyMailProdGuard` 会在启动期直接拒绝启动并打印中文提示（与 JWT 密钥 fail-fast 同风格）；
>   生产必须由部署平台注入环境变量：
>   `MAIL_HOST`（SMTP 主机）、`MAIL_PORT`（465/587）、`MAIL_USERNAME`、`MAIL_PASSWORD`、
>   `MAIL_FROM`（发件人地址），587 端口另需 `MAIL_SSL_ENABLED=false`（改用 STARTTLS）；
> - **限流与失败锁定**（Redis 计数，键名与 TTL 见 `RedisKeyConstants`）：
>   同一校园邮箱 24 小时内最多发送 5 次；同一用户 10 分钟内最多发送 3 次；
>   同一验证码核验失败累计 5 次即作废（必须重新发送）。超限返回 429 语义的明确业务错误。

#### 3.1 没有学校邮箱时怎么演示校园认证（演示模式）

个人开发者拿不到真实校园邮箱，上面那条"验证码只走邮件"的链路就演示不了：
邮件发到了自己收不到的地址，`/student/verify` 的 `data` 又是 `null`，流程必然卡在输码这一步。
为此提供一条**只对白名单邮箱生效**的演示通道。

| 配置 | 默认 | 作用 |
| :--- | :--- | :--- |
| `VERIFY_DEMO_MODE_ENABLED` | `false` | 演示模式总开关；单独打开无效，必须同时配置白名单 |
| `VERIFY_DEMO_EMAILS` | 空 | 逗号分隔的邮箱白名单，**只有这些邮箱**走演示通道 |

开启后，白名单邮箱的行为变成：**不发真实邮件**，验证码随 `POST /api/student/verify` 的响应返回
（`data: {"demoMode": true, "demoCode": "123456"}`），前端自动填入验证码并把页面提示条换成
「演示模式：当前环境未发送真实邮件……直接点击"完成认证"即可」。白名单之外的邮箱一个字节都不变
（后缀校验、双向限流、真实邮件、发送失败不降级），因此这项开关的影响面是被钉死的。

演示通道对**白名单邮箱**还额外放开两件事，都是"演示"这个场景的硬需求，且都只对白名单生效：

| 放开的事 | 为什么必须放开 |
| :--- | :--- |
| **跳过发送限流**（原本同一用户 10 分钟 3 次、同一邮箱 24 小时 3 次） | 限流的两个理由在演示通道都不成立：那台环境根本不发信（不存在"打满他人邮箱额度 / 投递垃圾邮件"）。不放开就会出现"演示到第 4 次被挡住，等 10 分钟才能继续"，同一邮箱 24 小时内更是只能用 3 次 |
| **允许重复演示**（同一个演示邮箱已认证成功后，再次申请会把该行重置回待认证） | 否则同一个邮箱只能演示一次：第二轮核销会报「该校园邮箱已完成认证，无需重复核销」。而演示要看的恰恰是"未认证 → 已认证"这次状态跃迁。重置只作用于调用者自己名下的记录 |

> 这两条都**只**作用于 `VERIFY_DEMO_EMAILS` 点名的邮箱。真实邮箱的限流、以及"已认证的行不允许被
> 申请动作回退"（`SUCCESS` 只能由核销写入，避免认证被自己撤销）都原样保留——回归测试里有专门
> 的用例守着这两条边界。

**演示步骤**（本地或部署机都适用；部署机加在 `.env` 后重启后端即可）：

```bash
# 1) 打开演示模式，并把演示邮箱写进白名单（用看起来就不像真人的地址）
VERIFY_DEMO_MODE_ENABLED=true
VERIFY_DEMO_EMAILS=demo@gxnun.edu.cn
```

2) 用任意账号登录 → 个人中心 → 「校园身份认证」；
3) 选择高校（示例用**广西民族师范学院**，后缀 `@gxnun.edu.cn`）→ 学号随意填，例如 `253012020445`；
4) 校园邮箱填白名单里的那个（默认会带出 `student@gxnun.edu.cn` 占位串，改掉 `student` 部分）；
5) 点「获取邮箱验证码」→ 页面提示条变成「演示模式」，验证码**已自动填入** → 点「完成认证」；
6) 认证成功后即可返回「发布新商品」——校园认证正是它的硬前置，这一步能直观看出闸门生效。

**部署机（compose）上的开启方式**：这两个变量加进 compose 用的 env 文件后**重建后端容器**；
前端页面负责"自动填入 + 演示模式提示条"，所以**前端产物也要一起重新部署**，否则接口虽然把验证码
带回来了，旧页面仍然只会显示"已发送至邮箱"，演示者无从下手。

```bash
# 后端：带演示模式变量重建并重启 app 服务（服务名 app，见 docker-compose.prod.yml；
# 若按本文件前面的做法用 --env-file 启动，就先把两个变量写进那个 env 文件）
VERIFY_DEMO_MODE_ENABLED=true VERIFY_DEMO_EMAILS=demo@gxnun.edu.cn \
  docker compose -f docker-compose.prod.yml up -d app

# 前端：重新构建 Web 产物并发布（API 基址按实际域名替换）
cd frontend && flutter build web --dart-define=API_BASE_URL=https://app.example.com/api
```

**演示时必须知道的三件事**：

- **白名单邮箱必须与所选学校的后缀匹配**。后缀校验在演示模式下**不会**被跳过（那正是要演示的东西之一），
  所以别填 QQ 邮箱；填 `demo@gxnun.edu.cn` 这类"后缀对、人不存在"的地址即可；
- **如果页面上出现「验证码请求过于频繁，请 10 分钟后再试」**：那是**旧版本**的限流计数（演示模式上线前
  点出来的）。等 10 分钟即可；也可以直接清掉计数键（键名形如 `student:verify:send:user:{userId}`）：
  ```bash
  # 先列出计数键，再把列出的键名逐个 DEL（通常只有 1~2 个）
  docker compose -f docker-compose.prod.yml exec -T redis sh -lc \
    'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning --scan --pattern "student:verify:send:*"'
  docker compose -f docker-compose.prod.yml exec -T redis sh -lc \
    'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning DEL "student:verify:send:user:123"'
  ```
  新版对白名单邮箱根本不再计数，所以只要部署到新版就不会再撞这个提示。
- **同一个校园邮箱只能被一个账号认证成功**（V12 部分唯一索引，防一人多号）。用同一个账号重复演示不受
  影响（见上面"允许重复演示"）；但如果换账号演示同一个邮箱，第二轮会报「该校园邮箱已被其他账号完成认证」，
  这时要么换前缀（`demo1@`、`demo2@`…，注意白名单是精确匹配，新地址要加进 `VERIFY_DEMO_EMAILS`），
  要么先清掉上一轮记录：
  ```sql
  DELETE FROM campus_trade.student_verify
   WHERE school_email = 'demo@gxnun.edu.cn' AND verify_status = 'SUCCESS';
  ```
- **演示邮箱自带"可零成本自证"的性质**，所以：① 只填明显是演示号的地址，绝不要填真实学生邮箱；
  ② 演示完把 `VERIFY_DEMO_MODE_ENABLED` 置回 `false`。两道启动期护栏会帮你兜住手误——
  开关打开却没有白名单会**直接拒绝启动**；生效时每次启动都会打一条点名白名单邮箱的 `WARN`
  （`VerifyDemoGuard`）。

#### 3.2 学校根本不发学生邮箱怎么办（学生证人工审核通道）

上面两节都建立在"学生有校园邮箱"这个前提上。但一部分高校**根本不提供学生邮箱**——对那批学生来说，
整条认证链路是不存在的；而认证是发布商品的硬前置，等于他们用不了平台。

因此校园认证现在有**两条通道**，认证结果语义完全相同（都写 `verify_status = 'SUCCESS'`、都点亮同一个
认证标识），差别只在材料与核验方式。通道只记录在 `student_verify.verify_method` 上，下游不需要区分。

| 通道 | 材料 | 核验方式 | 适用 |
| :--- | :--- | :--- | :--- |
| 校园邮箱（默认，V1 起） | 学号 + 校园邮箱 | 邮箱验证码，学生自助核销 | 学校有学生邮箱 |
| 学生证人工审核（V13 新增） | 学号 + 真实姓名 + 学生证/校园卡照片 | 管理员在审核队列里通过 / 驳回 | 学校**没有**学生邮箱 |

**边界与护栏**（都写在服务端，不依赖前端自觉）：

- **同一学号只能被一个账号认证成功**：V13 的 `uk_student_verify_school_number_success` 部分唯一索引
  与邮箱通道的 V12 邮箱唯一索引职责对等（邮箱通道防"一邮箱多号"，人工通道防"一学号多号"）；
- **驳回必须写原因**：不写原因等于让学生重猜一遍；学生在认证页能看到原因，修改材料后可重新提交，
  重新提交会清空上一轮的审核结论（避免"待审核"还挂着旧的驳回理由）；
- **提交限流**：默认每人每小时 5 次（`VERIFY_MANUAL_SUBMIT_LIMIT_PER_HOUR`）——这条通道没有发信成本，
  但审核是人工的，不限流就会有人把审核队列刷满；
- **每条处置都写审计**：`admin_audit_log` 里记 `PASS_STUDENT_VERIFY` / `REJECT_STUDENT_VERIFY`，
  与举报治理共用一张表，管理员操作可以在一处回溯；
- **两条通道的状态机不互相污染**：人工审核接口只接受 `verify_method = 'MANUAL'` 的申请，
  邮箱通道的 `PENDING` 行不能被它处置（有回归测试守着）。

**演示步骤**

1. **学生侧**：任意账号登录 → 个人中心 →「校园身份认证」→ 点底部「没有校园邮箱？改用学生证认证」→
   选学校、填学号与真实姓名、上传学生证/校园卡照片 → 提交；页面显示"材料已提交，等待管理员审核"。
   此时去发布商品会被拒（认证是硬前置，这一步能直观看出闸门生效）。
2. **管理员侧**：管理员账号登录 → 个人中心 →「认证审核」（**只有 `role = ADMIN` 的账号能看到这个入口**）→
   列表里看到该材料 → 点「查看大图」核对 → 「通过」或「驳回」（驳回必须填原因）。
3. **回到学生侧**：驳回则看到原因、可改材料重提；通过则显示已认证，此时发布商品成功。

> 管理端页面是随 V13 一起加进来的**第一个管理界面**（此前举报治理只有 API）。它只对管理员显示入口，
> 真正的权限边界在后端：`/api/admin/**` 由 `hasRole('ADMIN')` 拦截，非管理员请求一律 403。

#### 4. 启动前端应用
```bash
cd frontend
flutter pub get
flutter analyze
flutter run -d chrome
```
> Web 端 API 基址默认 `http://127.0.0.1:8081/api`（`lib/config/app_config.dart`，端口与
> `.env` 的 `BACKEND_PORT` 保持一致），需要指向别的后端时用编译期变量覆盖
> （`.env` 里的 `API_BASE_URL` 只是文档化的默认值，**不会**参与 Flutter 构建）：
> ```bash
> flutter run   -d chrome --dart-define=API_BASE_URL=http://10.0.0.5:8081/api
> flutter build web        --dart-define=API_BASE_URL=https://api.example.com/api
> ```

---

## 端口与环境变量清单

### 端口（本机开发）

| 服务 | 地址 | 说明 |
| :--- | :--- | :--- |
| 后端 HTTP API | `http://127.0.0.1:8081/api` | 上下文路径固定为 `/api`；端口由 `.env` 的 `BACKEND_PORT` 决定（8080 被同机另一个项目占用，故用 8081） |
| PostgreSQL 16 | `127.0.0.1:15435` | 库名 `campustrade`，schema `campus_trade`（宿主 5432 常被本机服务占用，故用 15435） |
| Redis 7 | `127.0.0.1:6379` | 需口令（`REDIS_PASSWORD`） |
| MinIO S3 API | `127.0.0.1:9000` | 桶名 `campustrade` |
| MinIO 控制台 | `http://127.0.0.1:9001` | 账号/口令见 `.env`（`MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD`） |
| Flutter Web 调试 | `flutter run -d chrome` 自动分配的端口 | 由 Flutter 决定，非固定值 |

> 说明：中间件端口取自 `.env`（`POSTGRES_PORT` / `REDIS_PORT` / `MINIO_PORT` / `MINIO_CONSOLE_PORT`），
> 改过端口后请同步修改 `SPRING_DATASOURCE_PORT` / `SPRING_DATA_REDIS_PORT` / `MINIO_ENDPOINT` / `MINIO_URL_PREFIX`。

### 后端端口只在一处配置（改端口必读）

后端端口只有一个配置点：**项目根目录 `.env` 的 `BACKEND_PORT`**（模板见 `.env.example`，当前 8081）。
其余位置全部由它派生，不需要（也不允许）再写死端口号：

| 读取方 | 怎么读到端口 |
| :--- | :--- |
| `backend/run-backend.cmd` | 解析 `.env` 后导出为环境变量 `SERVER_PORT`（Spring Boot 绑定 `server.port` 的约定名） |
| `backend/src/main/resources/application.yml` | `server.port: ${SERVER_PORT:8081}`（读不到时回退 8081） |
| `start.ps1` | 读 `.env` 的 `BACKEND_PORT`，用于占用预检 / 占用者归属判定 / 等待循环 / 提示 URL / 末尾导航 |
| `stop.ps1` | 读同一个键，用于"停止哪个端口的后端进程" |
| `backend/check-port.cmd` | 缺省读同一个键（也可用第一个参数显式覆盖，便于诊断任意端口） |
| `frontend/lib/config/app_config.dart` | 编译期常量默认 `http://127.0.0.1:8081/api`（与 `BACKEND_PORT` 同号，可用 `--dart-define` 覆盖） |

> **再改端口时**：只改 `.env` 的 `BACKEND_PORT`（并顺手把 `API_BASE_URL` 与
> `lib/config/app_config.dart` 的默认值改成同号），脚本、后端与文档中的示例 URL 会跟着变。
> 生产编排（`docker-compose.prod.yml` / `backend/Dockerfile`）是独立部署物，
> 容器内外统一为 8081，端口写在该文件与 Dockerfile 里。

### 环境变量（`.env`，模板见 `.env.example`）

| 变量 | 用途 | 是否必填 |
| :--- | :--- | :--- |
| `BACKEND_PORT` | **后端 HTTP 端口（唯一配置点）**；`run-backend.cmd` 会把它导出为 `SERVER_PORT` 供 Spring Boot 读取，启动/停止脚本与前端默认基址也以它为准 | 必填 |
| `POSTGRES_PORT` / `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | 容器内 PostgreSQL 初始化 | 必填 |
| `SPRING_DATASOURCE_HOST` / `_PORT` / `_DATABASE` / `_USERNAME` / `_PASSWORD` / `_SCHEMA` | 后端数据源（`_PORT` 必须与 `POSTGRES_PORT` 一致） | 必填 |
| `REDIS_PORT` / `REDIS_PASSWORD` | 容器内 Redis 端口与口令 | 必填 |
| `SPRING_DATA_REDIS_HOST` / `_PORT` / `_PASSWORD` | 后端 Redis 连接（`_PASSWORD` 必须与 `REDIS_PASSWORD` 一致） | 必填 |
| `MINIO_PORT` / `MINIO_CONSOLE_PORT` / `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` / `MINIO_BUCKET_NAME` | MinIO 容器与桶 | 必填 |
| `MINIO_ENDPOINT` / `MINIO_URL_PREFIX` | 后端访问 MinIO 的端点；**返回给前端的图片前缀**（生产必须是真实域名） | 必填 |
| `JWT_SECRET` | JWT 签名密钥，≥32 字节（`openssl rand -hex 32`） | **必填** |
| `CORS_ALLOWED_ORIGINS` | 跨域白名单，逗号分隔，支持 `http://localhost:*` 端口通配；**不可为 `*`** | 必填 |
| `VERIFY_MAIL_ENABLED` / `MAIL_HOST` / `MAIL_PORT` / `MAIL_USERNAME` / `MAIL_PASSWORD` / `MAIL_FROM` / `MAIL_SSL_ENABLED` | 校园认证邮件通道（本地保持 `false` 空值） | 生产必填 |
| `DEEPSEEK_API_KEY` / `DEEPSEEK_BASE_URL` / `DEEPSEEK_MODEL` | AI 估价/文案能力（可选，未配置时优雅降级） | 可选 |
| `API_BASE_URL` | 前端 API 基址的**文档化默认值**（实际用 `--dart-define` 覆盖） | 可选 |
| `SECURITY_LOGIN_MAX_FAILURES` / `SECURITY_LOGIN_LOCK_MINUTES` / `SECURITY_REGISTER_IP_LIMIT` | 登录失败锁定与注册限流阈值（有内置默认值） | 可选 |
| `SECURITY_TRUSTED_PROXIES` | 可信反向代理网段（逗号分隔 CIDR / 精确 IP）。**默认空 = 不采信 `X-Forwarded-For`/`X-Real-IP`**；前面有 Nginx/TLB 时必须配置代理地址，绝不可写 `0.0.0.0/0` | 可选（有代理时必填） |
| `SECURITY_TOKEN_ENDPOINT_IP_LIMIT` / `SECURITY_TOKEN_ENDPOINT_TOKEN_LIMIT` | `/auth/refresh`、`/auth/logout` 的按 IP / 按令牌指纹每分钟上限（默认 30 / 10） | 可选 |
| `AI_QUOTA_DAILY_LIMIT` / `AI_QUOTA_PER_MINUTE_LIMIT` | AI 接口按 userId 的日配额与分钟频控（默认 50 / 10） | 可选 |
| `SPRING_DATA_REDIS_POOL_*` | Lettuce 连接池参数（`commons-pool2` 已引入，参数真实生效） | 可选 |

> ⚠️ `.env.example` 里所有凭据都是 `CHANGE_ME_*` **占位符**，不含任何可用口令；
> 生产环境若继续使用 `CHANGE_ME_*` 前缀的值，`ProdSecretsGuard` 会直接拒绝启动。

---

## 测试与质量门禁

```bash
# 一键门禁（后端测试 + 前端静态分析 + 前端测试，任一失败即非 0 退出）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/quality-gate.ps1
# 或从启动器进入同一入口：
.\start.ps1 -Mode 6

# 单项执行
cd backend  && mvn -B test          # 263 项（Testcontainers 自带 PG/Redis/MinIO，不碰开发库；需要可用的 Docker）
cd frontend && flutter analyze      # 期望 0 issue
cd frontend && flutter test         # 194 项
```

**当前基线（本批次交付时实测）**：后端 `mvn -B test` 263 项全绿；`flutter analyze` 0 issue；`flutter test` 194 项全绿。
各阶段的测试规模变化（189 → 242 → 255 → 257 → 263 / 142 → 176 → 187 → 194）见 [CHANGELOG.md](CHANGELOG.md)。

> **门禁前置检查**：`scripts/quality-gate.ps1` 在跑测试前会先确认 Docker 可用
> （复用 `scripts/toolchain.ps1` 的 `Test-DockerAvailable`），不可用时打印中文原因与启动方法并以非 0 退出，
> 不会让 `mvn test` 把英文异常抛进日志。只跑前端两项时不需要 Docker：
> `-Only analyze` / `-Only flutter-test` 会跳过这项检查。
>
> 后端测试的中间件由 Testcontainers 在测试 JVM 内现拉现用（`com.campustrade.support.TestContainersConfig`），
> 因此 `mvn -B test` **不会**读写 `campustrade_*` 开发数据卷；容器镜像 tag 已固定
> （`postgres:16.15` / `redis:7.4.11` / `minio/minio:RELEASE.2024-10-13T13-34-11Z`，与 `docker-compose.yml` 一致）。
> 同理，`scripts/quality-gate.ps1` 刻意**不加载** `.env`：若把 `.env` 的 `SPRING_DATA_REDIS_PASSWORD`
> 导出到进程环境，测试会去给一个"没设口令的临时 Redis"发 AUTH 而失败。

---

## 首页与「我的待办」汇总接口

首页（`frontend/lib/pages/home/home_page.dart`）是**面向用户**的可用首页，自上而下四块：
欢迎条 → 搜索框 → 我的待办（仅登录用户） → 最新商品（最新 6 个在售商品 + 「查看全部 →」）。
早期那两张写满实现细节与"就绪 / Active / Ready"字样的开发进度说明卡已删除
（开发过程信息只属于提交历史与文档，不属于用户界面；`frontend/lib/widgets/status_badge.dart`
也因此不再被任何页面引用，已一并删除）。

| 区块 | 数据来源 | 跳转 |
| :--- | :--- | :--- |
| 搜索框（未登录可用） | 无（纯本地输入） | 集市页 `GET /goods/search` 同款入口，关键词经路由参数传入并在集市页发起搜索 |
| 我的待办 | **`GET /api/orders/summary`**（见下） | 待我确认 → 我的订单（卖家视角 + 待确认）；待面交 → 我的订单（待面交）；待评价 → 我的订单（已完成） |
| 最新商品 | `GET /api/goods/list?page=1&size=6` | 商品详情；底部「查看全部 →」→ 集市页 |

### `GET /api/orders/summary`（需登录）

只返回**当前登录用户自己**的订单计数，响应 `data`：

```json
{"pendingSellerConfirm": 0, "waitMeet": 0, "toReview": 0}
```

| 字段 | 口径 |
| :--- | :--- |
| `pendingSellerConfirm` | `seller_id = 我` 且 `order_status = 'WAIT_SELLER_CONFIRM'`（买家视角不计入——该状态下买家没有可做的动作） |
| `waitMeet` | `(buyer_id = 我 OR seller_id = 我)` 且 `order_status = 'WAIT_MEET'`（双方都要到场面交，因此买卖两侧都计入） |
| `toReview` | 我参与、`order_status = 'COMPLETED'`、且**不存在** `reviewer_id = 我` 的评价记录（只看我自己评没评，与对方评没评无关） |

实现：`TradeOrderMapper.selectTodoSummary` 用一条 PostgreSQL 聚合 SQL
（`count(*) FILTER (WHERE ...)` + 相关子查询 `NOT EXISTS`）一次算出三列，
避免"逐单查评价状态"的 N+1；`WHERE buyer_id = 我 OR seller_id = 我` 让数据隔离由 SQL 保证，
而不是靠调用方记得传对参数。三个计数用 `Integer` 下发（是"条数"不是 ID，不受全局
Long→String 序列化影响，前端拿到的是 JSON 数字）。无订单的用户仍得到一行全 0。

`frontend/lib/models/order_summary.dart` 与 `frontend/lib/controllers/home_controller.dart`
是该接口的前端落点：首页控制器只做"待办汇总 + 最新商品"两件事，**不复用**集市页的
`GoodsController` 或订单页的 `OrderController`（复用会把首页请求混进它们的分页/筛选状态机）。

---

## 安全约定：用户内容的存储与转义

一句话结论：**用户内容按原文存库、按原文出现在 JSON 负载里；任何 HTML 展示端都必须在输出点转义。**

- **当前唯一展示端是 Flutter 客户端**：商品描述、评价正文、举报原因等用户内容都经 `Text` 组件
  **按纯文本渲染，不解析 HTML**。因此后端不做输入侧"黑名单清洗"——
  那种做法既会破坏正常文本（`javascript` → `java`、`<3 这本书` 整段消失，且不可逆），
  也永远列不全（`<ScRiPt>`、事件属性等都能绕过），反而让人误以为"已经洗过了"。
- **新增 HTML 展示端时（后台管理页 / 邮件模板 / 导出报表 / 静态回调页）必须做两件事**：
  1. 在真正输出用户内容的那一处调用 `com.campustrade.common.util.HtmlEscapeUtils.escape(text)`
     （HTML 文本节点与属性值都适用），或改用模板引擎的自动转义并显式标注"已转义"；
  2. 同步更新本节与守护测试的断言，让新事实同样可验证。
- **这条约定由测试守护，不靠自觉**：`backend/src/test/java/com/campustrade/BackendHtmlSurfaceGuardTests.java`
  在每次 `mvn test` 时扫描 `backend/src/main/resources`，只要出现 `templates/` 目录或
  `.html` / `.ftl` / `.vm` / `.mustache` / `.jsp` 等模板类文件就会**失败**并给出上述两个步骤，
  以免"后端没有 HTML 出口"这个前提被悄悄推翻却没人重新评审转义策略。

> `HtmlEscapeUtils` 只提供输出侧能力：`escape(...)` 用于转义，`containsHtmlMarkup(...)` 仅用于审计观测、
> **绝不据此改写内容**。发现用户内容里含标签时，正确处置是"在该 HTML 输出点转义"，而不是回写数据库。

---

## Schema 单一真相源

- **建表/索引/约束/种子数据的唯一真相源是 Flyway 迁移**：`backend/src/main/resources/db/migration/V1..V12__*.sql`。
  后端启动时自动执行（`spring.flyway.*`），从空库可一路建出完整结构。
- `docker/postgres/init.sql` **只负责** `CREATE SCHEMA campus_trade` 与授权，**不含任何业务建表语句**。
  它仅在数据卷首次初始化时执行一次，即使删掉也不影响新环境（Flyway 会自行创建 schema）。
  （阶段 8 起移除了原先与迁移重复的那份建表语句副本——同一张表两份定义必然漂移。）
- 生产 profile 下 `spring.flyway.baseline-on-migrate: false`：库结构来路不明时宁可启动失败，也不自动打基线。

### 约束与历史脏数据（NOT VALID 外键）

V10 / V12 给关键关联补的外键里，涉及**历史造数可能已有孤儿行**的部分一律使用 `NOT VALID`：

| 迁移 | 约束 | 为什么 NOT VALID |
| --- | --- | --- |
| V10 | `review.order_id` / `review.goods_id` | 开发库存在历史造数孤儿评价（数百行） |
| V12 | 12 条关联外键（`review.reviewer_id`、`review_like.*`、`browse_history.*`、`search_history.user_id`、`report.reporter_id`、`admin_audit_log.admin_id`、`student_verify.*`、`user_credit.user_id`） | 本机开发库实测 `user_credit.user_id` 已有 14 条孤儿行；直接 `ADD CONSTRAINT`（全表校验）会让迁移整体失败并回滚 |

`NOT VALID` 的语义与运维要求：

1. **只约束新数据**：既有孤儿行保留原样（迁移绝不删改业务数据），新增/更新会被拦截；
2. **必须人工收口**：迁移成功 ≠ 数据干净。请按 `backend/docs/data-cleanup-orphans.sql` 执行
   「只读盘点 → 备份 → 按业务取舍清理 →`VALIDATE CONSTRAINT` 全量校验」，
   再用 `pg_constraint.convalidated` 复核；
3. **锁与耗时**：`ADD CONSTRAINT ... FOREIGN KEY`（不带 `NOT VALID`）会扫描全表并持 `ACCESS EXCLUSIVE` 锁
   （阻塞该表所有读写，大表上是分钟级）；带 `NOT VALID` 只登记定义，锁只持续毫秒级。
   `VALIDATE CONSTRAINT` 只取 `SHARE UPDATE EXCLUSIVE` 锁，不阻塞读写，可在线执行。
4. **多态列没有外键**：`report.target_id` 随 `target_type`（GOODS / REVIEW / USER）指向不同表，
   单列外键无法表达；其一致性由 `chk_report_target_type` 值域约束 + 应用写入前多态存在性校验保证。

### 校园邮箱唯一性（V12 部分唯一索引）

V12 在 `student_verify(school_id, school_email)` 上建 `WHERE verify_status = 'SUCCESS'` 的部分唯一索引
（一个校园邮箱只能被一个账号核销成功；`PENDING` 行不受限制）。
若建索引前发现已存在「同一邮箱多条 SUCCESS」的历史冲突，迁移会**跳过建索引并打印冲突明细**
（迁移本身仍然成功、不删任何数据），随后按 `backend/docs/data-cleanup-orphans.sql` 第 5 段人工处理。

---

## 生产部署步骤（摘要）

完整说明见 `docker-compose.prod.yml` 顶部注释与 `frontend/README.md`；此处给出最短路径。

### 0. 前置
- 一台装了 Docker Engine + Compose v2 的服务器；
- 一个可用域名（前端静态站点 + API），以及对外可达的图片域名（MinIO / 反向代理）；
- 一个可用的 SMTP 发信账号（校园认证验证码）。

### 1. 准备生产变量
把变量导出到 shell 或写入 `/etc/campustrade/prod.env`（**不要复用开发 `.env`**）：
```bash
export POSTGRES_DB=campustrade POSTGRES_USER=campustrade POSTGRES_PASSWORD='<强口令>'
export REDIS_PASSWORD='<强口令>'
export MINIO_ROOT_USER='<access-key>' MINIO_ROOT_PASSWORD='<强口令>' MINIO_BUCKET_NAME=campustrade
export MINIO_URL_PREFIX='https://img.example.com/campustrade'
export JWT_SECRET="$(openssl rand -hex 32)"
export CORS_ALLOWED_ORIGINS='https://app.example.com'
export MAIL_HOST='smtp.example.com' MAIL_PORT=465 MAIL_USERNAME='noreply@example.com' \
       MAIL_PASSWORD='<授权码>' MAIL_FROM='noreply@example.com'
```

### 2. 启动后端 + 中间件
```bash
docker compose -f docker-compose.prod.yml --env-file /etc/campustrade/prod.env up -d
```
- 生产编排里 postgres / redis / minio **不发布任何端口**，只在内部网络可达；
- 应用端口：容器内监听与宿主发布**都是 8081**（`SERVER_PORT: "8081"` / `EXPOSE 8081` /
  容器内 `HEALTHCHECK` 探针 `http://127.0.0.1:8081/api/school/list`），与本地开发端口同号；
  需要改宿主发布端口时用 `APP_PORT`（例如 `APP_PORT=8090`，容器内仍是 8081）；
- `depends_on: condition: service_healthy` 保证中间件健康后才启动应用；
- 应用以 `SPRING_PROFILES_ACTIVE=prod` 运行，容器内为非 root（uid 10001）、根文件系统只读、仅 `/tmp` 与日志卷可写；
- 缺任何敏感项都会**拒绝启动**（`ProdSecretsGuard` / `VerifyMailProdGuard` / `JwtTokenProvider` 三处 fail-fast）。

### 2.1 迁移后的数据清理（V10 / V12 的 NOT VALID 外键）

`V1..V12` 在应用启动时自动执行。**其中 V10/V12 的 14 条外键全部是 `NOT VALID`：它们只约束新数据，
历史孤儿行不会被自动校验，也不会被自动删除**（迁移绝不删改业务数据）。

> **在线 DDL 与锁语义（V10 的两条外键就是这个取舍）**
> `ADD CONSTRAINT ... FOREIGN KEY`（不带 `NOT VALID`）会**扫描全表校验每一行**，并持有
> `ACCESS EXCLUSIVE` 锁 —— 阻塞该表所有读写，大表上是分钟级；V10 的 `review.order_id` /
> `review.goods_id` 因此写成 `NOT VALID`：只登记定义、锁只持续毫秒级，且**只约束新行**。
> 而 `VALIDATE CONSTRAINT` 只取 `SHARE UPDATE EXCLUSIVE` 锁、**不阻塞读写，可以在线执行**。
> 更细的说明见下文「Schema 单一真相源 → 约束与历史脏数据（NOT VALID 外键）」。

因此上线前/上线后请执行一次：

```bash
# 只读盘点（可直接执行，不修改任何数据）
docker exec -i <postgres容器> psql -U campustrade -d campustrade \
  < backend/docs/data-cleanup-orphans.sql   # 该文件前两段是只读 SELECT，其后为人工确认后的清理/收口 SQL
```

顺序与要点（详见 `backend/docs/data-cleanup-orphans.sql`）：

1. **盘点**：`第 1 段` 输出各外键的孤儿行数量（含 V12 新增的 12 条）；
2. **备份**：`第 2 段`（清理任何数据之前必须先做；默认方案是**什么都不做**）；
3. **按业务取舍**：`第 3 段` 给出三种方案（保留 / 改挂 / 删除），默认不做删除；
4. **收口**：`第 4 段` 用 `VALIDATE CONSTRAINT` 把 `NOT VALID` 升级为全量校验
   （只取 `SHARE UPDATE EXCLUSIVE` 锁，可在线执行），再用 `pg_constraint.convalidated` 复核；
5. **校园邮箱冲突**：若日志里出现 `[V12] 检测到校园邮箱重复认证冲突`，说明有历史数据把同一邮箱
   认证给了多个账号，按 `第 5 段` 人工确认保留哪一个后修改其余行，再执行文件里给出的
   `CREATE UNIQUE INDEX uk_student_verify_email_success ...`。

### 2.2 反向代理与可信 IP（上线前务必确认）

后端默认**不采信** `X-Forwarded-For` / `X-Real-IP`（按 TCP 对端地址计限流维度）。

- 若生产入口本身就是应用容器（无反向代理）：**保持 `SECURITY_TRUSTED_PROXIES` 为空**，这是正确配置；
- 若前面有 Nginx / TLB / Ingress：必须把代理地址写进 `SECURITY_TRUSTED_PROXIES`
  （例如 `10.0.0.0/8,172.17.0.0/16`），否则所有用户会共用"代理的 IP"这一个限流维度，
  IP 维度的登录失败锁定会误伤全站；**绝不能写 `0.0.0.0/0`**（等于又变成无条件采信客户端头部）。

### 2.3 上线前必须执行：清理 Redis 中的历史明文 refresh token

**为什么**：refresh token 现在只以 **SHA-256 摘要**存 Redis（键 `jwt:refresh:{userId}`，值是 64 位十六进制；
见 `RedisKeyConstants.JWT_REFRESH_PREFIX` 与 `TokenHashUtils`）。但阶段 2 之前的旧代码把**令牌明文**
写在同一个键里，这些历史键：

- **不会被新代码复用**：校验逻辑拿"传入令牌的 SHA-256"去比对，明文值永远匹配不上，这些会话事实上已经失效；
- **也不会自己消失**：TTL 是 7 天，但只要被刷新过就会续期；
- **等于"可直接使用的登录凭证"躺在缓存里**：Redis 备份 / RDB 文件 / 误开的端口泄漏即等于会话泄漏。

终审修复时已在本机开发 Redis 中清掉 37 个这样的明文键；**其它环境（含生产）上线时必须各执行一次**。

**怎么判定**：明文 JWT 一定以 `eyJ` 开头（`{"alg":...}` 的 base64url 前缀），摘要值只可能是 `[0-9a-f]{64}`。
所以"值以 `eyJ` 开头"的 `jwt:refresh:*` 键就是待清理项。

```bash
# 1) 只读盘点：只打印仍需清理的键名（不回显任何令牌内容）
docker compose -f docker-compose.prod.yml exec -T redis sh -lc '
  redis-cli -a "$REDIS_PASSWORD" --no-auth-warning --scan --pattern "jwt:refresh:*" \
  | while read -r k; do
      [ "$(redis-cli -a "$REDIS_PASSWORD" --no-auth-warning GET "$k" | cut -c1-3)" = "eyJ" ] \
        && echo "待清理: $k"
    done'

# 2) 清理：删除这些明文键（SHA-256 摘要键不受影响，当前有效会话不会被踢）
docker compose -f docker-compose.prod.yml exec -T redis sh -lc '
  n=0
  for k in $(redis-cli -a "$REDIS_PASSWORD" --no-auth-warning --scan --pattern "jwt:refresh:*"); do
    if [ "$(redis-cli -a "$REDIS_PASSWORD" --no-auth-warning GET "$k" | cut -c1-3)" = "eyJ" ]; then
      redis-cli -a "$REDIS_PASSWORD" --no-auth-warning DEL "$k" > /dev/null && n=$((n + 1))
    fi
  done
  echo "已删除明文 refresh token 键: $n"'
```

- **预期影响**：被删掉的都是阶段 2 之前写入的会话，持有它们的客户端下次刷新会拿到 401，需要重新登录 ——
  这是期望结果（旧明文凭证本就不该继续可用）；
- 清理后请把上面的"1) 只读盘点"再跑一次，确认输出为空；
- 只动 `jwt:refresh:*` 这一类键：登录失败计数、验证码、限流键、`jwt:blacklist:*` 一律不碰。

### 2.4 镜像可复现性：tag、digest 与代码版本对应关系

- **应用镜像 tag 与 `pom.xml` 版本号的对应**：`docker-compose.prod.yml` 里写的是
  `image: campustrade-backend:${APP_IMAGE_TAG:-0.0.1}`，其中 `0.0.1` 对应 `backend/pom.xml` 的
  `<version>`（当前为 `0.0.1-SNAPSHOT`）。规则是 **镜像 tag = pom 版本号去掉 `-SNAPSHOT` 后缀**：
  发布时先改 `pom.xml` 版本，再同步 `APP_IMAGE_TAG`（或直接
  `APP_IMAGE_TAG=0.0.2 docker compose -f docker-compose.prod.yml up -d --build`），
  使"镜像 tag ↔ 代码版本"一一对应；**不要用 `latest`**。
- **基础镜像与中间件镜像都固定在补丁级 tag**（见 `backend/Dockerfile` 与 `docker-compose.prod.yml`）：
  例如运行阶段是 `eclipse-temurin:21.0.12_8-jre-alpine-3.24`（JDK 补丁 + Alpine 小版本都固定），
  中间件是 `postgres:16.15` / `redis:7.4.11` / `minio/minio:RELEASE.2024-10-13T13-34-11Z`。
- **记录 digest 以保证可复现**（tag 可被重新指向，digest 不能）：

```bash
# 构建后记录本次产物的 digest（tag → digest 对应关系）
docker images --digests | grep campustrade-backend

# 记录基础镜像 / 中间件镜像的 digest（多架构 manifest list 的 digest）
docker buildx imagetools inspect eclipse-temurin:21.0.12_8-jre-alpine-3.24
docker buildx imagetools inspect postgres:16.15
```

把输出里的 `<镜像>:<tag>@sha256:<digest>` 记进发布单/部署记录。若要求"任何机器拉到的字节完全一致"，
把 `FROM` / `image:` 写成 digest 形式（如
`FROM eclipse-temurin:21.0.12_8-jre-alpine-3.24@sha256:1a29e1fe...`）：固定 digest 后上游安全补丁
不会自动进入，升级必须显式改 digest —— 与"固定 tag"是同一个取舍，只是更严格。

### 3. 部署前端 Web 产物
```bash
cd frontend && flutter build web --dart-define=API_BASE_URL=https://app.example.com/api
# 把 build/web/ 交给 Nginx / 静态托管，并配置 SPA 回退（404 -> index.html）
```
详见 `frontend/README.md`（含 Nginx 配置、`base-href`、缓存策略与 API 基址注入方式）。

### 4. 生产运维建议（阶段 7 遗留结论）
- **`random_page_cost` 与 SSD 不匹配**：PostgreSQL 默认 `random_page_cost=4.0` 是按机械盘标定的，
  而本项目的开发机与目标云盘均为 SSD，这会低估索引扫描的收益。建议在生产库按库调整一次：
  ```sql
  ALTER DATABASE campustrade SET random_page_cost = 1.1;
  -- 生效：重连或 SELECT pg_reload_conf() 后，用 SHOW random_page_cost; 确认
  ```
- **数据库备份目前没有任何机制**：`docker-compose.prod.yml` 只保证数据落在命名卷里，
  **卷不是备份**（宿主机磁盘损坏、误删卷都会一起丢）。上线前至少做到：
  1. 每日 `pg_dump -Fc` 到另一台机器/对象存储，并保留 7–30 天；
  2. MinIO 桶（用户上传的图片）做同步/异地副本 —— 图片丢失无法从数据库恢复；
  3. 定期做一次"从备份恢复到全新实例"的演练，确认备份真的可用；
  4. 记录并演练 `docker compose -f docker-compose.prod.yml down`（不带 `-v`）与数据卷迁移流程。
- **Redis 内存策略**：生产编排使用 `--maxmemory-policy noeviction`。
  Redis 里存着登录失败计数、验证码与限流键，被自动淘汰等于安全策略静默失效；
  内存打满时宁可写入报错（可观测），也不静默丢键。请为 Redis 配置内存告警。
- **接口文档页**：当前**没有**在线 API 文档页（未引入 springdoc / swagger-ui），
  脚本与文档中的旧链接已移除。如需在线文档，先加
  `org.springdoc:springdoc-openapi-starter-webmvc-ui` 依赖并在 `SecurityConfig` 放行
  `/v3/api-docs/**`、`/swagger-ui/**`（生产是否开放另需评估信息暴露风险）。

---

## 当前状态（Stage 8 + 终审修复 + 批次 1/2）

- [x] 标准项目根目录结构搭建
- [x] Docker Compose 基础设施（PostgreSQL 16、Redis 7、MinIO）编排
- [x] 认证安全、校园身份认证、数据一致性、契约收敛、前端稳定性、性能优化
- [x] 配置单一来源：`.env` → 进程环境变量注入，`.env.example` 无任何可用凭据
- [x] profile 隔离：`prod` 下敏感项无可用默认值，缺失即拒绝启动（中文 fail-fast）
- [x] 生产交付物：多阶段 `backend/Dockerfile`（非 root、JRE 21、健康检查）+ `docker-compose.prod.yml`
- [x] 开发编排加固：中间件仅绑 `127.0.0.1`、Redis 口令、镜像 tag 固定、日志轮转与资源上限
- [x] 脚本健壮性：外部命令退出码检查、选项 6 以测试结果决定退出码、工具链"环境变量 → PATH → 报错指引"
- [x] Schema 单一真相源：`init.sql` 仅建 schema，建表全部交给 Flyway（空库验证到 V12 全 success）
- [x] 文档校正：阶段/状态/技术栈/端口/环境变量/门禁命令/生产部署同步到实际实现
- [x] 终审修复：资料回写改定点更新、订单详情串单、生产拒绝已知开发口令、历史审计报告加"已过时"抬头
- [x] 批次 1（后端）：可信代理解析、AI 配额、校园邮箱发送配额、token 端点限流、上传加固、V12 迁移
- [x] 批次 2（前端）：控制器作用域与 binding、CancelToken、页面守卫与统一提示、AppLogger、冒烟测试骨架
- [x] 工程化收尾（批次 3）：门禁 Docker 前置检查、CI 与本地门禁对齐、镜像 tag 固定、CHANGELOG/CONTRIBUTING、stage7/8 报告
- [x] 代码整洁收尾（本批次）：JWT 令牌 userId 与数据库用户比对、认证测试用例自治、`StudentVerifyStatus` 枚举化、
      测试裸状态字面量收敛、前端 `_serverMessage` 统一与 pageSize/版本号收敛、HTML 展示端守护测试
- [x] 测试基线：后端 `mvn -B test` **263 项**、`flutter analyze` 0 issue、`flutter test` **194 项**（全绿）
- [x] 首页重做为"面向用户"的可用首页（本批次）：删除两张开发进度说明卡（`Active`/`Ready`/技术名词清零），
      改为 搜索框 + 我的待办（新增 `GET /api/orders/summary` 一条 SQL 算三项计数）+ 最新商品，
      整页下拉刷新；新增 `HomeController`（页面级实例，不与集市/订单页共用）、首页与集市/订单的初始参数支持

> 各阶段的提交、验证结论与测试项数变化见 [CHANGELOG.md](CHANGELOG.md)。
