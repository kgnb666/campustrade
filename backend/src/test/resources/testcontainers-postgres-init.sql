-- ==============================================================================
--  Testcontainers PostgreSQL 初始化脚本（容器启动时执行，早于 Spring/Flyway）
--
--  与开发环境 docker/postgres/init.sql 的第一步保持一致：预先创建 campus_trade schema。
--
--  为什么必须预建：Flyway 只有在"自己创建 schema"时才会往 flyway_schema_history 里写一条
--  version 为 NULL、描述为 "<< Flyway Schema Creation >>" 的记录。开发库里 schema 是
--  init.sql 先建好的，所以没有这条记录；而空容器里若让 Flyway 建 schema，就会出现这条记录，
--  于是 flyway.info().applied() 里混进 version=null 的条目，直接让
--  CampusTradeStage4B1/5B/5D/6B/6C 的迁移历史断言（m.getVersion().getVersion()）抛 NPE。
--  预建 schema 后迁移历史与开发环境形态一致：只有带版本号的迁移记录（不再写死具体区间，
--  迁移会持续增加，例如 "V1..V9" 这类表述必然过时）。
--
--  注意：这里只建 schema，不建表——表结构全部由 db/migration 下的版本化迁移负责，
--  这样测试用的库仍然是"由 Flyway 从零迁移出来的干净库"。
-- ==============================================================================

CREATE SCHEMA IF NOT EXISTS campus_trade;
