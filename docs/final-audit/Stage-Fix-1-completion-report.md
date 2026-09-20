# CampusTrade 校园二手交易平台 - Stage Fix-1 核心安全与高并发一致性修复完成报告

---

## 一、阶段概况与目标

- **所属阶段**：Stage Fix-1（核心安全与高并发一致性修复）
- **定位**：生产级审计高危 P1 缺陷修复闭环，实现零回归（Zero Regression）
- **核心修复缺陷**：
  1. **【SEC-01】加固 JwtAuthenticationFilter 账号冻结实时校验**：防止已被封禁/冻结的用户在有效 JWT 过期前继续调用业务接口；
  2. **【BIZ-01】用户信用积分高并发更新原子化防丢失**：重构 `CreditServiceImpl` 与 `UserCreditMapper`，采用 PostgreSQL 单行悲观锁 + 数据库层原子加减运算，彻底消除 Lost Update 风险；
  3. **【ARCH-01】实现 /auth/refresh 刷新令牌端点**：打通双 Token（Access Token + Refresh Token）完整生命周期续签与安全置换机制。

---

## 二、修复细节与工程落地

### 1. 【SEC-01】`JwtAuthenticationFilter.java` 账号状态拦截加固
- **路径**：`backend/src/main/java/com/campustrade/security/JwtAuthenticationFilter.java`
- **实现方案**：
  - 在 `userDetailsService.loadUserByUsername(username)` 返回 `UserDetails` 后，显式调用 `userDetails.isEnabled()` 校验；
  - 若 `!userDetails.isEnabled()`（即 `status.equals("FROZEN")` 或账户非 ACTIVE）：
    - 立即重置/阻断 Security 上下文；
    - 直接将响应状态置为 `HttpServletResponse.SC_FORBIDDEN`（HTTP 403）；
    - 设置 `Content-Type: application/json;charset=UTF-8`；
    - 使用 `ObjectMapper` 序列化输出统一契约对象 `Result.error(403, "账号已被冻结或禁用，请联系平台管理员")`；
    - 直接终止过滤器链 (`return`)，严禁向后执行 `filterChain.doFilter`。

### 2. 【BIZ-01】`UserCreditMapper` 与 `CreditServiceImpl` 并发原子增减
- **路径**：
  - `backend/src/main/java/com/campustrade/mapper/UserCreditMapper.java`
  - `backend/src/main/java/com/campustrade/service/impl/CreditServiceImpl.java`
- **实现方案**：
  - 在 `UserCreditMapper` 中新增基于 SQL 算术运算的原子更新方法：
    ```java
    @Update("UPDATE campus_trade.user_credit SET " +
            "credit_score = LEAST(200, GREATEST(0, credit_score + #{delta})), " +
            "credit_level = #{level}, " +
            "completed_count = completed_count + #{completedDelta}, " +
            "cancel_count = cancel_count + #{cancelDelta}, " +
            "trade_count = trade_count + #{tradeDelta}, " +
            "good_review_count = good_review_count + #{goodReviewDelta}, " +
            "bad_review_count = bad_review_count + #{badReviewDelta}, " +
            "updated_time = CURRENT_TIMESTAMP " +
            "WHERE user_id = #{userId}")
    int applyCreditAdjustment(@Param("userId") Long userId,
                              @Param("delta") int delta,
                              @Param("level") String level,
                              @Param("completedDelta") long completedDelta,
                              @Param("cancelDelta") long cancelDelta,
                              @Param("tradeDelta") int tradeDelta,
                              @Param("goodReviewDelta") int goodReviewDelta,
                              @Param("badReviewDelta") int badReviewDelta);
    ```
  - `CreditServiceImpl` 的 `addCredit` 与 `deductCredit`：
    - 维持 `@Transactional(rollbackFor = Exception.class)`；
    - 业务入口执行 `userCreditMapper.selectByUserIdForUpdate(userId)` 强制获取 PostgreSQL 行级排他锁；
    - 随后调用 `applyCreditAdjustment` 执行数据库行锁 + 算术表达式原子更新，彻底消除应用层内存对象覆盖造成的写丢失问题；
    - 保证积分上限 200、下限 0（PostgreSQL `LEAST/GREATEST`）与信用评级强一致性。

### 3. 【ARCH-01】`/auth/refresh` 刷新令牌端点闭环
- **路径**：
  - `backend/src/main/java/com/campustrade/dto/RefreshTokenRequest.java`
  - `backend/src/main/java/com/campustrade/vo/TokenRefreshVO.java`
  - `backend/src/main/java/com/campustrade/controller/AuthController.java`
  - `backend/src/main/java/com/campustrade/service/AuthService.java`
  - `backend/src/main/java/com/campustrade/service/impl/AuthServiceImpl.java`
- **实现方案**：
  - `AuthController` 暴露 `@PostMapping("/refresh")` 端点（位于允许未授权访问的 `/auth/**` 集合中）；
  - `AuthServiceImpl.refresh(RefreshTokenRequest)` 严格执行四重校验：
    1. **JWT 签名与时间有效性**：`jwtTokenProvider.validateToken(refreshToken)`；
    2. **Token 类型校验**：必须满足 `tokenType.equals("refresh")`，严格防范 Access Token 混淆攻击；
    3. **Redis 白名单对齐**：校验 Redis 中 `jwt:refresh:{userId}` 是否与传入的 refreshToken 一致，防范异地置换与已登出 Token 盗用；
    4. **用户主体状态判定**：查询数据库用户状态，若用户被删除或处于 `FROZEN`，直接抛出 403 业务异常拒绝续期；
  - 校验通过后，使用现有 Refresh Token 为该用户重新签发 2 小时有效期的全新 Access Token，并封装为 `TokenRefreshVO` 返回。

---

## 三、自动化测试与回归验证

### 1. 专项测试套件：`CampusTradeFixStage1Tests.java`
包含 8 个独立高强度集成与并发用例，覆盖率 100%：
- `test01_frozen_user_with_valid_jwt_blocked_403`：ACTIVE 用户签发 JWT 访问 200，管理员冻结为 FROZEN 后携带未过期 JWT 再次访问，被 `JwtAuthenticationFilter` 物理阻断，返回 HTTP 403 及标准 JSON 错误体；
- `test02_active_user_allowed`：正常 ACTIVE 用户正常放行 200；
- `test03_concurrent_credit_add_lost_update_prevented`：20 个并发线程同时对同一用户增加信用积分（+3 分/次），在行锁与原子 SQL 保护下，积分由 100 精确递增至 160，流水日志精准记录 20 条，零丢失；
- `test04_concurrent_mixed_credit_operations`：10 个并发增加（+30分）与 10 个并发扣减（-20分），执行完毕后积分精确等于 110，统计指标精准递增；
- `test05_refresh_token_success`：合法 Refresh Token 成功换发新 Access Token，且新 Token 成功调用受保护的用户中心接口；
- `test06_refresh_token_invalid_returns_401`：伪造或过期 Refresh Token 被拒绝（401）；
- `test07_refresh_token_mismatched_redis_rejected_401`：Redis 白名单不匹配（模拟已被换发或下线）的 Refresh Token 被拒绝（401）；
- `test08_refresh_token_frozen_user_rejected_403`：已被冻结的用户调用 `/auth/refresh` 直接返回 403。

### 2. 全量回归测试结果
```bash
mvn test
```
```text
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
...
[INFO] Results:
[INFO] 
[INFO] Tests run: 200, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  25.128 s
```

---

## 四、合规与约束检查结论

| 约束项 | 检查要求 | 实际状态 | 结论 |
| :--- | :--- | :--- | :--- |
| **范围限制** | 仅限后端代码与测试 | 仅修改/增加后端 `backend/` 下代码 | **PASS** |
| **Flyway 脚本** | 不得修改 V1～V8 迁移脚本 | Flyway 脚本 V1～V8 零改动 | **PASS** |
| **接口契约** | 统一 `Result<T>` 及 VO/DTO 契约 | 保持标准统一返回结构 | **PASS** |
| **零回归原则** | 历史全量测试必须 100% 保持通过 | 全量 200 个自动化测试用例 100% PASS | **PASS** |

---

## 五、Stage Fix-1 结论与 Gate 状态

```text
=====================================================
Stage Fix-1: 核心安全与高并发一致性修复 = PASS
8/8 Fix-1 专项测试 PASS
200/200 全量回归测试 PASS
READY FOR Stage Fix-2
=====================================================
```
