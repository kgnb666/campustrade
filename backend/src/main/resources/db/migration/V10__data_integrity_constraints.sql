-- ==============================================================================
-- Flyway Migration V10: 数据一致性约束加固（阶段 4）
--
-- 本迁移只做三件事：
--   1) 让信用审计流水"可对账"：change_score 记录实际生效值，原始请求值另存 request_score；
--      并把幂等键从"业务维度"升级为"业务动作维度"（idem_key）。
--   2) 给状态类列补 CHECK 约束，杜绝写入非法状态字面量。
--   3) 给关键关联补外键，杜绝新的悬空引用（孤儿行）。
--
-- 迁移脚本对"全新空库"（Testcontainers）与"已有历史造数的开发库"都必须一次成功，
-- 因此每个约束都用 DO 块做存在性判断（可重复执行），并在注释里写清数据现状。
-- 本迁移绝不删除/修改任何业务数据：对已有孤儿行只使用 NOT VALID（见下）。
-- ==============================================================================

-- ==============================================================================
-- 1. user_credit_log：原始请求值 + 动作级幂等键
-- ==============================================================================

-- 1.1 原始请求值列：change_score 从"请求值"改为"实际生效值"后，请求口径需要单独留痕
ALTER TABLE campus_trade.user_credit_log
    ADD COLUMN IF NOT EXISTS request_score INTEGER;

-- 1.2 动作级幂等键列
ALTER TABLE campus_trade.user_credit_log
    ADD COLUMN IF NOT EXISTS idem_key VARCHAR(255);

-- 1.3 历史行回填
--     历史行由旧实现写入，change_score 就是当时的"请求值"，因此 request_score 直接对齐 change_score。
UPDATE campus_trade.user_credit_log
SET request_score = change_score
WHERE request_score IS NULL;

--     幂等键回填为旧实现使用的业务维度键：change_type|related_type|related_id|
--     与应用侧 CreditServiceImpl#buildIdemKey 的空值折叠规则完全一致，
--     因此历史流水的幂等语义被原样保留（同一业务维度的重复请求依旧会被拦截）。
UPDATE campus_trade.user_credit_log
SET idem_key = change_type || '|' || COALESCE(related_type, '') || '|' || COALESCE(related_id::text, '') || '|'
WHERE idem_key IS NULL;

ALTER TABLE campus_trade.user_credit_log
    ALTER COLUMN idem_key SET NOT NULL;

-- 1.4 幂等索引换代（沿用 uk_credit_log_idempotent 这个名字，但键从"业务维度"升级为"业务动作维度"）
--     旧定义 (user_id, related_type, related_id, change_type) 只认业务维度，导致
--     "屏蔽(REVIEW) → 恢复(REVIEW_RESTORE) → 再次屏蔽(REVIEW)" 的第三次治理动作与第一次
--     键完全相同而被静默拦截，追缴失效。新定义把"动作标识"（治理动作对应审计日志 ID）纳入的
--     idem_key 作为键：同一动作重试仍然只生效一次，不同次动作各自生效。
--     索引名保持不变，避免下游/运维脚本按名引用时失效。
DROP INDEX IF EXISTS campus_trade.uk_credit_log_idempotent;

CREATE UNIQUE INDEX IF NOT EXISTS uk_credit_log_idempotent
    ON campus_trade.user_credit_log (user_id, idem_key);

-- ==============================================================================
-- 2. 状态类列 CHECK 约束
--    取值域来自对开发库的只读核对（见每个约束的注释），确保迁移不会因既有数据校验失败。
-- ==============================================================================

-- goods.status：开发库现有 ON_SALE / OFF_SHELF / SOLD；应用侧另可写入 DRAFT / LOCKED
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_goods_status_domain') THEN
        ALTER TABLE campus_trade.goods
            ADD CONSTRAINT chk_goods_status_domain
            CHECK (status IN ('DRAFT', 'ON_SALE', 'LOCKED', 'SOLD', 'OFF_SHELF'));
    END IF;
END $$;

-- trade_order.order_status：开发库现有 WAIT_MEET / COMPLETED / CANCELLED；应用侧另可写入 WAIT_SELLER_CONFIRM
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_trade_order_status_domain') THEN
        ALTER TABLE campus_trade.trade_order
            ADD CONSTRAINT chk_trade_order_status_domain
            CHECK (order_status IN ('WAIT_SELLER_CONFIRM', 'WAIT_MEET', 'COMPLETED', 'CANCELLED'));
    END IF;
END $$;

-- review.status：开发库现有 VISIBLE / AUDIT_REJECTED
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_review_status_domain') THEN
        ALTER TABLE campus_trade.review
            ADD CONSTRAINT chk_review_status_domain
            CHECK (status IN ('VISIBLE', 'AUDIT_REJECTED'));
    END IF;
END $$;

-- user.status：开发库现有 ACTIVE；治理冻结写入 FROZEN
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_user_status_domain') THEN
        ALTER TABLE campus_trade."user"
            ADD CONSTRAINT chk_user_status_domain
            CHECK (status IN ('ACTIVE', 'FROZEN'));
    END IF;
END $$;

-- user.role：开发库现有 USER / STUDENT / ADMIN
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_user_role_domain') THEN
        ALTER TABLE campus_trade."user"
            ADD CONSTRAINT chk_user_role_domain
            CHECK (role IN ('USER', 'STUDENT', 'ADMIN'));
    END IF;
END $$;

-- student_verify.verify_status：开发库现有 PENDING / SUCCESS（应用侧仅这两条写入路径）
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_student_verify_status_domain') THEN
        ALTER TABLE campus_trade.student_verify
            ADD CONSTRAINT chk_student_verify_status_domain
            CHECK (verify_status IN ('PENDING', 'SUCCESS'));
    END IF;
END $$;

-- ==============================================================================
-- 3. 关键关联外键
--
--    NOT VALID 说明（只约束新数据，不清理既有数据）：
--      * review.order_id 与 review.goods_id 在开发库存在大量历史造数孤儿行
--        （评测脚本直接用随机 ID 插入评价：review.order_id 孤儿行 598 条、
--         review.goods_id 孤儿行 532 条，数量随历史造数继续变化）。
--        直接 ADD CONSTRAINT 会在校验既有数据阶段失败，导致迁移整体回滚；
--        因此这两条使用 NOT VALID：约束对**新增/更新**的行立即生效，
--        既有孤儿行保持原样不做任何删除或改写。
--      * 其余外键在开发库的孤儿行计数为 0，按常规（立即校验）方式创建。
--        孤儿数据排查与清理脚本见 backend/docs/data-cleanup-orphans.sql（只读排查 + 人工确认后执行的清理 SQL）。
-- ==============================================================================

-- 3.1 goods.seller_id -> user.id
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_goods_seller') THEN
        ALTER TABLE campus_trade.goods
            ADD CONSTRAINT fk_goods_seller FOREIGN KEY (seller_id)
            REFERENCES campus_trade."user" (id);
    END IF;
END $$;

-- 3.2 goods.school_id -> campus_school.id
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_goods_school') THEN
        ALTER TABLE campus_trade.goods
            ADD CONSTRAINT fk_goods_school FOREIGN KEY (school_id)
            REFERENCES campus_trade.campus_school (id);
    END IF;
END $$;

-- 3.3 goods.category_id -> category.id
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_goods_category') THEN
        ALTER TABLE campus_trade.goods
            ADD CONSTRAINT fk_goods_category FOREIGN KEY (category_id)
            REFERENCES campus_trade.category (id);
    END IF;
END $$;

-- 3.4 trade_order.goods_id -> goods.id
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_trade_order_goods') THEN
        ALTER TABLE campus_trade.trade_order
            ADD CONSTRAINT fk_trade_order_goods FOREIGN KEY (goods_id)
            REFERENCES campus_trade.goods (id);
    END IF;
END $$;

-- 3.5 trade_order.buyer_id -> user.id
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_trade_order_buyer') THEN
        ALTER TABLE campus_trade.trade_order
            ADD CONSTRAINT fk_trade_order_buyer FOREIGN KEY (buyer_id)
            REFERENCES campus_trade."user" (id);
    END IF;
END $$;

-- 3.6 trade_order.seller_id -> user.id
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_trade_order_seller') THEN
        ALTER TABLE campus_trade.trade_order
            ADD CONSTRAINT fk_trade_order_seller FOREIGN KEY (seller_id)
            REFERENCES campus_trade."user" (id);
    END IF;
END $$;

-- 3.7 review.order_id -> trade_order.id（NOT VALID：既有孤儿行 598 条，见上方说明）
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_review_order') THEN
        ALTER TABLE campus_trade.review
            ADD CONSTRAINT fk_review_order FOREIGN KEY (order_id)
            REFERENCES campus_trade.trade_order (id) NOT VALID;
    END IF;
END $$;

-- 3.8 review.goods_id -> goods.id（NOT VALID：既有孤儿行 532 条，见上方说明）
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_review_goods') THEN
        ALTER TABLE campus_trade.review
            ADD CONSTRAINT fk_review_goods FOREIGN KEY (goods_id)
            REFERENCES campus_trade.goods (id) NOT VALID;
    END IF;
END $$;

-- 3.9 favorite.goods_id -> goods.id
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_favorite_goods') THEN
        ALTER TABLE campus_trade.favorite
            ADD CONSTRAINT fk_favorite_goods FOREIGN KEY (goods_id)
            REFERENCES campus_trade.goods (id);
    END IF;
END $$;

-- 3.10 favorite.user_id -> user.id
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_favorite_user') THEN
        ALTER TABLE campus_trade.favorite
            ADD CONSTRAINT fk_favorite_user FOREIGN KEY (user_id)
            REFERENCES campus_trade."user" (id);
    END IF;
END $$;

-- 3.11 goods_image.goods_id -> goods.id
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_goods_image_goods') THEN
        ALTER TABLE campus_trade.goods_image
            ADD CONSTRAINT fk_goods_image_goods FOREIGN KEY (goods_id)
            REFERENCES campus_trade.goods (id);
    END IF;
END $$;

-- ==============================================================================
-- 4. 种子数据显式插入 id 后的序列对齐
--
--    V2 用显式 id（1..5 / 101..103 / 201..202 / 301 / 501）插入分类种子数据，
--    但 BIGSERIAL 序列并不会因此前进：全新库上序列仍停在 1，
--    任何 categoryMapper.insert 都会撞主键冲突（duplicate key value violates unique constraint "category_pkey"）。
--    这里把序列对齐到当前最大 id，nextval 将从 max(id)+1 开始。
--    （campus_school.id 由应用侧雪花 ID 生成，没有序列，无需对齐。）
-- ==============================================================================
SELECT setval(
    'campus_trade.category_id_seq',
    GREATEST((SELECT COALESCE(MAX(id), 1) FROM campus_trade.category), 1)
);
