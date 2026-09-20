-- ==============================================================================
-- Flyway Migration V3: Stage 3 交易互动增强 (收藏、足迹与搜索历史) 表结构
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
