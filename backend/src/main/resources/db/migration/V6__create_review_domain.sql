-- ==============================================================================
-- Flyway Migration V6: Stage 5-D 评价系统核心数据表与索引
-- ==============================================================================

-- 1. 创建评价核心数据表 (campus_trade.review)
CREATE TABLE IF NOT EXISTS campus_trade.review (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    goods_id BIGINT NOT NULL,
    reviewer_id BIGINT NOT NULL,
    reviewed_user_id BIGINT NOT NULL,
    score SMALLINT NOT NULL,
    content VARCHAR(500),
    tags VARCHAR(255),
    is_anonymous BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(30) NOT NULL DEFAULT 'VISIBLE',
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_review_score_range CHECK (score >= 1 AND score <= 5)
);

-- 2. 物理防重唯一索引 (同一笔订单同一人仅能评价一次)
CREATE UNIQUE INDEX IF NOT EXISTS uk_review_order_reviewer 
    ON campus_trade.review (order_id, reviewer_id);

-- 3. 业务高频查询索引
CREATE INDEX IF NOT EXISTS idx_review_target_time 
    ON campus_trade.review (reviewed_user_id, status, created_time DESC);

CREATE INDEX IF NOT EXISTS idx_review_goods_status 
    ON campus_trade.review (goods_id, status, created_time DESC);

CREATE INDEX IF NOT EXISTS idx_review_order 
    ON campus_trade.review (order_id);
