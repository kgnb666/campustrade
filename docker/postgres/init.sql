-- ==============================================================================
-- CampusTrade 校园二手交易平台 - 数据库初始化脚本（最小职责）
--
-- ⚠️ 职责边界（请勿在本文件里加任何业务建表语句）
--
-- 本脚本只负责"schema 存在性与权限"这两件事：
--   1. 创建业务 schema campus_trade；
--   2. 把该 schema 的权限授予当前连接用户（即 POSTGRES_USER，不写死用户名）。
--
-- 业务表结构（表 / 索引 / 约束 / 种子数据）的唯一真相源是 Flyway 迁移：
--   backend/src/main/resources/db/migration/（按版本号递增，不在此处写死区间）
-- 历史上这里曾经复制过一份建表语句（与 Flyway 重复），导致同一张表出现两个
-- 互相漂移的定义（例如 user_credit.completed_count 只在这份脚本里存在过）。
-- 现在起唯一职责交给 Flyway：本文件即使被删掉也不影响新建库，
-- 因为 Flyway 会按 spring.flyway.schemas 自行创建缺失的 schema。
--
-- 本文件仅在 PostgreSQL 数据卷"首次初始化"时由官方镜像的
-- docker-entrypoint-initdb.d 机制执行一次（空数据卷）。
--
-- 不写死数据库名/用户名：编排里的 POSTGRES_DB / POSTGRES_USER 是可配置的，
-- 若这里写成字面量，换成别的名字时初始化会直接失败（容器起不来）。
-- ==============================================================================

CREATE SCHEMA IF NOT EXISTS campus_trade;

-- 授予 schema 权限给当前连接用户（= POSTGRES_USER，兼容任意用户名）
GRANT ALL ON SCHEMA campus_trade TO CURRENT_USER;

-- 设置默认搜索路径（数据库级设置，独立于任何业务表）
-- 数据库名同样不写死：用动态 SQL 取 current_database()
DO $$
BEGIN
    EXECUTE format('ALTER DATABASE %I SET search_path TO campus_trade, public', current_database());
END
$$;

COMMENT ON SCHEMA campus_trade IS 'CampusTrade 校园二手交易平台核心业务模式（业务表由 Flyway 迁移创建）';
