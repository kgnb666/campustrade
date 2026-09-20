# CampusTrade 校园二手交易平台 最终审计问题分阶段修复提示词工程指南

> **文档标识**：`docs/final-audit/Fix-Prompts-By-Stage.md`  
> **编制角色**：平台首席架构师 + 资深后端工程师 + 安全审计专家  
> **编制日期**：2026-09-18  
> **指导思想**：`分阶治理、最小侵入、测试护航、绝不破坏已有 189 个测试闭环`

---

## 阶段规划总览

针对生产级最终审计发现的 **3 项 P1 缺陷** 与 **8 项 P2 缺陷**，划分为 4 个高度聚焦、依赖清晰的实施阶段：

```text
Stage Fix-1: 核心安全与高并发一致性修复 (P1 阻断项)
     │   • SEC-01: 冻结账号 JWT 拦截
     │   • BIZ-01: 信用积分并发更新丢失 (Lost Update)
     │   • ARCH-01: Refresh Token 刷新端点闭环
     ▼
Stage Fix-2: 后端工程质量与高可用加固 (P2 后端项)
     │   • SEC-03: 图片上传 Magic Bytes 魔数校验
     │   • BIZ-02: 浏览量同步消除 KEYS * 阻塞
     │   • ARCH-03: Context-Path 与 Controller 路由规范化
     ▼
Stage Fix-3: 移动端网络层与会话无感刷新 (P2 Flutter 项)
     │   • FLUTTER-01: 401 自动刷新 Token 与失效跳转登录
     ▼
Stage Fix-4: 生产运维就绪与可观测性加固 (P2 运维项)
         • PROD-01: Logback 滚动归档与 MDC TraceId 链路跟踪
         • PROD-02: Actuator 容器探针与 Prometheus 监控
         • PROD-03: Docker 资源限制与 Nginx HTTPS/限流模版
```

---

# Stage Fix-1 修复提示词：核心安全与高并发一致性修复 (P1 阻断项)

```markdown
你现在作为 CampusTrade 校园二手交易平台的资深后端工程师与安全专家。

当前任务：进入 **Stage Fix-1：核心安全与高并发一致性修复阶段**。

本阶段必须优先解决生产审计中发现的 3 个最高危 P1 级别缺陷：
1. 【SEC-01】被冻结（FROZEN）用户在有效 JWT 过期前绕过过滤器的安全漏洞；
2. 【BIZ-01】用户信用积分高并发更新丢失（Lost Update）数据一致性风险；
3. 【ARCH-01】缺少 /auth/refresh 刷新令牌端点的机制断裂问题。

---

### 一、修改与加固要求

#### 1. 修复 SEC-01：加固 `JwtAuthenticationFilter.java`
- 文件路径：`backend/src/main/java/com/campustrade/security/JwtAuthenticationFilter.java`
- 逻辑要求：
  - 在 `userDetailsService.loadUserByUsername(username)` 返回 `UserDetails` 后，**显式增加账户启用状态校验**：
    `if (!userDetails.isEnabled()) { ... }`；
  - 若 `!userDetails.isEnabled()`，直接返回 HTTP 403 状态码与统一 JSON 结构（`Result.error(403, "账号已被冻结或禁用，请联系平台管理员")`），并执行 `return` 阻断过滤链，严禁注入 `SecurityContext`。

#### 2. 修复 BIZ-01：消解 `CreditServiceImpl` 的并发写覆盖隐患
- 文件路径：
  - `backend/src/main/java/com/campustrade/mapper/UserCreditMapper.java`
  - `backend/src/main/java/com/campustrade/service/impl/CreditServiceImpl.java`
- 逻辑要求：
  - 在 `UserCreditMapper` 中增加原子增减 SQL 方法，直接基于数据库行锁更新积分与统计字段，杜绝在 Java 内存中算好分数再覆盖整行；
  - SQL 示例：
    ```sql
    UPDATE campus_trade.user_credit 
    SET credit_score = LEAST(200, GREATEST(0, credit_score + #{delta})),
        credit_level = #{level},
        completed_count = completed_count + #{completedDelta},
        trade_count = trade_count + #{tradeDelta},
        good_review_count = good_review_count + #{goodReviewDelta},
        bad_review_count = bad_review_count + #{badReviewDelta},
        updated_time = CURRENT_TIMESTAMP 
    WHERE user_id = #{userId}
    ```
  - 或者在查询信用行时采用悲观行锁 `SELECT ... FOR UPDATE`，并在更新完成后正确插入 `UserCreditLog` 审计流水。

#### 3. 修复 ARCH-01：闭环 Refresh Token 机制
- 文件路径：
  - `backend/src/main/java/com/campustrade/dto/RefreshTokenRequest.java` (新入参 DTO)
  - `backend/src/main/java/com/campustrade/controller/AuthController.java`
  - `backend/src/main/java/com/campustrade/service/AuthService.java`
  - `backend/src/main/java/com/campustrade/service/impl/AuthServiceImpl.java`
- 逻辑要求：
  - 新增请求 DTO：`RefreshTokenRequest`，包含 `@NotBlank String refreshToken`；
  - 在 `AuthController` 暴露 `POST /auth/refresh`（及 `/api/auth/refresh`）公开接口（在 `SecurityConfig` 中加入 `permitAll`）；
  - 在 `AuthServiceImpl` 中校验：
    1. 校验 Token 签名合法且未过期；
    2. 提取 `userId`，比对 Redis 中存储的 `REFRESH_TOKEN_PREFIX + userId` 是否一致；
    3. 校验用户状态必须为 `ACTIVE`；
    4. 重新颁发崭新的 2 小时 Access Token 并返回（可选轮转 Refresh Token）。

---

### 二、严格红线与回归测试

1. 严禁改动任何已有 Flyway 脚本（V1~V8 保持只读）；
2. 保持全站统一 `Result<T>` 响应契约；
3. 编写专项测试用例 `CampusTradeFixStage1Tests.java`：
   - 验证：被冻结用户即使携带合法未过期 JWT 请求接口，也被拦截返回 403；
   - 验证：多线程高并发同时增加同一用户积分，最终分值准确等于初始值+总增加值，绝无写丢失；
   - 验证：合法 Refresh Token 成功换取新 Access Token，伪造或过期的 Refresh Token 被拒绝；
4. 运行全量后端测试：确保原有 189 个测试 100% 全部保持绿灯（0 Failures, 0 Errors）。
```

---

# Stage Fix-2 修复提示词：后端工程质量与高可用加固 (P2 后端项)

```markdown
你现在作为 CampusTrade 校园二手交易平台的资深后端工程师。

当前任务：进入 **Stage Fix-2：后端工程质量与高可用加固阶段**。

本阶段处理生产审计报告中的后端 P2 缺陷与代码规范问题：
1. 【SEC-03】图片文件上传增加二进制文件头（Magic Bytes）校验；
2. 【BIZ-02】商品浏览量同步彻底废除阻塞式的 `redisTemplate.keys("*")`；
3. 【ARCH-03】清理 Context-Path 与 Controller 路由双重映射冗余。

---

### 一、修改与加固要求

#### 1. 修复 SEC-03：加固 `FileServiceImpl.java` 二进制安全
- 文件路径：`backend/src/main/java/com/campustrade/service/impl/FileServiceImpl.java`
- 逻辑要求：
  - 文件上传除校验文件扩展名与 Content-Type 外，增加基于文件流前 8 字节（Magic Bytes）的格式比对，确保上传的文件真实为 JPEG、PNG 或 WebP 图像：
    - JPEG 魔数：`FF D8 FF`
    - PNG 魔数：`89 50 4E 47 0D 0A 1A 0A`
    - WebP 魔数：前 4 字节为 `RIFF` (52 49 46 46)，第 8~11 字节为 `WEBP` (57 45 42 50)
  - 若文件流魔数不匹配，立即抛出 `BusinessException(400, "文件内容不是有效的图片格式")`，彻底杜绝伪装脚本上传。

#### 2. 修复 BIZ-02：重构 `GoodsServiceImpl.java` 浏览量同步逻辑
- 文件路径：`backend/src/main/java/com/campustrade/service/impl/GoodsServiceImpl.java`
- 逻辑要求：
  - 彻底删除 `redisTemplate.keys(VIEW_KEY_PREFIX + "*")` 这类 $O(N)$ 阻塞主线程的命令；
  - 方案设计：
    - 在用户每次浏览商品时，除执行自增计数外，顺带将该 `goodsId` 加入一个 Redis Set：
      `stringRedisTemplate.opsForSet().add("goods:views:dirty_ids", goodsId.toString())`；
    - 在 `syncViewCounts()` 定时任务中，通过 `opsForSet().pop("goods:views:dirty_ids", 100)` 批量弹出需要同步的商品 ID，直接按 ID 读取计数值并落盘；
    - 确保任何场景下都不会对全库 Key 进行模式扫描。

#### 3. 修复 ARCH-03：路由注解清理与 `@CurrentUser` 参数解析器抽离
- 优化控制器路由注解：
  - 统一规范各 Controller 上的 `@RequestMapping`，清理冗余的数组写法（由于已全局配置 `context-path: /api`，Controller 统一使用 `@RequestMapping("/orders")` 等单一规范）；
- 自定义 `@CurrentUser` 注解：
  - 新建注解 `@Target(ElementType.PARAMETER) @Retention(RetentionPolicy.RUNTIME) public @interface CurrentUser {}`；
  - 实现 `HandlerMethodArgumentResolver`，自动提取已登录 `User` 实体注入 Controller 参数，彻底消除各个 Controller 中重复编写的私有 `getCurrentUser()` 样板代码。

---

### 二、严格红线与回归测试

1. 保持 MinIO 存储路径兼容，不影响现有已上传图片访问；
2. 保持对外 API 请求路径与参数完全向前兼容；
3. 执行全量测试套件，保证全部测试用例 100% 通过。
```

---

# Stage Fix-3 修复提示词：移动端网络层与会话无感刷新 (P2 Flutter 项)

```markdown
你现在作为 CampusTrade 校园二手交易平台的资深 Flutter 前端工程师。

当前任务：进入 **Stage Fix-3：移动端网络层与会话无感刷新优化阶段**。

本阶段必须解决生产审计报告中的 Flutter P2 缺陷：
1. 【FLUTTER-01】DioClient 缺少对 HTTP 401 / Token 失效的自动重定向与凭据清理；
2. 配合后端 Stage Fix-1 交付的 `/api/auth/refresh` 接口，实现客户端“无感刷新 Token（Silent Refresh）”。

---

### 一、修改与优化要求

#### 1. 加固 `frontend/lib/api/dio_client.dart` 错误拦截器
- 文件路径：`frontend/lib/api/dio_client.dart`
- 逻辑要求：
  - 在 `onError(DioException e, handler)` 中增加对 401 状态码的处理：
    ```dart
    if (e.response?.statusCode == 401) {
      // 1. 尝试使用本地存储的 Refresh Token 进行无感静默刷新
      final refreshed = await _tryRefreshToken();
      if (refreshed) {
        // 刷新成功：更新请求头并重新发起上一次失败的原请求
        final retryResponse = await _retryRequest(e.requestOptions);
        return handler.resolve(retryResponse);
      } else {
        // 刷新失败或 Refresh Token 也已过期：彻底清空本地缓存并跳转登录
        await storage.clearAll();
        getx.Get.offAllNamed(AppRoutes.LOGIN);
        getx.Get.snackbar('登录已过期', '请重新登录以继续使用',
            snackPosition: getx.SnackPosition.TOP);
        return handler.next(e);
      }
    }
    ```
  - 防并发刷新竞争：在 `_tryRefreshToken` 中增加互斥锁（Mutex / Completer），当多个并发请求同时遭遇 401 时，保证只向服务端发起一次 `/auth/refresh` 请求，后续请求等待该刷新完成并复用新 Token。

#### 2. 完善用户退出登录清理机制
- 在 `AuthController.logout()` 中，同步调用 `storageService.clearAll()` 清空本地持久化的 `token`、`refreshToken` 与用户信息，确保登出后无任何残留状态泄露。

---

### 二、严格红线与验证

1. 遵循 GetX 状态管理规范，不破坏现有页面组件与路由；
2. 验证：当 Access Token 过期时，界面不再卡死，能够平滑重试并成功获取数据；
3. 验证：当刷新凭据彻底失效时，应用能够平滑优雅地弹出提示并跳转回登录页面。
```

---

# Stage Fix-4 修复提示词：生产运维就绪与可观测性加固 (P2 运维项)

```markdown
你现在作为 CampusTrade 校园二手交易平台的生产运维架构师与 SRE 专家。

当前任务：进入 **Stage Fix-4：生产运维就绪与可观测性加固阶段**。

本阶段必须完成生产审计中规划的上线前运维基建配置：
1. 【PROD-01】补充 `logback-spring.xml` 磁盘滚动归档与 MDC TraceId 链路跟踪；
2. 【PROD-02】引入 Spring Boot Actuator 健康探针与 Prometheus 监控依赖；
3. 【PROD-03】加固 `docker-compose.yml` 容器资源上限限制；
4. 【PROD-04】编写生产级 Nginx 反向代理配置与自动化 `pg_dump` 备份脚本。

---

### 一、实施与加固要求

#### 1. 引入 Actuator 依赖与可观测性探针
- 文件路径：`backend/pom.xml`, `backend/src/main/resources/application.yml`
- 要求：
  - 引入 `org.springframework.boot:spring-boot-starter-actuator` 依赖；
  - 在 `application.yml` 中配置：
    ```yaml
    management:
      endpoints:
        web:
          exposure:
            include: health,info,metrics,prometheus
      endpoint:
        health:
          show-details: when-authorized
          probes:
            enabled: true
    ```
  - 确保 `/actuator/health` 探针正常返回 `{"status":"UP"}`，可供 Docker / K8s 健康检查调用。

#### 2. 完善磁盘滚动日志与分布式 TraceId（MDC）
- 新建配置文件：`backend/src/main/resources/logback-spring.xml`
- 逻辑要求：
  - ConsoleAppender（保留控制台彩色输出）；
  - RollingFileAppender（`logs/campustrade-info.log` 与 `logs/campustrade-error.log`）：按天滚动并按 100MB 切分，保留 30 天，最大归档体积 10GB；
  - 在 `JwtAuthenticationFilter` 中每次请求生成短 UUID 放入 `MDC.put("traceId", ...)`，并在日志 Pattern 中输出 `[%X{traceId}]`。

#### 3. 加固 `docker-compose.yml` 容器资源配额
- 文件路径：`docker-compose.yml`
- 要求：
  - 为 `postgres`、`redis`、`minio` 服务增加资源配额限制，防止容器内存泄露引发宿主机 OOM Crash：
    ```yaml
    deploy:
      resources:
        limits:
          cpus: '2.0'
          memory: 2048M
        reservations:
          memory: 512M
    ```

#### 4. 交付生产级 Nginx 反向代理与数据库备份脚本
- 新建文件：`docker/nginx/campustrade.conf`
  - 包含 SSL/TLS 1.2+ 证书配置、Gzip 静态资源压缩、反向代理与指纹隐藏（`server_tokens off`）；
  - 包含全局 IP 限流（`limit_req_zone $binary_remote_addr zone=api_limit:10m rate=20r/s`）与登录防暴力破解限流（`rate=5r/m`）；
- 新建文件：`docker/postgres/backup.sh`
  - 基于 `pg_dump` 编写每日定时导出 `.sql.gz` 备份的 Shell 脚本，带时间戳命名，并自动清理 7 天前历史备份。

---

### 二、严格红线与验证

1. 保证本地开发环境无需额外繁重配置即可平滑运行；
2. 执行 `mvn test` 保证全量自动化测试 100% 绿灯通过；
3. 验证 Actuator `/actuator/health` 端点在未经管理员认证时安全且能正确汇报数据库与 Redis 连通性。
```
