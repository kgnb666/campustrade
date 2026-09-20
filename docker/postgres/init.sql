-- ==============================================================================
-- CampusTrade 校园二手交易平台 - 数据库初始化脚本
-- ==============================================================================

CREATE SCHEMA IF NOT EXISTS campus_trade;

-- 授予模式所有权限给当前连接用户
GRANT ALL ON SCHEMA campus_trade TO campustrade;

-- 设置默认搜索路径
ALTER DATABASE campustrade SET search_path TO campus_trade, public;

COMMENT ON SCHEMA campus_trade IS 'CampusTrade 校园二手交易平台核心业务模式';

-- 1. 用户表
CREATE TABLE IF NOT EXISTS campus_trade."user" (
    id BIGINT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    nickname VARCHAR(50),
    avatar VARCHAR(500),
    phone VARCHAR(20),
    email VARCHAR(100) UNIQUE,
    role VARCHAR(20) NOT NULL DEFAULT 'USER',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_user_username ON campus_trade."user"(username);
CREATE INDEX IF NOT EXISTS idx_user_email ON campus_trade."user"(email);

-- 2. 高校学校字典表
CREATE TABLE IF NOT EXISTS campus_trade.campus_school (
    id BIGINT PRIMARY KEY,
    school_name VARCHAR(100) NOT NULL,
    school_code VARCHAR(50) NOT NULL UNIQUE,
    email_suffix VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 初始化高校种子数据
INSERT INTO campus_trade.campus_school (id, school_name, school_code, email_suffix, status) VALUES
(1, '清华大学', 'THU', '@mails.tsinghua.edu.cn', 'ACTIVE'),
(2, '北京大学', 'PKU', '@pku.edu.cn', 'ACTIVE'),
(3, '复旦大学', 'FDU', '@fudan.edu.cn', 'ACTIVE'),
(4, '浙江大学', 'ZJU', '@zju.edu.cn', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

-- 3. 学生认证表
CREATE TABLE IF NOT EXISTS campus_trade.student_verify (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    school_id BIGINT NOT NULL,
    student_number VARCHAR(50) NOT NULL,
    school_email VARCHAR(100) NOT NULL,
    verify_code VARCHAR(10),
    verify_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    verify_time TIMESTAMP,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_student_verify_user ON campus_trade.student_verify(user_id);

-- 4. 用户信用档案表
CREATE TABLE IF NOT EXISTS campus_trade.user_credit (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE,
    credit_score INT NOT NULL DEFAULT 100,
    trade_count INT NOT NULL DEFAULT 0,
    good_review_count INT NOT NULL DEFAULT 0,
    bad_review_count INT NOT NULL DEFAULT 0,
    completed_count BIGINT NOT NULL DEFAULT 0,
    cancel_count BIGINT NOT NULL DEFAULT 0,
    credit_level VARCHAR(20) NOT NULL DEFAULT 'GOOD',
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_user_credit_score_range CHECK (credit_score >= 0 AND credit_score <= 200)
);

CREATE INDEX IF NOT EXISTS idx_user_credit_user ON campus_trade.user_credit(user_id);

-- ==============================================================================
-- Stage 2: 商品发布与商品浏览体系
-- ==============================================================================

-- 5. 商品分类表 (category)
CREATE TABLE IF NOT EXISTS campus_trade.category (
    id BIGSERIAL PRIMARY KEY,
    parent_id BIGINT DEFAULT 0,
    name VARCHAR(50) NOT NULL,
    icon VARCHAR(255),
    sort INT DEFAULT 0,
    status SMALLINT DEFAULT 1,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_category_parent_id ON campus_trade.category(parent_id);
CREATE INDEX IF NOT EXISTS idx_category_status ON campus_trade.category(status);

-- 6. 商品表 (goods)
CREATE TABLE IF NOT EXISTS campus_trade.goods (
    id BIGSERIAL PRIMARY KEY,
    seller_id BIGINT NOT NULL,
    school_id BIGINT NOT NULL,
    category_id BIGINT NOT NULL,
    title VARCHAR(100) NOT NULL,
    description TEXT,
    price DECIMAL(10,2) NOT NULL,
    original_price DECIMAL(10,2),
    condition_level VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ON_SALE',
    location VARCHAR(100),
    view_count INT DEFAULT 0,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_goods_seller_id ON campus_trade.goods(seller_id);
CREATE INDEX IF NOT EXISTS idx_goods_school_id ON campus_trade.goods(school_id);
CREATE INDEX IF NOT EXISTS idx_goods_category_id ON campus_trade.goods(category_id);
CREATE INDEX IF NOT EXISTS idx_goods_status ON campus_trade.goods(status);
CREATE INDEX IF NOT EXISTS idx_goods_created_time ON campus_trade.goods(created_time);

-- 7. 商品图片表 (goods_image)
CREATE TABLE IF NOT EXISTS campus_trade.goods_image (
    id BIGSERIAL PRIMARY KEY,
    goods_id BIGINT NOT NULL,
    image_url VARCHAR(255) NOT NULL,
    sort INT DEFAULT 0,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_goods_image_goods_id ON campus_trade.goods_image(goods_id);

-- 8. 商品标签表 (goods_tag)
CREATE TABLE IF NOT EXISTS campus_trade.goods_tag (
    id BIGSERIAL PRIMARY KEY,
    goods_id BIGINT NOT NULL,
    tag_name VARCHAR(50) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_goods_tag_goods_id ON campus_trade.goods_tag(goods_id);

-- 初始化分类数据 (种子数据)
INSERT INTO campus_trade.category (id, parent_id, name, icon, sort, status)
VALUES 
    (1, 0, '电子产品', 'devices', 1, 1),
    (2, 0, '教材资料', 'menu_book', 2, 1),
    (3, 0, '生活用品', 'home_repair_service', 3, 1),
    (4, 0, '服饰鞋包', 'checkroom', 4, 1),
    (5, 0, '运动用品', 'sports_basketball', 5, 1)
ON CONFLICT (id) DO NOTHING;

INSERT INTO campus_trade.category (id, parent_id, name, icon, sort, status)
VALUES 
    (101, 1, '手机', 'smartphone', 1, 1),
    (102, 1, '电脑', 'laptop', 2, 1),
    (103, 1, '平板', 'tablet', 3, 1),
    (201, 2, '考研资料', 'auto_stories', 1, 1),
    (202, 2, '专业教材', 'school', 2, 1),
    (301, 3, '宿舍用品', 'bed', 1, 1),
    (501, 5, '自行车', 'pedal_bike', 1, 1)
ON CONFLICT (id) DO NOTHING;

-- ==============================================================================
-- Stage 3: 交易互动增强 + AI商品助手
-- ==============================================================================

-- 9. 收藏表 (favorite)
CREATE TABLE IF NOT EXISTS campus_trade.favorite (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    goods_id BIGINT NOT NULL,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_favorite_user_goods UNIQUE (user_id, goods_id)
);

CREATE INDEX IF NOT EXISTS idx_favorite_user_id ON campus_trade.favorite(user_id);
CREATE INDEX IF NOT EXISTS idx_favorite_goods_id ON campus_trade.favorite(goods_id);
CREATE INDEX IF NOT EXISTS idx_favorite_user_time ON campus_trade.favorite(user_id, created_time DESC);

-- 10. 浏览历史表 (browse_history)
CREATE TABLE IF NOT EXISTS campus_trade.browse_history (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    goods_id BIGINT NOT NULL,
    browse_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_browse_history_user_goods UNIQUE (user_id, goods_id)
);

CREATE INDEX IF NOT EXISTS idx_browse_history_user_time ON campus_trade.browse_history(user_id, browse_time DESC);

-- 11. 搜索历史表 (search_history)
CREATE TABLE IF NOT EXISTS campus_trade.search_history (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    keyword VARCHAR(100) NOT NULL,
    search_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_search_history_user_time ON campus_trade.search_history(user_id, search_time DESC);

-- ==============================================================================
-- Stage 4: 交易订单系统与校园面交闭环
-- ==============================================================================

-- 12. 交易订单表 (trade_order)
CREATE TABLE IF NOT EXISTS campus_trade.trade_order (
    id BIGSERIAL PRIMARY KEY,
    order_no VARCHAR(32) NOT NULL,
    buyer_id BIGINT NOT NULL,
    seller_id BIGINT NOT NULL,
    goods_id BIGINT NOT NULL,
    school_id BIGINT,
    goods_title_snapshot VARCHAR(200),
    goods_price_snapshot NUMERIC(10,2),
    goods_image_snapshot VARCHAR(500),
    meet_location VARCHAR(200),
    buyer_message VARCHAR(500),
    seller_reply VARCHAR(500),
    order_status VARCHAR(50) NOT NULL DEFAULT 'WAIT_SELLER_CONFIRM',
    cancel_reason VARCHAR(500),
    cancelled_by BIGINT,
    confirmed_time TIMESTAMP,
    completed_time TIMESTAMP,
    cancelled_time TIMESTAMP,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_trade_order_no UNIQUE (order_no)
);

CREATE INDEX IF NOT EXISTS idx_trade_order_buyer_time 
    ON campus_trade.trade_order(buyer_id, created_time DESC);

CREATE INDEX IF NOT EXISTS idx_trade_order_seller_time 
    ON campus_trade.trade_order(seller_id, created_time DESC);

CREATE INDEX IF NOT EXISTS idx_trade_order_goods 
    ON campus_trade.trade_order(goods_id);

CREATE INDEX IF NOT EXISTS idx_trade_order_status 
    ON campus_trade.trade_order(order_status);

CREATE UNIQUE INDEX IF NOT EXISTS uk_trade_order_active_goods 
    ON campus_trade.trade_order(goods_id) 
    WHERE order_status IN ('WAIT_SELLER_CONFIRM', 'WAIT_MEET');

-- ==============================================================================
-- Stage 5: 信用体系升级与交易评价领域
-- ==============================================================================

-- 13. 信用变更审计流水表 (user_credit_log)
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

CREATE UNIQUE INDEX IF NOT EXISTS uk_credit_log_idempotent 
    ON campus_trade.user_credit_log(user_id, related_type, related_id, change_type);

CREATE INDEX IF NOT EXISTS idx_credit_log_user_time 
    ON campus_trade.user_credit_log(user_id, created_time DESC);

CREATE INDEX IF NOT EXISTS idx_credit_log_related 
    ON campus_trade.user_credit_log(related_type, related_id);

-- 14. 评价核心数据表 (review)
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
    like_count INT NOT NULL DEFAULT 0,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_review_score_range CHECK (score >= 1 AND score <= 5),
    CONSTRAINT chk_review_like_count_non_negative CHECK (like_count >= 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_review_order_reviewer 
    ON campus_trade.review (order_id, reviewer_id);

CREATE INDEX IF NOT EXISTS idx_review_target_time 
    ON campus_trade.review (reviewed_user_id, status, created_time DESC);

CREATE INDEX IF NOT EXISTS idx_review_goods_status 
    ON campus_trade.review (goods_id, status, created_time DESC);

CREATE INDEX IF NOT EXISTS idx_review_order 
    ON campus_trade.review (order_id);

-- ==============================================================================
-- Stage 6-B: 平台治理与举报工单领域
-- ==============================================================================

-- 15. 统一举报工单表 (report)
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

CREATE UNIQUE INDEX IF NOT EXISTS uk_report_active 
    ON campus_trade.report (reporter_id, target_type, target_id) 
    WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_report_status_time 
    ON campus_trade.report (status, created_time DESC);

CREATE INDEX IF NOT EXISTS idx_report_target 
    ON campus_trade.report (target_type, target_id);

CREATE INDEX IF NOT EXISTS idx_report_reporter 
    ON campus_trade.report (reporter_id, created_time DESC);

-- 16. 管理员操作审计日志表 (admin_audit_log)
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

CREATE INDEX IF NOT EXISTS idx_admin_audit_time 
    ON campus_trade.admin_audit_log (created_time DESC);

CREATE INDEX IF NOT EXISTS idx_admin_audit_target 
    ON campus_trade.admin_audit_log (target_type, target_id);

CREATE INDEX IF NOT EXISTS idx_admin_audit_admin 
    ON campus_trade.admin_audit_log (admin_id, created_time DESC);

-- ==============================================================================
-- Stage 6-C: 评价点赞领域与点赞计数体系
-- ==============================================================================

-- 17. 评价点赞明细表 (review_like)
CREATE TABLE IF NOT EXISTS campus_trade.review_like (
    id BIGSERIAL PRIMARY KEY,
    review_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_review_like_review_user 
    ON campus_trade.review_like (review_id, user_id);

CREATE INDEX IF NOT EXISTS idx_review_like_user_time 
    ON campus_trade.review_like (user_id, created_time DESC);

CREATE INDEX IF NOT EXISTS idx_review_like_review 
    ON campus_trade.review_like (review_id);




