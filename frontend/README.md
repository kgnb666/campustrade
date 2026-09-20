# CampusTrade 前端（Flutter）

CampusTrade 的 Flutter 多端客户端（Web / Android / iOS / Desktop）。
状态管理用 GetX，网络层用 Dio（拦截器 + Token 自动刷新），敏感凭证存 Flutter Secure Storage。

---

## 一、本地开发

```bash
flutter pub get
flutter analyze                 # 期望 0 issue
flutter test                    # 142 项
flutter run -d chrome           # Web 调试（推荐，快速验证）
flutter run -d windows          # Windows 桌面端
```

也可以用仓库脚本（会解析 Flutter 路径并转发参数，失败时给出明确提示）：

```bash
cd frontend
run-frontend.cmd run -d chrome
```

### API 基址怎么注入

`lib/config/app_config.dart` 中的基址是**编译期常量**，默认 `http://127.0.0.1:8080/api`：

```dart
static const String apiBaseUrl = String.fromEnvironment(
  'API_BASE_URL',
  defaultValue: 'http://127.0.0.1:8080/api',
);
```

要指向别的后端，用 `--dart-define` 覆盖（注意 `dart-define` 的值是编译期字符串，
每次改动都要重新构建，见下文"缓存陷阱"）：

```bash
# 调试：指向同一局域网里的后端
flutter run -d chrome --dart-define=API_BASE_URL=http://10.0.0.5:8080/api

# 生产构建：指向线上 API
flutter build web --dart-define=API_BASE_URL=https://app.example.com/api
```

> 项目根目录 `.env` 里的 `API_BASE_URL` **不会**参与 Flutter 构建，
> 它只是"默认值是什么"的文档化记录（改了 `.env` 不会改变 App 行为）。
> 后端地址一律通过 `--dart-define=API_BASE_URL=...` 注入。
>
> 跨域：后端 `CORS_ALLOWED_ORIGINS` 必须包含前端的来源（生产用真实域名，**不能**写 `*`），
> 否则浏览器会拦截请求（后端 `allowCredentials=true`）。

---

## 二、Web 生产部署

### 1. 构建产物

```bash
flutter build web --release --dart-define=API_BASE_URL=https://app.example.com/api
```

产物在 `frontend/build/web/`，是一堆静态文件（`index.html` + `main.dart.js` + `assets/` + `canvaskit/`），
可直接交给 Nginx、对象存储静态托管或任意 CDN。

如果前端不是部署在域名根路径（例如 `https://example.com/app/`），加上 `--base-href`：

```bash
flutter build web --release --base-href=/app/ --dart-define=API_BASE_URL=https://example.com/api
```

（`--dart-define` 注入的 API 地址不受 `--base-href` 影响，两者互相独立。）

### 2. SPA 回退（404 → index.html）

Flutter Web 是单页应用，路由在客户端（GetX）完成，服务器上**并不存在** `/goods/123` 这类路径。
如果服务器对未知路径直接返回 404，用户刷新页面或直接打开深链接就会看到 404。

**Nginx 配置示例**（`try_files` 回退是必须的）：

```nginx
server {
    listen       443 ssl;
    server_name  app.example.com;

    root  /var/www/campustrade/web;   # flutter build web 产物目录
    index index.html;

    # 深链接回退：任何找不到的路径都交给 index.html，由前端路由接管
    location / {
        try_files $uri $uri/ /index.html;
    }

    # 带内容哈希的产物可以长缓存
    location ~* \.(?:js|wasm|json|css|png|jpg|jpeg|gif|svg|woff2?|ttf|otf)$ {
        expires 30d;
        add_header Cache-Control "public, max-age=2592000, immutable";
    }

    # 入口文件与 Service Worker 必须不缓存，否则用户会一直用旧版本
    location = /index.html {
        add_header Cache-Control "no-store";
    }
    location ~ ^/(flutter_service_worker\.js|flutter_bootstrap\.js|version\.json)$ {
        add_header Cache-Control "no-store";
    }

    # 若前端与后端同域，可以在这里反代 API（此时 API_BASE_URL 用同域地址）
    # location /api/ {
    #     proxy_pass http://127.0.0.1:8080/api/;
    #     proxy_set_header Host $host;
    #     proxy_set_header X-Real-IP $remote_addr;
    #     proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    #     proxy_set_header X-Forwarded-Proto $scheme;
    # }
}
```

其他托管方式的等价做法：
- **Nginx**：上面 `try_files $uri $uri/ /index.html;`
- **S3 / COS / OSS 静态托管**：把"错误文档(404)"设置为 `index.html`，并返回 200（不是 302）
- **Vercel / Netlify**：配置 rewrite `/* -> /index.html`
- **IIS**：URL Rewrite 规则把不存在的路径重写到 `/index.html`

### 3. 缓存陷阱（部署后"页面没变"的常见原因）

1. `index.html` 与 `flutter_service_worker.js` 必须**不缓存**（`no-store`），
   否则浏览器会一直用旧的 Service Worker 拉旧资源；上面的 Nginx 示例已处理。
2. 用 `--dart-define` 改过 API 地址后必须**重新构建**（值被编译进 `main.dart.js`），
   直接改 `.env` 或服务器上的文件不会生效。
3. 发布新版本时建议同时清理 CDN 缓存里的 `index.html` 与 `flutter_service_worker.js`。

### 4. 常见问题

| 现象 | 原因 / 处理 |
| :--- | :--- |
| 深链接刷新 404 | 没配 SPA 回退，见上面"SPA 回退" |
| 页面能开但请求全部失败 | `CORS_ALLOWED_ORIGINS` 未包含前端来源；或 `API_BASE_URL` 指向了 `127.0.0.1`（用户浏览器访问不到服务器本机） |
| 图片裂图 | `MINIO_URL_PREFIX` 仍是开发默认值 `http://127.0.0.1:9000/...`；生产必须是公网可达的图片域名 |
| 部署后仍是旧版本 | `index.html` / Service Worker 被缓存，见上面"缓存陷阱" |
| Web 首次加载慢 | `canvaskit/` 资源较大，确认 gzip/br 压缩与缓存头已开启（Nginx `gzip on;` / `brotli on;`） |

---

## 三、目录结构

```
frontend/
├── lib/
│   ├── api/          # Dio 客户端、拦截器与各领域 API 定义
│   ├── config/       # AppConfig（API 基址、分页常量等）
│   ├── models/       # 数据模型
│   ├── pages/        # 页面（首页/搜索/详情/发布/订单/个人中心/校园认证…）
│   ├── routes/       # GetX 路由
│   ├── services/     # 网络、存储、登录态等全局服务
│   ├── utils/        # 工具函数
│   ├── widgets/      # 通用组件
│   └── main.dart
├── test/             # Widget / 单元测试
├── web/              # Web 入口（index.html、manifest.json、icons）
├── run-frontend.cmd  # 启动转发脚本（纯 ASCII，解析 FLUTTER_ROOT/PATH）
└── pubspec.yaml
```
