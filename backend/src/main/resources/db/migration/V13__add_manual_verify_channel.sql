-- ==============================================================================
-- V13：校园认证「无邮箱通道」（学生证人工审核）
--
-- 背景：V1 的校园认证只有一条通道 —— 学号 + 校园邮箱 + 邮件验证码。但一部分高校
-- 根本不提供学生邮箱，这条通道对那部分学生永远走不通；而校园认证是「发布商品」的
-- 硬前置，走不通就等于用不了平台。
--
-- 本迁移新增第二条通道（人工审核）：学生填 学校 + 学号 + 姓名，并上传学生证/校园卡
-- 照片，由管理员审核通过后点亮**同样的**认证标识。两条通道写同一张 student_verify 表，
-- 用 verify_method 区分（EMAIL / MANUAL）；下游（发布商品闸门、卖家"已认证"标识）
-- 只认 verify_status = 'SUCCESS'，不需要也不应该区分通道。
--
-- 本迁移只做结构变更，不删改任何业务数据：
--   1. school_email 允许为空 —— 人工通道没有邮箱，旧的 NOT NULL 会挡住插入；
--   2. verify_status 的取值域加入 'REJECTED' —— 被驳回的申请必须有落库状态，
--      否则只能停留在 PENDING，用户分不清"等待审核"和"已被驳回"；
--   3. 新增人工通道字段：verify_method / real_name / evidence_url /
--      review_note / reviewer_id / review_time；
--   4. 新增 (school_id, student_number) 的 SUCCESS 部分唯一索引 —— 邮箱通道靠 V12 的
--      邮箱唯一索引防"一人多号"，人工通道没有邮箱，改以学号承担同一职责。
-- ==============================================================================

-- 1) 邮箱允许为空（历史行不受影响）
ALTER TABLE campus_trade.student_verify ALTER COLUMN school_email DROP NOT NULL;

-- 2) 状态取值域加入 REJECTED（先删后加：CHECK 约束无法就地扩展取值）
ALTER TABLE campus_trade.student_verify DROP CONSTRAINT IF EXISTS chk_student_verify_status_domain;
ALTER TABLE campus_trade.student_verify
    ADD CONSTRAINT chk_student_verify_status_domain
        CHECK (verify_status IN ('PENDING', 'SUCCESS', 'REJECTED'));

-- 3) 人工通道字段（历史行默认 EMAIL，语义与它们当年走的通道一致）
ALTER TABLE campus_trade.student_verify
    ADD COLUMN IF NOT EXISTS verify_method VARCHAR(20) NOT NULL DEFAULT 'EMAIL';
ALTER TABLE campus_trade.student_verify ADD COLUMN IF NOT EXISTS real_name VARCHAR(50);
ALTER TABLE campus_trade.student_verify ADD COLUMN IF NOT EXISTS evidence_url VARCHAR(255);
ALTER TABLE campus_trade.student_verify ADD COLUMN IF NOT EXISTS review_note VARCHAR(255);
ALTER TABLE campus_trade.student_verify ADD COLUMN IF NOT EXISTS reviewer_id BIGINT;
ALTER TABLE campus_trade.student_verify ADD COLUMN IF NOT EXISTS review_time TIMESTAMP;

-- verify_method 的取值域（与 Java 枚举 StudentVerifyStatus 之外的通道枚举一一对应）
ALTER TABLE campus_trade.student_verify DROP CONSTRAINT IF EXISTS chk_student_verify_method_domain;
ALTER TABLE campus_trade.student_verify
    ADD CONSTRAINT chk_student_verify_method_domain
        CHECK (verify_method IN ('EMAIL', 'MANUAL'));

-- 审核队列按"先提交先审核"排队；状态列建索引便于筛选 PENDING/REJECTED
CREATE INDEX IF NOT EXISTS idx_student_verify_status ON campus_trade.student_verify (verify_status);
CREATE INDEX IF NOT EXISTS idx_student_verify_created ON campus_trade.student_verify (created_time DESC);

-- 4) 学号唯一（仅 SUCCESS）：
--    与 V12 处理邮箱冲突同样的取舍 —— 发现历史冲突时**跳过建索引并打印明细**，
--    迁移本身仍然成功、不删任何数据，由人工按明细处理后手动补建索引。
DO $$
DECLARE
    conflict_cnt INT;
    conflict_detail TEXT;
BEGIN
    SELECT count(*) INTO conflict_cnt FROM (
        SELECT school_id, student_number
          FROM campus_trade.student_verify
         WHERE verify_status = 'SUCCESS'
         GROUP BY school_id, student_number
        HAVING count(DISTINCT user_id) > 1
    ) t;

    IF conflict_cnt > 0 THEN
        SELECT string_agg(
                   format('school_id=%s, student_number=%s, user_ids=%s', school_id, student_number, user_ids),
                   E'\n  '
               )
          INTO conflict_detail
          FROM (
              SELECT school_id,
                     student_number,
                     string_agg(DISTINCT user_id::text, ',') AS user_ids
                FROM campus_trade.student_verify
               WHERE verify_status = 'SUCCESS'
               GROUP BY school_id, student_number
              HAVING count(DISTINCT user_id) > 1
          ) c;

        RAISE NOTICE '[V13] 检测到同一学号多条 SUCCESS 认证的历史冲突，跳过建唯一索引（数据未被删改）：%',
            conflict_detail;
        RAISE NOTICE '[V13] 处理完后手动执行：CREATE UNIQUE INDEX uk_student_verify_school_number_success '
            'ON campus_trade.student_verify (school_id, student_number) WHERE verify_status = ''SUCCESS'';';
    ELSE
        CREATE UNIQUE INDEX IF NOT EXISTS uk_student_verify_school_number_success
            ON campus_trade.student_verify (school_id, student_number)
            WHERE verify_status = 'SUCCESS';
    END IF;
END $$;
