-- ==============================================================================
--  孤儿数据（悬空引用）排查与清理脚本
--
--  背景
--  ------------------------------------------------------------------------------
--  V10__data_integrity_constraints.sql 与 V12__student_verify_email_unique_and_missing_fks.sql
--  为关键关联补了外键。其中 review.order_id / review.goods_id（V10）以及 V12 新增的 12 条外键
--  全部使用 NOT VALID：约束对**新增/更新**的行立即生效，但既有历史造数产生的孤儿行
--  （评测脚本直接以随机 ID 插入）没有被校验，也不会被自动删除。
--
--    ⚠️ 重要：NOT VALID 外键**不会**在迁移时校验历史数据，因此"迁移成功"不等于"库里没有孤儿行"。
--    本文件就是用来把这件事查清楚、并按业务决定是否清理的操作手册。
--
--  本文件是"只读排查 + 人工确认后执行"的操作手册，**不会被 Flyway 执行**，
--  也绝不在迁移里静默删数据。请按段落顺序执行：
--    第 1 段：只读盘点（先看清楚有多少、属于哪一批）
--    第 2 段：备份（任何清理动作之前必须先做）
--    第 3 段：清理方案（三种，按业务取舍选择其一；默认不做任何删除）
--    第 4 段：清理后收口（VALIDATE CONSTRAINT 让外键真正校验全量历史数据）
--    第 5 段：校园邮箱重复认证（V12 部分唯一索引被跳过时的处理流程）
--
--  执行环境：dev = docker exec -i campustrade-postgres psql -U campustrade -d campustrade
-- ==============================================================================


-- ==============================================================================
-- 第 1 段：只读盘点（纯 SELECT，可随时执行）
-- ==============================================================================

-- 1.0 V12 新增的 12 条外键各自的孤儿行计数（预期全部为 0；非 0 不影响迁移，
--     但意味着"这些行不会被 VALIDATE CONSTRAINT 接受"，收口前需要先按业务处理）
SELECT 'review.reviewer_id' AS fk_column, count(*) AS orphan_rows
FROM campus_trade.review c LEFT JOIN campus_trade."user" p ON c.reviewer_id = p.id WHERE p.id IS NULL
UNION ALL
SELECT 'review.reviewed_user_id', count(*)
FROM campus_trade.review c LEFT JOIN campus_trade."user" p ON c.reviewed_user_id = p.id WHERE p.id IS NULL
UNION ALL
SELECT 'review_like.review_id', count(*)
FROM campus_trade.review_like c LEFT JOIN campus_trade.review p ON c.review_id = p.id WHERE p.id IS NULL
UNION ALL
SELECT 'review_like.user_id', count(*)
FROM campus_trade.review_like c LEFT JOIN campus_trade."user" p ON c.user_id = p.id WHERE p.id IS NULL
UNION ALL
SELECT 'browse_history.user_id', count(*)
FROM campus_trade.browse_history c LEFT JOIN campus_trade."user" p ON c.user_id = p.id WHERE p.id IS NULL
UNION ALL
SELECT 'browse_history.goods_id', count(*)
FROM campus_trade.browse_history c LEFT JOIN campus_trade.goods p ON c.goods_id = p.id WHERE p.id IS NULL
UNION ALL
SELECT 'search_history.user_id', count(*)
FROM campus_trade.search_history c LEFT JOIN campus_trade."user" p ON c.user_id = p.id WHERE p.id IS NULL
UNION ALL
SELECT 'report.reporter_id', count(*)
FROM campus_trade.report c LEFT JOIN campus_trade."user" p ON c.reporter_id = p.id WHERE p.id IS NULL
UNION ALL
SELECT 'admin_audit_log.admin_id', count(*)
FROM campus_trade.admin_audit_log c LEFT JOIN campus_trade."user" p ON c.admin_id = p.id WHERE p.id IS NULL
UNION ALL
SELECT 'student_verify.user_id', count(*)
FROM campus_trade.student_verify c LEFT JOIN campus_trade."user" p ON c.user_id = p.id WHERE p.id IS NULL
UNION ALL
SELECT 'student_verify.school_id', count(*)
FROM campus_trade.student_verify c LEFT JOIN campus_trade.campus_school p ON c.school_id = p.id WHERE p.id IS NULL
UNION ALL
SELECT 'user_credit.user_id', count(*)
FROM campus_trade.user_credit c LEFT JOIN campus_trade."user" p ON c.user_id = p.id WHERE p.id IS NULL;

-- 1.0.1 说明：report.target_id **故意没有外键**。
--       report 是多态表（target_type ∈ GOODS / REVIEW / USER，见 V7 的 chk_report_target_type），
--       target_id 的指向随 target_type 变化，无法用单列外键表达。
--       该列的一致性由"chk_report_target_type 值域约束 + 应用层写入前多态存在性校验"保证。

-- 1.1 review 的两类孤儿行计数
SELECT 'review.order_id 孤儿' AS orphan_kind, count(*) AS rows
FROM campus_trade.review r
LEFT JOIN campus_trade.trade_order o ON r.order_id = o.id
WHERE o.id IS NULL
UNION ALL
SELECT 'review.goods_id 孤儿', count(*)
FROM campus_trade.review r
LEFT JOIN campus_trade.goods g ON r.goods_id = g.id
WHERE g.id IS NULL;

-- 1.2 孤儿评价的时间分布（确认它们属于哪一批历史造数）
SELECT date_trunc('day', r.created_time) AS created_day,
       count(*)                                    AS orphan_rows,
       min(r.id)                                   AS min_id,
       max(r.id)                                   AS max_id
FROM campus_trade.review r
LEFT JOIN campus_trade.trade_order o ON r.order_id = o.id
LEFT JOIN campus_trade.goods g ON r.goods_id = g.id
WHERE o.id IS NULL OR g.id IS NULL
GROUP BY 1
ORDER BY 1;

-- 1.3 孤儿评价里是否混入了真实用户产生的内容（reviewer_id 存在即属于真实账号）
SELECT count(*) FILTER (WHERE u.id IS NOT NULL) AS orphan_with_real_reviewer,
       count(*) FILTER (WHERE u.id IS NULL)     AS orphan_with_unknown_reviewer
FROM campus_trade.review r
LEFT JOIN campus_trade.trade_order o ON r.order_id = o.id
LEFT JOIN campus_trade.goods g ON r.goods_id = g.id
LEFT JOIN campus_trade."user" u ON r.reviewer_id = u.id
WHERE o.id IS NULL OR g.id IS NULL;

-- 1.4 其余外键的孤儿行盘点（V10 已建的 9 条，预期全部为 0；若不为 0，只有在执行
--     VALIDATE CONSTRAINT 时才会失败 —— 迁移本身不会因此中断）
SELECT 'goods.seller_id' AS fk_column, count(*) AS orphan_rows
FROM campus_trade.goods c LEFT JOIN campus_trade."user" p ON c.seller_id = p.id WHERE p.id IS NULL
UNION ALL
SELECT 'goods.school_id', count(*)
FROM campus_trade.goods c LEFT JOIN campus_trade.campus_school p ON c.school_id = p.id WHERE p.id IS NULL
UNION ALL
SELECT 'goods.category_id', count(*)
FROM campus_trade.goods c LEFT JOIN campus_trade.category p ON c.category_id = p.id WHERE p.id IS NULL
UNION ALL
SELECT 'trade_order.goods_id', count(*)
FROM campus_trade.trade_order c LEFT JOIN campus_trade.goods p ON c.goods_id = p.id WHERE p.id IS NULL
UNION ALL
SELECT 'trade_order.buyer_id', count(*)
FROM campus_trade.trade_order c LEFT JOIN campus_trade."user" p ON c.buyer_id = p.id WHERE p.id IS NULL
UNION ALL
SELECT 'trade_order.seller_id', count(*)
FROM campus_trade.trade_order c LEFT JOIN campus_trade."user" p ON c.seller_id = p.id WHERE p.id IS NULL
UNION ALL
SELECT 'favorite.goods_id', count(*)
FROM campus_trade.favorite c LEFT JOIN campus_trade.goods p ON c.goods_id = p.id WHERE p.id IS NULL
UNION ALL
SELECT 'favorite.user_id', count(*)
FROM campus_trade.favorite c LEFT JOIN campus_trade."user" p ON c.user_id = p.id WHERE p.id IS NULL
UNION ALL
SELECT 'goods_image.goods_id', count(*)
FROM campus_trade.goods_image c LEFT JOIN campus_trade.goods p ON c.goods_id = p.id WHERE p.id IS NULL;

-- 1.5 查看外键当前的校验状态（NOT VALID 会在 validated 列显示 false）
SELECT conname,
       conrelid::regclass AS table_name,
       convalidated       AS validated,
       pg_get_constraintdef(oid) AS definition
FROM pg_constraint
WHERE connamespace = 'campus_trade'::regnamespace
  AND contype = 'f'
ORDER BY conrelid::regclass::text, conname;


-- ==============================================================================
-- 第 2 段：备份（任何清理动作之前必须先执行）
-- ==============================================================================

-- 2.1 只备份将被清理的孤儿行（推荐；轻量、可精确回滚）
CREATE TABLE IF NOT EXISTS campus_trade.review_orphan_backup AS
SELECT r.*, CURRENT_TIMESTAMP AS backup_time
FROM campus_trade.review r
LEFT JOIN campus_trade.trade_order o ON r.order_id = o.id
LEFT JOIN campus_trade.goods g ON r.goods_id = g.id
WHERE o.id IS NULL OR g.id IS NULL;

CREATE INDEX IF NOT EXISTS idx_review_orphan_backup_id
    ON campus_trade.review_orphan_backup (id);

-- 2.2 全表备份（更保守，占用空间更大）
-- CREATE TABLE campus_trade.review_full_backup AS SELECT * FROM campus_trade.review;

-- 回滚示例（把备份中的行重新插回主表；仅在确认需要回滚时执行）
-- INSERT INTO campus_trade.review
--     SELECT id, order_id, goods_id, reviewer_id, reviewed_user_id, score, content, tags,
--            is_anonymous, status, created_time, updated_time, like_count
--     FROM campus_trade.review_orphan_backup
--     ON CONFLICT (id) DO NOTHING;


-- ==============================================================================
-- 第 3 段：清理方案（三选一，默认什么都不做）
--
--   方案 A —— 保留数据、不动外键：什么都不做。
--             NOT VALID 外键已经能阻止新的孤儿行，历史行可长期留档。
--   方案 B —— 把孤儿行"改挂"到一条占位订单/商品上（不丢数据，但会引入假关联，不推荐）。
--   方案 C —— 删除孤儿行（按下述 SQL；务必先做第 2 段备份，并在业务侧确认这些评价不承载真实内容）。
-- ==============================================================================

-- 方案 C-1：把孤儿评价的关联字段先置空（仅当业务允许 status 与关联解耦时考虑；
--           注意 order_id / goods_id 当前均为 NOT NULL，置空前需先放开非空约束，风险较高）
-- ALTER TABLE campus_trade.review ALTER COLUMN order_id DROP NOT NULL;
-- UPDATE campus_trade.review r SET order_id = NULL
--   WHERE NOT EXISTS (SELECT 1 FROM campus_trade.trade_order o WHERE o.id = r.order_id);
-- UPDATE campus_trade.review r SET goods_id = NULL
--   WHERE NOT EXISTS (SELECT 1 FROM campus_trade.goods g WHERE g.id = r.goods_id);

-- 方案 C-2：连带清理孤儿评价的点赞明细后删除孤儿评价（保持点赞表不产生新的悬空引用）
-- BEGIN;
-- DELETE FROM campus_trade.review_like rl
--  WHERE rl.review_id IN (
--      SELECT r.id FROM campus_trade.review r
--      LEFT JOIN campus_trade.trade_order o ON r.order_id = o.id
--      LEFT JOIN campus_trade.goods g ON r.goods_id = g.id
--      WHERE o.id IS NULL OR g.id IS NULL
--  );
-- DELETE FROM campus_trade.review r
--  WHERE NOT EXISTS (SELECT 1 FROM campus_trade.trade_order o WHERE o.id = r.order_id)
--     OR NOT EXISTS (SELECT 1 FROM campus_trade.goods g WHERE g.id = r.goods_id);
-- COMMIT;

-- 清理结果复核（应为 0）
-- SELECT count(*) FROM campus_trade.review r
--  LEFT JOIN campus_trade.trade_order o ON r.order_id = o.id
--  LEFT JOIN campus_trade.goods g ON r.goods_id = g.id
--  WHERE o.id IS NULL OR g.id IS NULL;


-- ==============================================================================
-- 第 4 段：清理后收口
--
--   确认孤儿行已按业务要求处理完毕后，用 VALIDATE CONSTRAINT 把 NOT VALID 外键
--   升级为全量校验（该操作只取 SHARE UPDATE EXCLUSIVE 锁，不阻塞读写）。
--   若库中仍有孤儿行，这一步会失败并报出具体冲突行 —— 这正是它的作用：让"是否还有脏数据"可验证。
-- ==============================================================================

-- 4.1 V10 建立的两条（review.order_id / review.goods_id）
-- ALTER TABLE campus_trade.review VALIDATE CONSTRAINT fk_review_order;
-- ALTER TABLE campus_trade.review VALIDATE CONSTRAINT fk_review_goods;

-- 4.2 V12 新增的 12 条
-- ALTER TABLE campus_trade.review          VALIDATE CONSTRAINT fk_review_reviewer;
-- ALTER TABLE campus_trade.review          VALIDATE CONSTRAINT fk_review_reviewed_user;
-- ALTER TABLE campus_trade.review_like     VALIDATE CONSTRAINT fk_review_like_review;
-- ALTER TABLE campus_trade.review_like     VALIDATE CONSTRAINT fk_review_like_user;
-- ALTER TABLE campus_trade.browse_history  VALIDATE CONSTRAINT fk_browse_history_user;
-- ALTER TABLE campus_trade.browse_history  VALIDATE CONSTRAINT fk_browse_history_goods;
-- ALTER TABLE campus_trade.search_history  VALIDATE CONSTRAINT fk_search_history_user;
-- ALTER TABLE campus_trade.report          VALIDATE CONSTRAINT fk_report_reporter;
-- ALTER TABLE campus_trade.admin_audit_log VALIDATE CONSTRAINT fk_admin_audit_log_admin;
-- ALTER TABLE campus_trade.student_verify  VALIDATE CONSTRAINT fk_student_verify_user;
-- ALTER TABLE campus_trade.student_verify  VALIDATE CONSTRAINT fk_student_verify_school;
-- ALTER TABLE campus_trade.user_credit     VALIDATE CONSTRAINT fk_user_credit_user;

-- 4.3 收口结果复核（validated 应变为 true；仍为 false 说明对应外键还处于"只约束新数据"状态）
-- SELECT conname, conrelid::regclass AS table_name, convalidated
--   FROM pg_constraint
--  WHERE connamespace = 'campus_trade'::regnamespace AND contype = 'f'
--  ORDER BY conrelid::regclass::text, conname;


-- ==============================================================================
-- 第 5 段：校园邮箱重复认证（V12 的部分唯一索引被跳过时）
--
--   V12 会给 campus_trade.student_verify 建一条部分唯一索引：
--       CREATE UNIQUE INDEX uk_student_verify_email_success
--           ON campus_trade.student_verify (school_id, school_email)
--           WHERE verify_status = 'SUCCESS';
--   含义：一个校园邮箱最多只能被一个账号核销成功（PENDING 行不受限制）。
--
--   若建索引前发现"同一 school_id + school_email 已有多条 SUCCESS"，V12 会：
--       * 跳过建索引（迁移继续，不报错、不删数据）；
--       * 打印冲突组数、行数与明细（school_id / school_email / user_ids）。
--   请按下面顺序人工处理：
-- ==============================================================================

-- 5.1 只读盘点：找出所有重复认证的邮箱与涉及的账号
SELECT school_id,
       school_email,
       count(*)                                        AS success_rows,
       string_agg(user_id::text, ',' ORDER BY user_id)  AS user_ids,
       string_agg(id::text, ',' ORDER BY id)            AS verify_row_ids
FROM campus_trade.student_verify
WHERE verify_status = 'SUCCESS'
GROUP BY school_id, school_email
HAVING count(*) > 1
ORDER BY school_email;

-- 5.2 备份（清理前必须执行）
-- CREATE TABLE IF NOT EXISTS campus_trade.student_verify_dup_backup AS
-- SELECT sv.*, CURRENT_TIMESTAMP AS backup_time
--   FROM campus_trade.student_verify sv
--   JOIN (
--         SELECT school_id, school_email
--           FROM campus_trade.student_verify
--          WHERE verify_status = 'SUCCESS'
--          GROUP BY school_id, school_email
--         HAVING count(*) > 1
--        ) dup ON dup.school_id = sv.school_id AND dup.school_email = sv.school_email;

-- 5.3 处理（示例：只保留每个邮箱最早核销成功的那一行，其余降级为 PENDING）
--     注意：本步骤会**修改**历史数据，必须由业务方确认"谁才是该邮箱的真实持有人在留档"后再执行。
-- UPDATE campus_trade.student_verify sv
--    SET verify_status = 'PENDING',
--        verify_time   = NULL
--  WHERE sv.verify_status = 'SUCCESS'
--    AND sv.id <> (
--        SELECT keep.id
--          FROM campus_trade.student_verify keep
--         WHERE keep.school_id = sv.school_id
--           AND keep.school_email = sv.school_email
--           AND keep.verify_status = 'SUCCESS'
--         ORDER BY keep.verify_time NULLS LAST, keep.id
--         LIMIT 1
--    );

-- 5.4 建索引（5.1 的查询返回 0 行后执行；应用侧已同时做校验，索引在这里做物理兜底）
-- CREATE UNIQUE INDEX uk_student_verify_email_success
--     ON campus_trade.student_verify (school_id, school_email)
--     WHERE verify_status = 'SUCCESS';

-- 5.5 复核（应返回 0 行）
-- SELECT school_id, school_email, count(*)
--   FROM campus_trade.student_verify
--  WHERE verify_status = 'SUCCESS'
--  GROUP BY school_id, school_email
-- HAVING count(*) > 1;


-- ==============================================================================
-- 附：信用流水对账巡检（阶段 4 引入 change_score=实际生效值后应恒等式成立）
--
-- 历史行（change_score 记录的是请求值）可能仍然不自洽，这是既有事实、不要求修正；
-- 关注"新产生的数据"应为 0 条不自洽。
-- ==============================================================================
-- SELECT count(*) AS inconsistent_users
-- FROM campus_trade.user_credit c
-- WHERE c.credit_score <> 100 + COALESCE(
--         (SELECT sum(l.change_score) FROM campus_trade.user_credit_log l WHERE l.user_id = c.user_id), 0);

-- 明细：哪些用户不自洽、差多少
-- SELECT c.user_id,
--        c.credit_score                                   AS actual_score,
--        100 + COALESCE(sum(l.change_score), 0)           AS expected_score,
--        c.credit_score - (100 + COALESCE(sum(l.change_score), 0)) AS drift
-- FROM campus_trade.user_credit c
-- LEFT JOIN campus_trade.user_credit_log l ON l.user_id = c.user_id
-- GROUP BY c.user_id, c.credit_score
-- HAVING c.credit_score <> 100 + COALESCE(sum(l.change_score), 0)
-- ORDER BY abs(c.credit_score - (100 + COALESCE(sum(l.change_score), 0))) DESC;
