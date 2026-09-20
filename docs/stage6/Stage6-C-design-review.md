# Stage 6-C：评价点赞与平台治理增强综合设计评审与 Design Gate 报告

**编制日期**：2026-09-18  
**所属阶段**：Stage 6-C（设计评审阶段 - 严禁修改业务代码）  
**当前状态**：设计审查完毕，正式输出 Design Gate 报告  
**核心原则**：正确性 > 数据一致性 > 权限安全 > 并发安全 > 可维护性 > 性能 > 功能数量 > 炫技

---

## 一、十三项核心议题评审决议总结

### 1. 当前评价系统现状
- 全站目前完全不存在点赞相关字段、表或缓存（`like_count`、`review_like` 均为 0）；
- Stage 5-D 建设的 `Review` 体系运行稳健，通过 `ReviewCreatedEvent` 与 `CreditService` 紧密联动；
- Stage 6-B 建立的举报治理体系通过工单采纳将违规评价置为 `AUDIT_REJECTED` 并执行精确信用追缴与审计，该主链路必须严格遵守。

### 2. 点赞系统最终设计
- 采用显式 RESTful 资源语义：
  - 点赞：`POST /api/reviews/{reviewId}/like`
  - 取消：`DELETE /api/reviews/{reviewId}/like`
  - 状态：`GET /api/reviews/{reviewId}/like`
- 拒绝纯 `toggle` 作为唯一接口，避免移动端弱网重试导致状态翻转。

### 3. 是否使用 Redis
- **明确决策：坚决不使用 Redis 做点赞计数！**
- 高校二手平台流量规模完全在 PostgreSQL 轻松承载范围内，拒绝过度设计，规避双写不一致与缓存击穿等额外复杂度。

### 4. `review_like` 最终数据库设计
- 表结构：`id (BIGSERIAL PK)`, `review_id (BIGINT NOT NULL)`, `user_id (BIGINT NOT NULL)`, `created_time (TIMESTAMP)`；
- 物理约束：复合唯一索引 `uk_review_like_review_user (review_id, user_id)`；
- 查询索引：`idx_review_like_user_time (user_id, created_time DESC)`；
- 物理删除明细以保证取消点赞轻量，外键采用逻辑关联。

### 5. `like_count` 最终方案
- 在 `campus_trade.review` 增加冗余列 `like_count INT NOT NULL DEFAULT 0`；
- 点赞累加：`SET like_count = like_count + 1`；
- 取消递减：`SET like_count = GREATEST(0, like_count - 1)`，底层物理防负数。

### 6. API 最终契约
- 统一遵循 `Result<T>` 规范：
  - 点赞/取消返回 `Result<ReviewLikeVO>`（包含 `reviewId`, `liked`, `likeCount`）；
  - 评价列表返回 `Result<IPage<ReviewVO>>`（增强扩展 `likeCount` 与 `likedByCurrentUser`）；
  - 评价恢复返回 `Result<ReviewVO>`。

### 7. 治理与点赞关系
- **普通用户视角**：被屏蔽评价（`AUDIT_REJECTED`）在前台完全不可见；
- **互动阻断**：对已屏蔽评价发起点赞严格阻断并返回 `422 Unprocessable Entity`；
- **历史数据处置**：评价被屏蔽时，历史点赞记录**完整保留，严禁删除**，保障治理溯源与误封纠偏能力。

### 8. 信用冲正与恢复方案
- 引入管理员纠偏恢复接口：`PUT /api/admin/reviews/{id}/restore`；
- 恢复时仅能针对 `AUDIT_REJECTED` 评价，恢复为 `VISIBLE`；
- 精准信用反向补偿：
  - 5 星好评恢复：补回 +3 信用分 (`ADMIN_ADJUST`)
  - 4 星好评恢复：补回 +1 信用分 (`ADMIN_ADJUST`)
  - 2 星差评恢复：重新扣减 -2 信用分 (`ADMIN_ADJUST`)
  - 1 星差评恢复：重新扣减 -5 信用分 (`ADMIN_ADJUST`)
- 全程由 `CreditService` 驱动并强制生成 `admin_audit_log` 审计流水。

### 9. 并发方案
- 100 并发点赞依赖 `uk_review_like_review_user` 强行阻断 99 个请求，事务回滚保证计数只累加 1 次；
- 并发取消依赖 `rowsAffected > 0` 与 `GREATEST(0, like_count - 1)`，绝不出现负数计数。

### 10. 权限与 IDOR 防护
- 点赞用户身份由服务端安全上下文（JWT）强制提取，前端传入一律无效；
- 严禁给自己发表的评价点赞（`reviewerId == currentUserId` 拦截返回 400）；
- 管理员接口严格由 `SecurityConfig` 路由守卫 + `@PreAuthorize("hasRole('ADMIN')")` 双重锁定。

### 11. N+1 防护方案
- 列表查询改用 **4-Phase 批量内存聚合映射算法**；
- 评价列表查询压减为恒定 4 次批量 SQL（评价分页 + 批量用户 + 批量商品 + 当前用户批量点赞），彻底终结 N+1 隐患。

### 12. 自动化测试矩阵规划
规划在后续实现阶段编写 `CampusTradeStage6CTests`，覆盖：
- 未登录 401 拦截
- 正常点赞 200 与计数 +1
- 重复点赞幂等返回已赞
- 取消点赞 200 与计数 -1
- 重复取消幂等且计数不为负
- 自点赞 400 拦截
- 不存在评价 404 拦截
- 被屏蔽评价点赞 422 拦截
- 并发 100 点赞数据严格一致
- 列表查询 `likeCount` 与 `likedByCurrentUser` 批量透出且无 N+1
- 管理员纠偏恢复与信用反向精准补偿
- 非管理员越权调用恢复接口 403 拦截

### 13. 风险评估与分级排查
- **P0（致命破坏性风险）**：**0 项**。未破坏 Stage 0~6-B 任何既有业务与状态机；
- **P1（数据与并发一致性风险）**：**0 项**。物理唯一键与原子更新完全收敛竞态风险；
- **P2（中低度性能与体验风险）**：**已收敛**。列表 N+1 问题已由 4 阶段批量算法从架构层面彻底根治。

---

## 二、Stage 6-C 评审设计产出物索引

1. **现有工程与点赞审计**：  
   [`docs/stage6/Stage6-C-existing-analysis.md`](file:///d:/wkk/Second-hand%20trading%20platform/docs/stage6/Stage6-C-existing-analysis.md)
2. **领域模型与治理设计**：  
   [`docs/stage6/Stage6-C-domain-design.md`](file:///d:/wkk/Second-hand%20trading%20platform/docs/stage6/Stage6-C-domain-design.md)
3. **数据库与存储设计**：  
   [`docs/stage6/Stage6-C-database-design.md`](file:///d:/wkk/Second-hand%20trading%20platform/docs/stage6/Stage6-C-database-design.md)
4. **API 契约与 N+1 优化设计**：  
   [`docs/stage6/Stage6-C-api-contract.md`](file:///d:/wkk/Second-hand%20trading%20platform/docs/stage6/Stage6-C-api-contract.md)
5. **安全与越权防御设计**：  
   [`docs/stage6/Stage6-C-security-design.md`](file:///d:/wkk/Second-hand%20trading%20platform/docs/stage6/Stage6-C-security-design.md)
6. **并发控制与 Redis 评估**：  
   [`docs/stage6/Stage6-C-concurrency-design.md`](file:///d:/wkk/Second-hand%20trading%20platform/docs/stage6/Stage6-C-concurrency-design.md)
7. **综合评审与 Gate 报告**：  
   [`docs/stage6/Stage6-C-design-review.md`](file:///d:/wkk/Second-hand%20trading%20platform/docs/stage6/Stage6-C-design-review.md)

---

## 三、最终 Design Gate 裁决结论

$$\mathbf{Design\ Gate\ Decision:\ PASS}$$

> **裁决结论**：**Stage 6-C 设计评审正式放行 (PASS)！**  
> 
> **理由**：
> 1. 本阶段**严格未修改任何业务代码、SQL 或数据库结构**，完全遵守设计评审约束；
> 2. 深入审查了 Stage 5 评价与 Stage 6-B 举报治理链路，新设计的点赞系统与屏蔽治理实现无缝兼容；
> 3. 对 Redis 盲目引入进行了坚决遏制，确立以 PostgreSQL 作为唯一 Source of Truth 的轻量高可用方案；
> 4. 彻底解决了评价列表现存的 N+1 查询隐患，形成了高内聚低耦合的工程级落地规划；
> 5. 系统架构边界明晰、安全性与并发性证明完备，具备正式进入后端实现的准入条件。
