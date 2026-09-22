# CampusTrade 服务器部署（腾讯云）

本目录是**服务器侧**的部署套件：把 `docker-compose.prod.yml` 的生产栈加上一个边缘
Nginx，并用脚本生成生产变量文件。开发机上的 `start.ps1` / `docker-compose.yml`
与这里无关，互不影响。

---

## 一、当前部署实例（2026-09-21）

| 项目 | 值 |
| --- | --- |
| 服务器 | `129.204.61.68`（腾讯云 CVM，Ubuntu 24.04，4 核 / 3.6G / 59G） |
| 登录 | `ubuntu`（免密 sudo），SSH 22 |
| 代码目录 | `/opt/campustrade`（git 仓库，origin = GitHub，用服务器自己的 Deploy Key 拉取） |
| 生产变量 | `/etc/campustrade/prod.env`（600 / root:root，含随机凭据） |
| 前端产物 | `/opt/campustrade/web`（`flutter build web` 输出） |
| 对外入口 | `http://129.204.61.68:8080`（**需先放通安全组 8080**，见第四节） |
| 编排文件 | `docker-compose.prod.yml` + `deploy/docker-compose.edge.yml` |

容器（compose project `campustrade-prod`，全部 `restart: unless-stopped`）：

```
                      公网 :8080
                          │
                ┌─────────▼──────────┐
                │ campustrade-prod-  │  静态前端 / + /api/ 反代 + /img/ 反代
                │ edge (nginx)       │
                └───┬────────────┬───┘
                    │ /api/      │ /img/
        ┌───────────▼──┐   ┌─────▼────────┐
        │ app (8081)   │   │ minio (9000) │   ← 三者只在
        └──┬────────┬──┘   └──────┬───────┘     campustrade-prod-network 内可达
           │        │             │
   ┌───────▼──┐ ┌───▼─────┐ ┌─────▼──────┐
   │ postgres │ │ redis   │ │ minio 数据 │
   └──────────┘ └─────────┘ └────────────┘
```

> 同一台服务器上还跑着另一个项目（`campus-ledger-*`，占用宿主 80 端口）。
> 本项目的中间件**不发布任何宿主端口**，只发布边缘 Nginx 的 8080，因此两者互不干扰。

---

## 二、首次部署（换新服务器时照做）

```bash
# 0. 前置：Docker Engine + Compose v2；放通安全组的 SSH 端口

# 1. 取代码
sudo mkdir -p /opt && sudo chown "$USER" /opt
git clone git@github.com:kgnb666/campustrade.git /opt/campustrade

# 2. 生成生产变量（随机口令 + 按实际地址生成 CORS/图片前缀）
cd /opt/campustrade
sudo bash deploy/init-prod-env.sh <公网IP或域名> 8080
#    已有真实 SMTP 时用：
#    sudo MAIL_HOST=smtp.exmail.qq.com MAIL_USER=noreply@x.com MAIL_PASS='授权码' \
#         bash deploy/init-prod-env.sh <公网IP或域名> 8080

# 3. 准备中间件与应用镜像
sudo docker pull postgres:16.15
sudo docker pull redis:7.4.11
# MinIO：国内镜像源在 OCI referrers 接口上会超时，改用官方备用仓库 Quay
sudo docker pull quay.io/minio/minio:RELEASE.2024-10-13T13-34-11Z
sudo docker tag  quay.io/minio/minio:RELEASE.2024-10-13T13-34-11Z \
                 minio/minio:RELEASE.2024-10-13T13-34-11Z
sudo docker pull nginx:1.27-alpine
# 应用镜像：建议在开发机构建好再传（服务器拉 Maven 依赖既慢又占空间）：
#   开发机: docker build -t campustrade-backend:0.0.1 -f backend/Dockerfile backend
#           docker save -o /tmp/ct.tar campustrade-backend:0.0.1
#           scp /tmp/ct.tar <user>@<host>:~/
#   服务器: sudo docker load -i ~/ct.tar

# 4. 前端产物（开发机执行，地址必须与对外入口一致）
#    flutter build web --release --dart-define=API_BASE_URL=http://<地址>:8080/api
#    然后打包上传并解压到 /opt/campustrade/web（解压前不要删除该目录，见第五节）

# 5. 启动
sudo docker compose -f docker-compose.prod.yml -f deploy/docker-compose.edge.yml \
     --env-file /etc/campustrade/prod.env up -d
```

---

## 三、日常运维

```bash
cd /opt/campustrade
export COMPOSE="sudo docker compose -f docker-compose.prod.yml -f deploy/docker-compose.edge.yml --env-file /etc/campustrade/prod.env"

$COMPOSE ps                      # 容器状态与健康
$COMPOSE logs -f app             # 后端日志（stdout）
sudo docker logs campustrade-prod-app | tail -100          # 同上
sudo tail -f /var/lib/docker/volumes/campustrade_prod_app_logs/_data/campustrade.log
$COMPOSE restart edge            # 只重启边缘 Nginx
$COMPOSE down                    # 停栈（不加 -v，数据卷保留）
$COMPOSE up -d                   # 起栈
```

数据库临时操作（不开放端口，走容器内 psql）：

```bash
sudo docker exec -it campustrade-prod-postgres psql -U campustrade -d campustrade
```

---

## 四、发布新版本

```bash
# 服务器
cd /opt/campustrade
git pull --ff-only
sudo docker compose -f docker-compose.prod.yml -f deploy/docker-compose.edge.yml \
     --env-file /etc/campustrade/prod.env up -d
```

- **只改前端**：重新 `flutter build web` 并覆盖 `/opt/campustrade/web` 内容
  （解压到目录**内部**，不要先 `rm -rf` 目录本身，原因见第五节）。
- **改了后端代码**：镜像 tag 要与 `backend/pom.xml` 版本号（去掉 `-SNAPSHOT`）一致，
  在开发机重新构建并 `docker save/load`，然后改 `APP_IMAGE_TAG` 或直接
  `APP_IMAGE_TAG=0.0.2 $COMPOSE up -d`。
- 迁移由 Flyway 在应用启动时执行；`prod` 下 `baseline-on-migrate=false`，
  一个"非空但没有 `flyway_schema_history`"的库会拒绝启动（这是刻意设计）。

### 4.1 新增/修改环境变量时的完整流程（漏一步就会"代码是新的、行为还是旧的"）

环境变量要走**三段**才能生效，缺任何一段都会表现为"新功能不生效"：

```bash
# ① 仓库：docker-compose.prod.yml 里把变量透传给容器（形如 FOO: ${FOO:-默认值}）
cd /opt/campustrade && git pull --ff-only

# ② 生产变量文件：写入实际取值（600 权限，不进仓库）
sudo vi /etc/campustrade/prod.env      # 例：VERIFY_DEMO_MODE_ENABLED=true

# ③ 重建容器（改 env 不会自动生效，必须重建）
$COMPOSE up -d app
sudo docker exec campustrade-prod-app sh -c 'env | grep 你的变量名'   # 验证确实进去了
```

真实案例：校园认证演示模式上线时，只改了 `/etc/campustrade/prod.env` 却没在服务器
`git pull` 最新的 `docker-compose.prod.yml`，结果变量没透传进容器 —— 代码是新的、
开关却像没生效（现象是仍然去发真实邮件并失败）。

---

## 五、已知坑（踩过的，别再踩）

1. **不要把 bind mount 的目录整个删掉重建**：`edge` 容器把 `/opt/campustrade/web`
   挂进容器，删除再 `mkdir` 会让容器继续指向被删掉的旧 inode，表现为首页 403、
   静态资源 404。正确做法是把文件解压/覆盖到目录**内部**；万一已经删了，
   `$COMPOSE up -d --force-recreate edge` 重建容器即可。
2. **单文件 bind mount 改内容不生效**：同样的 inode 问题。`deploy/nginx/` 之前是
   按单文件挂载的，用 `scp` 覆盖 `campustrade.conf` 后容器里仍是旧文件
   （`nginx -t` 通过但配置没变）。现已改为挂载**整个目录**
   （`./deploy/nginx:/etc/nginx/conf.d:ro`），目录内文件的增删改都能被容器看到；
   若沿用单文件挂载，改完必须 `--force-recreate`。
3. **入口文件不能长缓存**：Flutter Web 的 `main.dart.js` 文件名**不带内容哈希**，
   早期配置给它打了 `immutable, max-age=30d`，会导致"发版后老用户永远拿到旧前端"。
   现在入口文件（`index.html` / `main.dart.js` / `flutter*.js` / `version.json` /
   `manifest.json`）统一 `no-cache`（回源校验，未变则 304），其余资源仍长缓存。
4. **compose 相对路径以"第一个 `-f` 文件所在目录"为基准**：因此
   `deploy/docker-compose.edge.yml` 里写的是 `./deploy/nginx/...`，
   并且必须在仓库根目录执行 `docker compose`。
5. **不要省掉 `--env-file`**：compose 默认读仓库根目录的 `.env`（开发口令），
   `ProdSecretsGuard` 会因此拒绝启动（这是它的设计目的）。
6. **MinIO 镜像别用国内镜像源拉**：`mirror.ccs.tencentyun.com` 在 OCI referrers
   接口上会 `dial tcp ... i/o timeout`，用 Quay 源拉完再 `docker tag` 成 compose 里的名字。
7. **SSH 传大文件会偶发中断**：传完务必校验（`ls` 数量 / `du -sh` / 直接 `curl` 一次），
   本项目第一次传前端产物就断了一半，页面 403 的根因就在这。
8. **HTTP + 公网 IP 会击穿 Web 的 secure storage**：`flutter_secure_storage` 在 Web 上
   依赖 `window.crypto.subtle`，该 API 只在安全上下文（HTTPS 或 localhost）可用。
   站点以 `http://<公网IP>:8080` 提供时，Token 存不进去，`DioClient` 取不到 Token，
   所有认证请求变匿名 → 401 → 被"会话失效"流程踢回登录页（现象："能登录，一点就弹回"）。
   前端 `StorageService` 已改为三级降级（内存 → 安全存储 → SharedPreferences/localStorage），
   HTTP 部署下也能保存登录态；**但生产仍建议上 HTTPS**（安全存储可用即不触发降级）。
9. **nginx 的 location 优先级会悄悄截走 `/img/` 与 `/api/`**：匹配顺序是
   「最长前缀 location → 正则 location」，所以后写的静态资源正则
   `location ~* \.(png|jpg|js|json|...)$` **优先于**前缀 location `/img/`、`/api/`。
   症状是「MinIO 直连 200、经边缘却 404」，且只在带扩展名的路径上出现
   （`/img/campustrade/` 列表反而正常，很容易误判成 MinIO 问题）。
   本项目的 `/img/` 与 `/api/` 已加 `^~` 前缀修饰符（命中前缀即不再尝试正则）。

---

## 六、上线待办

- [ ] **安全组放通 8080**（腾讯云控制台 → 安全组/防火墙 → 入站规则 TCP 8080），
      否则外网访问 `http://129.204.61.68:8080` 会一直超时（服务器内部正常）。
- [ ] **替换真实 SMTP**：当前 `/etc/campustrade/prod.env` 里是占位发信账号，
      服务能启动，但**校园认证验证码发不出去**（白名单邮箱走演示通道，见第七节）。
      换成真实账号后 `$COMPOSE up -d --force-recreate app` 生效。
- [ ] **HTTPS**：需要一个已解析的域名（大陆服务器还需 ICP 备案），
      之后在本目录加 certbot 或走腾讯云 SSL 证书 + Nginx 443。
- [ ] **数据库备份**：目前没有任何自动备份机制（数据卷 ≠ 备份）。建议每日
      `pg_dump -Fc` 到异地/对象存储，并定期做恢复演练。
- [ ] **Redis 明文 refresh token 清理**：本项目上线时的 Redis 是空的，无需清理；
      若将来从旧环境迁移数据，按根 README「2.3」执行一次。
- [ ] **SSD 参数**：已在生产库执行 `ALTER DATABASE campustrade SET random_page_cost = 1.1`
      （对新建连接生效）。

---

## 七、校园认证演示模式（当前：已开启）

**背景**：生产环境的 SMTP 还是占位账号，真实验证码发不出去；而校园认证是"发布商品的硬前置"，
不打通就没法完整演示。演示模式让**白名单内**的邮箱跳过真实邮件，验证码随
`POST /student/verify` 的响应返回，前端自动填入并标注"演示模式"。

**当前白名单**（2026-09-22 开启，5 所学校的后缀各一条，本地部分统一用 `demo-verify`，
一眼能看出是演示号、也不可能属于任何真实学生）：

| 学校 | 演示邮箱 |
| --- | --- |
| 广西民族师范学院 | `demo-verify@gxnun.edu.cn` |
| 清华大学 | `demo-verify@mails.tsinghua.edu.cn` |
| 北京大学 | `demo-verify@pku.edu.cn` |
| 复旦大学 | `demo-verify@fudan.edu.cn` |
| 浙江大学 | `demo-verify@zju.edu.cn` |

**怎么演示**：登录 → 个人中心 → 校园认证 → 选对应学校 + 填学号 + 填上表里同后缀的演示邮箱 →
提交后验证码会直接提示并自动填入 → 提交即认证成功。

**怎么关闭**（演示结束后建议关掉）：

```bash
sudo sed -i 's/^VERIFY_DEMO_MODE_ENABLED=.*/VERIFY_DEMO_MODE_ENABLED=false/' /etc/campustrade/prod.env
sudo sed -i 's/^VERIFY_DEMO_EMAILS=.*/VERIFY_DEMO_EMAILS=/' /etc/campustrade/prod.env
cd /opt/campustrade && $COMPOSE up -d app
```

**安全边界（已实测）**：白名单之外的邮箱链路一个字节没变——仍要过学校后缀校验、仍走真实邮件、
发送失败不降级。开启期间每次启动都会有一条点名白名单的 WARN 日志（`VerifyDemoGuard`），
"忘了关"能被立刻发现；把开关打开却不写白名单会直接拒绝启动。
