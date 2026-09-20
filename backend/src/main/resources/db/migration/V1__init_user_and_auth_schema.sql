-- ==============================================================================
-- Flyway Migration V1: Stage 1 用户中心与校园认证基础表结构
-- ==============================================================================

CREATE SCHEMA IF NOT EXISTS campus_trade;

-- 1. 用户核心表
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

-- 2. 高校字典表
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

-- 3. 学生实名认证表
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
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_user_credit_user ON campus_trade.user_credit(user_id);
