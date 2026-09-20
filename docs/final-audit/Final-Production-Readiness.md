> ⚠️ **历史报告（修复前快照，结论已过时）**
>
> 本文产生于 2026-09-20 上午的审计，**早于随后完成的 Stage 1–8 加固**。
> 文中诸如「189/189 测试通过」「Flyway V1–V8」「init.sql 与迁移 100% 同步」「裁决 NEEDS OPTIMIZATION」
> 等结论**均已被推翻**，请勿作为项目现状依据。
>
> 当前状态请以以下为准：
> - `README.md` / `docs/README.md`（已校正的阶段、端口、变量、门禁与部署说明）
> - Stage 1–8 的提交记录（工程地基 → 认证安全 → 校园认证 → 数据一致性 → 契约收敛 → 前端稳定性 → 性能 → 生产交付）
> - 测试基线：**后端 255 项、前端 176 项**（阶段 8 时为 242 / 142；见根目录 `CHANGELOG.md`）
>
> 修复过程与结论见同目录 `Stage-Fix-*.md`。

---

# CampusTrade 校园二手交易平台 生产级最终审查：生产上线准备报告 (Production Readiness)

> **文档标识**：`docs/final-audit/Final-Production-Readiness.md`  
> **审查主体**：生产运维总架构师与安全专家团队  
> **审查基准**：模拟明日正式上线生产环境标准  
> **审计结论**：**NEEDS OPTIMIZATION (业务代码就绪，需补齐运维与生产级配置后放行)**

---

## 一、生产上线核对清单（Production Checklist）全景

| 序号 | 核查领域 | 生产标准要求 | 当前工程现状 | 满足度 | 生产整改建议 |
| :---: | :--- | :--- | :--- | :---: | :--- |
| 1 | **环境变量与秘钥** | 严禁弱口令，秘钥由 Secrets 注入 | YAML 中存在硬编码默认口令 | **需加固** | 移除硬编码，启动脚本强制断言生产环境变量 |
| 2 | **日志系统** | 滚动磁盘持久化归档，MDC 链路追踪 | 仅配置控制台标准输出（Console） | **需加固** | 补充 `logback-spring.xml` 滚动归档与 TraceId |
| 3 | **可观测性与监控** | Actuator 健康检查与 Prometheus 探针 | 未引入 `spring-boot-starter-actuator` | **待补充** | 补充 Actuator 依赖与 `/health` 端点配置 |
| 4 | **数据库备份机制** | 每日定时快照全量与增量 WAL 归档 | 依赖 Docker Volume，无自动脚本 | **待补充** | 编写 `pg_dump` 定时归档脚本与异地备份 |
| 5 | **容器资源限制** | Docker 配置 Memory 与 CPU Limit | `docker-compose.yml` 未设资源上限 | **需加固** | 增加 `mem_limit: 2g`, `cpus: '2.0'` 防止 OOM |
| 6 | **网络与 HTTPS** | 全站强制 HTTPS、反向代理与指纹隐藏 | 后端裸跑 8080 端口 HTTP | **待配置** | 部署 Nginx 反向代理配置 SSL 证书与 Gzip |
| 7 | **对象存储与 CDN** | 静态图片加速，管理控制台内网隔离 | 依赖本地 `http://127.0.0.1:9000` | **需切换** | 切换公网可访问 CDN 域名，9001 端口内网收口 |
| 8 | **网关与防刷限流** | 登录防爆破、防恶意爬虫与 DDoS | 仅应用层针对举报接口有限频 | **需加固** | Nginx 配置 `limit_req_zone` 限制单 IP 频次 |

---

## 二、生产级关键加固方案指南

### 1. 生产环境变量注入与秘钥脱敏
当前 `application.yml` 中定义了大量带默认值的配置项（如 `campustrade123`，`JWT_SECRET`）。在生产部署环境（Kubernetes 或云主机生产 Docker），**必须通过操作系统环境变量覆盖全部默认配置**：
```bash
# 生产环境强制覆盖变量模板
export SPRING_DATASOURCE_HOST=prod-postgres-cluster
export SPRING_DATASOURCE_PASSWORD=StrongPassword_Generated_9381!@
export SPRING_DATA_REDIS_PASSWORD=RedisSecret_92812!
export JWT_SECRET=ProdSecretKey_Cryptographically_Secure_Random_256Bits_Min_Len_2026
export MINIO_ROOT_PASSWORD=MinioSecretStrong_8192!
export DEEPSEEK_API_KEY=sk-prod-real-key-here
```
并在启动脚本中检查，若环境变量为空或为默认值，直接拒绝启动。

### 2. 日志滚动归档与链路追踪（TraceId）
目前 `application.yml` 仅将日志输出至终端，容器一旦重启历史日志即面临丢失风险。生产环境需提供 `logback-spring.xml`：
- **按天滚动存储**：`app-info.log` 与 `app-error.log`，单文件上限 100MB，保留周期 30 天；
- **MDC 链路追踪**：在 `JwtAuthenticationFilter` 中生成 `UUID.randomUUID()` 存入 `MDC.put("traceId", ...)`，并在日志 pattern 中打印 `[%X{traceId}]`，以便在排查故障时能够单线跟踪用户全流程调用。

### 3. 可观测性（Metrics & Actuator）
建议在 `pom.xml` 中引入：
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```
暴露 `/actuator/health` 探针供 Docker / K8s 存活检查（Liveness/Readiness Probe），保护平台在高负载时自动摘除异常实例。

### 4. 生产级 Nginx 反向代理配置范例
生产环境严禁直接将 Spring Boot Tomcat 8080 暴露给公网。需前置部署 Nginx 反向代理：
```nginx
# /etc/nginx/conf.d/campustrade.conf
limit_req_zone $binary_remote_addr zone=api_limit:10m rate=20r/s;
limit_req_zone $binary_remote_addr zone=login_limit:10m rate=5r/m;

server {
    listen 443 ssl http2;
    server_name api.campustrade.com;

    ssl_certificate /etc/nginx/ssl/campustrade.crt;
    ssl_certificate_key /etc/nginx/ssl/campustrade.key;

    # 隐藏服务端真实指纹
    server_tokens off;

    # 登录防爆破限流
    location /api/auth/login {
        limit_req zone=login_limit burst=3 nodelay;
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }

    # 全局 API 反向代理
    location / {
        limit_req zone=api_limit burst=50 nodelay;
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

### 5. MinIO 生产收敛与安全边界
- MinIO 控制台端口 `9001` **严禁映射至宿主机 `0.0.0.0:9001`**，必须绑定内网 `127.0.0.1:9001` 或关闭对外端口，仅允许内网运维人员通过 SSH 隧道访问；
- 图片访问地址 `minio.url-prefix` 切换为公网 CDN 域名并开启全站缓存，极大减轻对象存储主机的流量带宽压力。
