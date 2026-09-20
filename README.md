# CampusTrade 校园二手交易平台

> 面向高校大学生的校园二手闲置交易平台，打造安全、便捷、绿色流转的校园数字集市。

---

## 快速导航

- **项目阶段**：Stage 0（项目初始化与基础设施建设）
- **架构文档**：[docs/README.md](docs/README.md)
- **环境配置**：[.env.example](.env.example)
- **Docker 编排**：[docker-compose.yml](docker-compose.yml)

---

## 技术栈总览

| 模块 | 技术选型 | 说明 |
| :--- | :--- | :--- |
| **后端框架** | Spring Boot 3.3.4 + Java 21 | 高性能后端企业级开发 |
| **持久层** | MyBatis-Plus 3.5.7 + PostgreSQL 16 | 现代化 ORM 与高可靠开源关系型数据库 |
| **高速缓存** | Redis 7 | 开启 AOF 数据持久化 |
| **对象存储** | MinIO (最新版) | 兼容 AWS S3 的私有图片与文件存储 |
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
> 启动器将自动：
> 1. 检查并拉起 Docker（PostgreSQL 16、Redis 7、MinIO）；
> 2. 检查并启动后端 Spring Boot 3 服务（端口 8080）：启动前先判断 8080 占用情况，
>    能区分「本项目后端」与「其它程序占用」，后者会报出占用者主类与所属工程目录并中止，
>    既不会重复启动也不会误杀别的工程；
> 3. 打开 Chrome 浏览器启动 Flutter Web 前端应用；后端未就绪时会直接中止，不会继续拉起前端；
> 4. 如需关闭所有服务，可双击运行 `stop.bat` 或 `stop.ps1`
>    （若 8080 被非本项目进程占用，停止脚本会先询问，默认不动它）。

### 脚本结构与约定（改脚本前必读）

| 文件 | 作用 | 编码要求 |
| :--- | :--- | :--- |
| `start.bat` / `stop.bat` | 纯 ASCII 转发器，仅负责调用同名 `.ps1` | **必须保持纯 ASCII** |
| `start.ps1` / `stop.ps1` | 启动器 / 停止器的全部逻辑与中文提示 | UTF-8 **带 BOM** |
| `backend/run-backend.cmd` | 解析 JDK/Maven 并启动 Spring Boot，失败时给出明确提示 | **必须纯 ASCII** |
| `backend/check-port.cmd` | 诊断 8080 占用者（空闲 / 本项目 / 其它程序） | **必须纯 ASCII** |
| `frontend/run-frontend.cmd` | 解析 Flutter 并执行 flutter 命令 | **必须纯 ASCII** |

> **为什么中文不能写进 `.bat` / `.cmd`**：cmd.exe 按控制台代码页（本机为 GBK/936）解析批处理文件，
> 而文件字节是 UTF-8 时会吞掉引号、错位行，命令会静默失效——历史上后端"拉不起来"正是这个原因。
> 中文提示请一律写在 `.ps1` 中，并保持"UTF-8 with BOM"保存（否则 PowerShell 5.1 同样会读错中文）。

---

### 手动逐步启动

#### 1. 启动基础设施
```bash
docker compose up -d
```

#### 2. 启动后端
```bash
cd backend
mvn spring-boot:run
```

### 3. 启动前端应用
```bash
cd frontend
flutter pub get
flutter analyze
flutter run -d chrome
```

---

## 当前状态 (Stage 0)

- [x] 标准项目根目录结构搭建
- [x] Docker Compose 基础设施（PostgreSQL 16、Redis 7、MinIO）编排
- [x] PostgreSQL 模式初始化脚本（`campus_trade`）
- [x] Spring Boot 3 + Java 21 后端工程骨架与基础包配置
- [x] Flutter 3 前端工程与 GetX / Dio 基础架构搭建
- [x] 环境配置模板 `.env.example` 与工程说明文档
- [ ] *（Stage 1 待开启）用户服务与校园身份认证*
