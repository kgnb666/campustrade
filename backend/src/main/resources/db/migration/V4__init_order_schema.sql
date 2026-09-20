-- ==============================================================================
-- Flyway Migration V4: Stage 4 交易订单系统与校园面交闭环核心表结构
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

-- 普通索引与时序复合索引
CREATE INDEX IF NOT EXISTS idx_trade_order_buyer_time 
    ON campus_trade.trade_order(buyer_id, created_time DESC);

CREATE INDEX IF NOT EXISTS idx_trade_order_seller_time 
    ON campus_trade.trade_order(seller_id, created_time DESC);

CREATE INDEX IF NOT EXISTS idx_trade_order_goods 
    ON campus_trade.trade_order(goods_id);

CREATE INDEX IF NOT EXISTS idx_trade_order_status 
    ON campus_trade.trade_order(order_status);

-- 核心并发防护: PostgreSQL Partial Unique Index
-- 强制约束：同一件二手商品只能存在一个处于活动状态 (WAIT_SELLER_CONFIRM 或 WAIT_MEET) 的订单
CREATE UNIQUE INDEX IF NOT EXISTS uk_trade_order_active_goods 
    ON campus_trade.trade_order(goods_id) 
    WHERE order_status IN ('WAIT_SELLER_CONFIRM', 'WAIT_MEET');
