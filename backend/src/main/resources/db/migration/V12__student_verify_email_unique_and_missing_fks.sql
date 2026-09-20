-- ==============================================================================
-- Flyway Migration V12: 校园邮箱唯一性 + 补齐 V10 未覆盖的关联外键（终审遗留）
--
-- 本迁移只做两件事，且**绝不删除、不修改任何既有业务数据**：
--   1) 给 campus_trade.student_verify(school_id, school_email) 建一条
--      "仅 verify_status = 'SUCCESS' 的行"的部分唯一索引，堵住"同一校园邮箱认证多个账号"；
--   2) 补齐 V10__data_integrity_constraints.sql 未覆盖的 12 条关联外键，**一律 NOT VALID**。
--
-- 每个约束都放在 DO 块里做存在性判断（可重复执行），因此本迁移在
--   * Testcontainers 全新空库（V1→V12 连续执行），
--   * 已有历史造数的本机开发库（run-backend.cmd 启动时）
-- 上都必须一次成功；对既有孤儿行只登记约束、不校验、不清理（见下）。
-- ==============================================================================
--
-- ==============================================================================
-- 【锁与耗时说明】（大表上直接执行 ADD CONSTRAINT 会阻塞线上写入，务必先读这一段）
-- ==============================================================================
--   * ALTER TABLE ... ADD CONSTRAINT ... FOREIGN KEY
--       - 不带 NOT VALID：PostgreSQL 需要扫描引用表全量数据以校验既有行，
--         期间对引用表持有 **ACCESS EXCLUSIVE 锁**（该表的读写全部被阻塞）；
--         百万行级别的表上是分钟级操作，且会连带阻塞业务写入。
--       - 带 NOT VALID：只在系统目录里登记约束定义（对**新增/更新**的行立即生效），
--         不扫描既有数据，ACCESS EXCLUSIVE 锁只持续极短时间（毫秒级）。
--         => 本迁移全部使用 NOT VALID，这是"先止血、后收口"的标准做法。
--   * CREATE UNIQUE INDEX（非 CONCURRENTLY）：对 student_verify 持有 **SHARE 锁**
--     （阻塞写入、不阻塞 SELECT）；该表每个用户至多一行，数据量很小，耗时可忽略。
--     CONCURRENTLY 不能在事务中执行，而 Flyway 默认把迁移放在事务里，故不使用。
--   * 本迁移在"空库"上执行时所有孤儿计数都是 0，只登记约束，不产生任何数据变更。
--
-- ==============================================================================
-- 【NOT VALID 之后如何收口】（生产运维步骤）
-- ==============================================================================
--   1) 只读盘点：执行 backend/docs/data-cleanup-orphans.sql 第 1 段，确认各外键的孤儿行数量；
--   2) 按业务要求处理孤儿行：默认什么都不做（NOT VALID 已经能阻止**新的**孤儿行）；
--      确需清理时按该脚本第 2 段先备份、第 3 段再清理；
--   3) 清理干净后再把外键升级为全量校验（该操作只取 SHARE UPDATE EXCLUSIVE 锁，不阻塞读写）：
--          ALTER TABLE campus_trade.review          VALIDATE CONSTRAINT fk_review_reviewer;
--          ALTER TABLE campus_trade.review          VALIDATE CONSTRAINT fk_review_reviewed_user;
--          ALTER TABLE campus_trade.review_like     VALIDATE CONSTRAINT fk_review_like_review;
--          ALTER TABLE campus_trade.review_like     VALIDATE CONSTRAINT fk_review_like_user;
--          ALTER TABLE campus_trade.browse_history  VALIDATE CONSTRAINT fk_browse_history_user;
--          ALTER TABLE campus_trade.browse_history  VALIDATE CONSTRAINT fk_browse_history_goods;
--          ALTER TABLE campus_trade.search_history  VALIDATE CONSTRAINT fk_search_history_user;
--          ALTER TABLE campus_trade.report          VALIDATE CONSTRAINT fk_report_reporter;
--          ALTER TABLE campus_trade.admin_audit_log VALIDATE CONSTRAINT fk_admin_audit_log_admin;
--          ALTER TABLE campus_trade.student_verify  VALIDATE CONSTRAINT fk_student_verify_user;
--          ALTER TABLE campus_trade.student_verify  VALIDATE CONSTRAINT fk_student_verify_school;
--          ALTER TABLE campus_trade.user_credit     VALIDATE CONSTRAINT fk_user_credit_user;
--   4) 复核（validated 应为 true）：
--          SELECT conname, convalidated FROM pg_constraint
--           WHERE connamespace = 'campus_trade'::regnamespace AND contype = 'f' ORDER BY 1;
--   注意：VALIDATE CONSTRAINT 只有在孤儿行清理干净后才会成功；它失败是"库里仍有脏数据"的证据，
--   而不是迁移缺陷。因此本迁移**不含** VALIDATE 语句：若把它放进迁移，
--   "干净库"与"历史脏库"的行为就会分叉（后者直接迁移失败），违背"两库都能一次成功"的要求。
-- ==============================================================================

-- ==============================================================================
-- 1. 校园邮箱唯一性：一个校园邮箱只能被一个账号核销成功
--
--    背景：student_verify 上原先只有 (user_id) 索引，没有任何"邮箱 → 账号"的唯一约束，
--    因此同一个校园邮箱（school_id + school_email）可以被无限多个账号先后核销成功，
--    一个学生身份 = 任意多个账号（校园身份背书的实际价值被架空）。
--
--    为什么用"部分唯一索引"而不是普通唯一约束：
--      * PENDING 行必须允许重复：同一个邮箱可以在多个账号上处于"待核销"状态
--        （谁先核销谁得，其余请求会被应用层与索引同时拦下）；
--      * 同一账号重发验证码时复用的是同一行（不会新增 PENDING 行）。
--      只有 verify_status = 'SUCCESS' 才代表"这个邮箱确实绑定到了这个账号"。
--
--    冲突数据策略（不删数据）：
--      建索引前先只读排查是否已存在冲突（同一 school_id + school_email 有多条 SUCCESS）。
--      * 无冲突 → 直接建索引；
--      * 有冲突 → **跳过建索引**，用 RAISE NOTICE 打印冲突组数、行数与明细，
--        迁移本身仍然成功（不中断启动）。清理与建索引由运维按
--        backend/docs/data-cleanup-orphans.sql 第 5 段的流程人工执行。
--      这样既保证"两库都能一次迁移成功"，也绝不静默删改历史数据。
-- ==============================================================================

DO $$
DECLARE
    conflict_groups  INTEGER := 0;
    conflict_rows    INTEGER := 0;
    conflict_detail  TEXT;
BEGIN
    SELECT count(*), COALESCE(sum(cnt), 0)
      INTO conflict_groups, conflict_rows
      FROM (
            SELECT count(*) AS cnt
              FROM campus_trade.student_verify
             WHERE verify_status = 'SUCCESS'
             GROUP BY school_id, school_email
            HAVING count(*) > 1
           ) g;

    IF conflict_groups > 0 THEN
        SELECT string_agg(format('school_id=%s, school_email=%s, user_ids=%s', school_id, school_email, user_ids), E'\n  ')
          INTO conflict_detail
          FROM (
                SELECT school_id,
                       school_email,
                       string_agg(user_id::text, ',' ORDER BY user_id) AS user_ids
                  FROM campus_trade.student_verify
                 WHERE verify_status = 'SUCCESS'
                 GROUP BY school_id, school_email
                HAVING count(*) > 1
               ) d;

        RAISE NOTICE '[V12] 检测到校园邮箱重复认证冲突：冲突组数=%，涉及行数=%', conflict_groups, conflict_rows;
        RAISE NOTICE '[V12] 冲突明细（school_id / school_email / user_ids）：%', conflict_detail;
        RAISE NOTICE '[V12] 已跳过创建 uk_student_verify_email_success（迁移继续，未删除任何数据）。';
        RAISE NOTICE '[V12] 运维请按 backend/docs/data-cleanup-orphans.sql 第 5 段处理：';
        RAISE NOTICE '[V12]   1) 人工确认保留哪个账号的 SUCCESS 记录；';
        RAISE NOTICE '[V12]   2) 把其余记录按业务决定改为 PENDING（或保留）；';
        RAISE NOTICE '[V12]   3) 再执行 CREATE UNIQUE INDEX uk_student_verify_email_success ON campus_trade.student_verify (school_id, school_email) WHERE verify_status = ''SUCCESS'';';
        RETURN;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_indexes
         WHERE schemaname = 'campus_trade' AND indexname = 'uk_student_verify_email_success'
    ) THEN
        -- 用 EXECUTE 执行 DDL（PL/pgSQL 中对非 DML 语句使用动态 SQL 是标准做法）
        EXECUTE 'CREATE UNIQUE INDEX uk_student_verify_email_success '
             || 'ON campus_trade.student_verify (school_id, school_email) '
             || 'WHERE verify_status = ''SUCCESS''';
        RAISE NOTICE '[V12] 已创建部分唯一索引 uk_student_verify_email_success（仅约束 SUCCESS 行）';
    ELSE
        RAISE NOTICE '[V12] 部分唯一索引 uk_student_verify_email_success 已存在，跳过';
    END IF;
END $$;

-- ==============================================================================
-- 2. 补齐 V10 未覆盖的关联外键（全部 NOT VALID）
--
--    每条外键的处理顺序：先只读统计孤儿行（仅 RAISE NOTICE 提示，不做任何更改），
--    再在"约束不存在时"登记 NOT VALID 外键。因此：
--      * 新库：孤儿计数全为 0，只登记约束；
--      * 历史造数库：孤儿行保留原样（既不删除也不改写），约束只对新数据生效。
--
--    ⚠️ 关于 report.target_id：
--      report 是**多态**表（target_type ∈ GOODS / REVIEW / USER，见 V7 的
--      chk_report_target_type），target_id 的指向随 target_type 变化，
--      因此**无法**为它声明一条指向单表的外键（那会让举报评价/用户时全部插入失败）。
--      该列的一致性由两层保证：
--        a) chk_report_target_type 限定取值域；
--        b) 应用层 ReportServiceImpl#validateTargetAndSelfReport 在写入前做多态存在性校验。
--      这里不建外键是刻意的设计取舍，不是遗漏（运维脚本中已用注释标明）。
-- ==============================================================================

DO $$
DECLARE
    item         RECORD;
    orphan_count BIGINT;
BEGIN
    FOR item IN
        SELECT * FROM (VALUES
            ('fk_review_reviewer',      'campus_trade.review',          'reviewer_id',      'campus_trade."user"', 'id'),
            ('fk_review_reviewed_user', 'campus_trade.review',          'reviewed_user_id', 'campus_trade."user"', 'id'),
            ('fk_review_like_review',   'campus_trade.review_like',     'review_id',        'campus_trade.review', 'id'),
            ('fk_review_like_user',     'campus_trade.review_like',     'user_id',          'campus_trade."user"', 'id'),
            ('fk_browse_history_user',  'campus_trade.browse_history',  'user_id',          'campus_trade."user"', 'id'),
            ('fk_browse_history_goods', 'campus_trade.browse_history',  'goods_id',         'campus_trade.goods',  'id'),
            ('fk_search_history_user',  'campus_trade.search_history',  'user_id',          'campus_trade."user"', 'id'),
            ('fk_report_reporter',      'campus_trade.report',          'reporter_id',      'campus_trade."user"', 'id'),
            ('fk_admin_audit_log_admin','campus_trade.admin_audit_log', 'admin_id',         'campus_trade."user"', 'id'),
            ('fk_student_verify_user',  'campus_trade.student_verify',  'user_id',          'campus_trade."user"', 'id'),
            ('fk_student_verify_school','campus_trade.student_verify',  'school_id',        'campus_trade.campus_school', 'id'),
            ('fk_user_credit_user',     'campus_trade.user_credit',     'user_id',          'campus_trade."user"', 'id')
        ) AS t(constraint_name, ref_table, ref_column, target_table, target_column)
    LOOP
        -- 2.1 只读排查（不做任何数据变更）
        EXECUTE format('SELECT count(*) FROM %s c LEFT JOIN %s p ON c.%I = p.%I WHERE p.%I IS NULL',
                       item.ref_table, item.target_table, item.ref_column, item.target_column, item.target_column)
           INTO orphan_count;

        IF orphan_count > 0 THEN
            RAISE NOTICE '[V12] % 存在孤儿行 % 条（NOT VALID 只约束新增/更新数据，既有行保留不删；清理见 backend/docs/data-cleanup-orphans.sql）',
                         item.constraint_name, orphan_count;
        END IF;

        -- 2.2 登记 NOT VALID 外键（可重复执行）
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = item.constraint_name) THEN
            EXECUTE format('ALTER TABLE %s ADD CONSTRAINT %I FOREIGN KEY (%I) REFERENCES %s (%I) NOT VALID',
                           item.ref_table, item.constraint_name, item.ref_column, item.target_table, item.target_column);
            RAISE NOTICE '[V12] 已登记外键 % (NOT VALID)', item.constraint_name;
        END IF;
    END LOOP;
END $$;

-- ==============================================================================
-- 3. 迁移结果自检（只读，输出到迁移日志便于核对）
-- ==============================================================================
DO $$
DECLARE
    total_fk      INTEGER;
    not_valid_fk  INTEGER;
    email_index   INTEGER;
BEGIN
    SELECT count(*) FILTER (WHERE conname IN (
                'fk_review_reviewer', 'fk_review_reviewed_user', 'fk_review_like_review', 'fk_review_like_user',
                'fk_browse_history_user', 'fk_browse_history_goods', 'fk_search_history_user', 'fk_report_reporter',
                'fk_admin_audit_log_admin', 'fk_student_verify_user', 'fk_student_verify_school', 'fk_user_credit_user'
           )),
           count(*) FILTER (WHERE conname IN (
                'fk_review_reviewer', 'fk_review_reviewed_user', 'fk_review_like_review', 'fk_review_like_user',
                'fk_browse_history_user', 'fk_browse_history_goods', 'fk_search_history_user', 'fk_report_reporter',
                'fk_admin_audit_log_admin', 'fk_student_verify_user', 'fk_student_verify_school', 'fk_user_credit_user'
           ) AND NOT convalidated)
      INTO total_fk, not_valid_fk
      FROM pg_constraint
     WHERE connamespace = 'campus_trade'::regnamespace AND contype = 'f';

    SELECT count(*) INTO email_index
      FROM pg_indexes
     WHERE schemaname = 'campus_trade' AND indexname = 'uk_student_verify_email_success';

    RAISE NOTICE '[V12] 本次涉及的 12 条外键：已存在 % 条，其中 NOT VALID（待收口）% 条；校园邮箱唯一索引：% 条',
                 total_fk, not_valid_fk, email_index;
END $$;
