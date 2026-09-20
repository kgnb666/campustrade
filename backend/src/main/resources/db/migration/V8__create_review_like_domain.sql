-- ==============================================================================
-- Flyway Migration V8: Stage 6-C 评价点赞领域与点赞计数体系
-- ==============================================================================

-- 1. 评价主表扩充 like_count 列并增加非负约束
ALTER TABLE campus_trade.review 
    ADD COLUMN IF NOT EXISTS like_count INT NOT NULL DEFAULT 0;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_review_like_count_non_negative'
    ) THEN
        ALTER TABLE campus_trade.review 
            ADD CONSTRAINT chk_review_like_count_non_negative CHECK (like_count >= 0);
    END IF;
END $$;

-- 2. 创建评价点赞明细表 (campus_trade.review_like)
CREATE TABLE IF NOT EXISTS campus_trade.review_like (
    id BIGSERIAL PRIMARY KEY,
    review_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 3. 核心物理防重唯一索引 (一人一评仅能点赞一次，同时可作为 review_id 前缀索引)
CREATE UNIQUE INDEX IF NOT EXISTS uk_review_like_review_user 
    ON campus_trade.review_like (review_id, user_id);

-- 4. 点赞人历史查询索引 (支持按用户快速查询其所有点赞记录)
CREATE INDEX IF NOT EXISTS idx_review_like_user_time 
    ON campus_trade.review_like (user_id, created_time DESC);

-- 5. 评价 ID 单独索引 (优化按 review_id 查询点赞明细)
CREATE INDEX IF NOT EXISTS idx_review_like_review 
    ON campus_trade.review_like (review_id);
