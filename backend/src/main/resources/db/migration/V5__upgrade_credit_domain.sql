-- ==============================================================================
-- Flyway Migration V5: Stage 5 信用领域数据结构扩展与审计流水体系建设
-- ==============================================================================

-- 1. 扩展用户信用档案表 (campus_trade.user_credit)
ALTER TABLE campus_trade.user_credit
    ADD COLUMN IF NOT EXISTS completed_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS cancel_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS credit_level VARCHAR(20) NOT NULL DEFAULT 'GOOD',
    ADD COLUMN IF NOT EXISTS updated_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP;

-- 2. 增加积分范围约束 (0 <= credit_score <= 200)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_user_credit_score_range'
    ) THEN
        ALTER TABLE campus_trade.user_credit
            ADD CONSTRAINT chk_user_credit_score_range CHECK (credit_score >= 0 AND credit_score <= 200);
    END IF;
END $$;

-- 3. 创建信用变更审计流水表 (campus_trade.user_credit_log)
CREATE TABLE IF NOT EXISTS campus_trade.user_credit_log (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    change_type VARCHAR(50) NOT NULL,
    change_score INTEGER NOT NULL,
    before_score INTEGER NOT NULL,
    after_score INTEGER NOT NULL,
    related_type VARCHAR(50),
    related_id BIGINT,
    reason VARCHAR(500),
    created_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 4. 创建幂等唯一索引 (防止重复增加或扣减积分，确保幂等性)
CREATE UNIQUE INDEX IF NOT EXISTS uk_credit_log_idempotent 
    ON campus_trade.user_credit_log(user_id, related_type, related_id, change_type);

-- 5. 创建查询与审计索引
CREATE INDEX IF NOT EXISTS idx_credit_log_user_time 
    ON campus_trade.user_credit_log(user_id, created_time DESC);

CREATE INDEX IF NOT EXISTS idx_credit_log_related 
    ON campus_trade.user_credit_log(related_type, related_id);
