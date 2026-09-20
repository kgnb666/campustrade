-- ==============================================================================
-- V9: 校园认证可选高校新增「广西民族师范学院」
--
-- 说明：
--   学生认证时会校验「校园邮箱后缀必须与所选高校匹配」（见 StudentVerifyServiceImpl），
--   因此 email_suffix 必须是该校真实可用的邮箱域名。
--   此处采用学校官网主域 gxnun.edu.cn（与 PKU/FDU/ZJU 等条目写法一致）。
--   若该校学生邮箱实际带子域前缀（例如 @stu.gxnun.edu.cn），
--   由于校验用的是 endsWith，需把下面这条改成对应后缀，否则学生的真实邮箱会被判为不匹配。
-- ==============================================================================

INSERT INTO campus_trade.campus_school (id, school_name, school_code, email_suffix, status) VALUES
(5, '广西民族师范学院', 'GXNUN', '@gxnun.edu.cn', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;
