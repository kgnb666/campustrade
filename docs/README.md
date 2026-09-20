# CampusTrade 校园二手交易平台 - 详细设计与技术文档

本文档为 **CampusTrade（校园二手交易平台）** 的架构与技术环境规范说明。

---

## 一、项目介绍

CampusTrade 是一个面向高校大学生的校园闲置二手交易平台。系统旨在打造绿色循环、可信便捷的校园交易生态，核心规划功能包括：
- **校园身份认证**：学号与校园邮箱/实名认证，保障校内真实交易身份；
- **二手商品流转**：商品发布、成色评级、多图展示、校内分类检索；
- **即时消息沟通**：买卖双方在线即时私聊、议价与预约自提地点；
- **安全交易担保**：线上意向锁定、线下核验自提、评价信誉体系；
- **AI 智能赋能**：集成 DeepSeek 等大语言模型，提供闲置估价、文案智能美化、智能反欺诈识别。

当前版本为 **Stage 0：项目初始化阶段**，旨在建立标准、稳定的后端、前端与容器化基础设施，未夹杂任何具体业务逻辑。

---

## 二、技术架构

```
+-------------------------------------------------------------+
|                      CampusTrade 总体架构                    |
+-------------------------------------------------------------+
| 前端层 (Frontend): Flutter 3.x (Dart 3.x)                  |
| - 状态管理 & 路由: GetX                                      |
| - 网络请求: Dio                                             |
| - 本地安全存储: Flutter Secure Storage                      |
| - 平台支持: Web / Android / iOS / Desktop                   |
+-------------------------------------------------------------+
                              |
                              | HTTP RESTful APIs
                              v
+-------------------------------------------------------------+
| 后端服务层 (Backend): Spring Boot 3.x (Java 21)             |
| - ORM 数据持久层: MyBatis-Plus                              |
| - 参数校验: Spring Boot Validation                          |
| - 数据连接池: HikariCP                                       |
| - 代码简化: Lombok                                          |
+-------------------------------------------------------------+
                              |
       +----------------------+----------------------+
       |                      |                      |
       v                      v                      v
+---------------+     +---------------+     +---------------+
|  PostgreSQL   |     |    Redis 7    |     |  MinIO (S3)   |
| 关系型数据库   |     | 缓存与高频会话|     | 对象文件存储   |
| (Schema:      |     | (AOF 持久化)  |     | (商品图/附件) |
| campus_trade) |     |               |     |               |
+---------------+     +---------------+     +---------------+
```

---

## 三、开发环境要求

| 组件 / 工具 | 推荐版本 | 说明 |
| :--- | :--- | :--- |
| **操作系统** | Windows 10/11, macOS, Linux | 跨平台开发 |
| **JDK** | OpenJDK 21 LTS | 后端核心运行时 |
| **构建工具** | Apache Maven 3.9+ | 依赖管理与打包 |
| **Flutter** | Flutter 3.x (Dart 3.x) | 移动与多端开发 SDK |
| **容器引擎** | Docker 24+ & Docker Compose v2+ | 中间件本地编排 |
| **数据库** | PostgreSQL 16 | 核心业务数据库 |
| **缓存** | Redis 7 | 缓存与即时通信会话 |
| **对象存储** | MinIO (最新稳定版) | 本地兼容 S3 的分布式存储 |

---

## 四、启动方式

### 1. 基础设施启动（Docker Compose）

在项目根目录下，先复制环境配置：
```bash
cp .env.example .env
```
*(如宿主机本地已安装 PostgreSQL 占用了 5432 端口，可修改 `.env` 中的 `POSTGRES_PORT=15432`)*

启动中间件服务容器：
```bash
docker compose up -d
```

查看容器运行状态：
```bash
docker compose ps
```
服务控制台访问入口：
- **PostgreSQL 16**: 宿主机端口 `5432`（或自定义端口）
- **Redis 7**: 宿主机端口 `6379`
- **MinIO Web Console**: `http://localhost:9001`（默认账号：`campustrade`，密码：`campustrade123`）
- **MinIO S3 API**: `http://localhost:9000`

---

### 2. 后端服务启动

进入后端目录：
```bash
cd backend
```

执行单元与集成测试：
```bash
mvn test
```

本地启动 Spring Boot 服务：
```bash
mvn spring-boot:run
```
后端服务默认监听端口：`http://localhost:8080`

---

### 3. 前端应用启动

进入前端目录：
```bash
cd frontend
```

安装 Flutter 依赖：
```bash
flutter pub get
```

代码质量分析：
```bash
flutter analyze
```

启动 Chrome 调试（推荐 Web 快速验证）：
```bash
flutter run -d chrome
```

---

## 五、项目目录说明

```
CampusTrade/
├── backend/                       # Spring Boot 3 + Java 21 后端模块
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/campustrade/
│   │   │   │   ├── common/        # 统一返回结果 Result、通用常量与工具
│   │   │   │   ├── config/        # MyBatis-Plus、Redis 等组件配置
│   │   │   │   ├── controller/    # Web 控制器层（留空，无业务）
│   │   │   │   ├── dto/           # 数据传输对象（留空）
│   │   │   │   ├── entity/        # 数据库实体类（留空）
│   │   │   │   ├── exception/     # 全局异常处理与自定义业务异常
│   │   │   │   ├── mapper/        # MyBatis-Plus 数据访问接口（留空）
│   │   │   │   ├── service/       # 业务逻辑接口与实现（留空）
│   │   │   │   ├── vo/            # 视图呈现对象（留空）
│   │   │   │   └── CampusTradeApplication.java # Spring Boot 启动类
│   │   │   └── resources/
│   │   │       └── application.yml# 应用核心配置文件
│   │   └── test/                  # 单元与集成测试
│   └── pom.xml                    # Maven 构建脚本
├── frontend/                      # Flutter 3 多端前端模块
│   ├── lib/
│   │   ├── api/                   # Dio 封装与 API 请求定义
│   │   ├── config/                # 客户端环境、全局常量配置
│   │   ├── models/                # 前端数据模型
│   │   ├── pages/                 # 页面 UI（包含 Stage 0 占位页）
│   │   ├── routes/                # GetX 页面路由管理
│   │   ├── services/              # 客户端全局服务（网络、存储等）
│   │   ├── utils/                 # 工具函数
│   │   ├── widgets/               # 通用基础 UI 组件
│   │   └── main.dart              # Flutter 入口
│   └── pubspec.yaml               # Flutter 依赖配置
├── docker/                        # 容器化与运维配置
│   └── postgres/
│       └── init.sql               # PostgreSQL 数据库初始化脚本
├── docs/                          # 项目相关设计与接口文档
│   └── README.md                  # 本说明文档
├── .env.example                   # 环境变量模板
├── docker-compose.yml             # 本地中间件一键编排配置
└── README.md                      # 项目根说明文档
```
