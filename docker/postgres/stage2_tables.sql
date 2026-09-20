-- ==============================================================================
-- CampusTrade Stage 2: 商品发布与商品浏览体系 数据表定义
-- Schema: campus_trade
-- ==============================================================================

SET search_path TO campus_trade;

-- 1. 商品分类表 (category)
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

-- 2. 商品表 (goods)
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

-- 3. 商品图片表 (goods_image)
CREATE TABLE IF NOT EXISTS campus_trade.goods_image (
    id BIGSERIAL PRIMARY KEY,
    goods_id BIGINT NOT NULL,
    image_url VARCHAR(255) NOT NULL,
    sort INT DEFAULT 0,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_goods_image_goods_id ON campus_trade.goods_image(goods_id);

-- 4. 商品标签表 (goods_tag)
CREATE TABLE IF NOT EXISTS campus_trade.goods_tag (
    id BIGSERIAL PRIMARY KEY,
    goods_id BIGINT NOT NULL,
    tag_name VARCHAR(50) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_goods_tag_goods_id ON campus_trade.goods_tag(goods_id);

-- 5. 初始化分类数据 (种子数据)
-- 一级分类 (parent_id = 0)
INSERT INTO campus_trade.category (id, parent_id, name, icon, sort, status)
VALUES 
    (1, 0, '电子产品', 'devices', 1, 1),
    (2, 0, '教材资料', 'menu_book', 2, 1),
    (3, 0, '生活用品', 'home_repair_service', 3, 1),
    (4, 0, '服饰鞋包', 'checkroom', 4, 1),
    (5, 0, '运动用品', 'sports_basketball', 5, 1)
ON CONFLICT (id) DO NOTHING;

-- 二级分类
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

-- 重置分类自增序列到最大值
SELECT setval('campus_trade.category_id_seq', COALESCE((SELECT MAX(id) FROM campus_trade.category), 1) + 1, false);
