-- ==============================================================================
-- Flyway Migration V7: Stage 6-B 平台治理与举报工单数据表及索引
-- ==============================================================================

-- 1. 创建统一举报工单表 (campus_trade.report)
CREATE TABLE IF NOT EXISTS campus_trade.report (
    id BIGSERIAL PRIMARY KEY,
    reporter_id BIGINT NOT NULL,
    target_type VARCHAR(30) NOT NULL,
    target_id BIGINT NOT NULL,
    reason_type VARCHAR(50) NOT NULL,
    description VARCHAR(500),
    evidence_images VARCHAR(1000),
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    handled_by BIGINT,
    handled_time TIMESTAMP,
    handle_result VARCHAR(500),
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_report_target_type CHECK (target_type IN ('GOODS', 'REVIEW', 'USER')),
    CONSTRAINT chk_report_status CHECK (status IN ('PENDING', 'HANDLED_VALID', 'HANDLED_INVALID'))
);

-- 2. 物理防重唯一索引 (同一用户针对同一目标仅能有一个 PENDING 状态的待处理工单)
CREATE UNIQUE INDEX IF NOT EXISTS uk_report_active 
    ON campus_trade.report (reporter_id, target_type, target_id) 
    WHERE status = 'PENDING';

-- 3. 举报业务查询索引
CREATE INDEX IF NOT EXISTS idx_report_status_time 
    ON campus_trade.report (status, created_time DESC);

CREATE INDEX IF NOT EXISTS idx_report_target 
    ON campus_trade.report (target_type, target_id);

CREATE INDEX IF NOT EXISTS idx_report_reporter 
    ON campus_trade.report (reporter_id, created_time DESC);

-- 4. 创建管理员操作审计日志表 (campus_trade.admin_audit_log)
CREATE TABLE IF NOT EXISTS campus_trade.admin_audit_log (
    id BIGSERIAL PRIMARY KEY,
    admin_id BIGINT NOT NULL,
    admin_username VARCHAR(50) NOT NULL,
    operation_type VARCHAR(50) NOT NULL,
    target_type VARCHAR(30) NOT NULL,
    target_id BIGINT NOT NULL,
    before_status VARCHAR(50),
    after_status VARCHAR(50),
    reason VARCHAR(500) NOT NULL,
    ip_address VARCHAR(50),
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 5. 管理员审计日志查询索引
CREATE INDEX IF NOT EXISTS idx_admin_audit_time 
    ON campus_trade.admin_audit_log (created_time DESC);

CREATE INDEX IF NOT EXISTS idx_admin_audit_target 
    ON campus_trade.admin_audit_log (target_type, target_id);

CREATE INDEX IF NOT EXISTS idx_admin_audit_admin 
    ON campus_trade.admin_audit_log (admin_id, created_time DESC);
