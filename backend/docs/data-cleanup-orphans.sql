-- ==============================================================================
--  孤儿数据（悬空引用）排查与清理脚本
--
--  背景
--  ------------------------------------------------------------------------------
--  V10__data_integrity_constraints.sql 为关键关联补了外键。其中 review.order_id /
--  review.goods_id 两条使用 NOT VALID：约束对**新增/更新**的行立即生效，但既有历史造数
--  产生的孤儿行（评测脚本直接以随机 ID 插入评价）没有被校验，也不会被自动删除。
--
--  本文件是"只读排查 + 人工确认后执行"的操作手册，**不会被 Flyway 执行**，
--  也绝不在迁移里静默删数据。请按段落顺序执行：
--    第 1 段：只读盘点（先看清楚有多少、属于哪一批）
--    第 2 段：备份（任何清理动作之前必须先做）
--    第 3 段：清理方案（三种，按业务取舍选择其一；默认不做任何删除）
--    第 4 段：清理后收口（VALIDATE CONSTRAINT 让外键真正校验全量历史数据）
--
--  执行环境：dev = docker exec -i campustrade-postgres psql -U campustrade -d campustrade
-- ==============================================================================


-- ==============================================================================
-- 第 1 段：只读盘点（纯 SELECT，可随时执行）
-- ==============================================================================

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

-- 1.4 其余外键的孤儿行盘点（预期全部为 0；若不为 0，V10 会在建约束阶段直接失败，
--     需要先处理掉这些行再重跑迁移）
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

-- ALTER TABLE campus_trade.review VALIDATE CONSTRAINT fk_review_order;
-- ALTER TABLE campus_trade.review VALIDATE CONSTRAINT fk_review_goods;

-- 收口结果复核（validated 应变为 true）
-- SELECT conname, convalidated FROM pg_constraint
--  WHERE connamespace = 'campus_trade'::regnamespace AND contype = 'f'
--    AND conname IN ('fk_review_order', 'fk_review_goods');


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
