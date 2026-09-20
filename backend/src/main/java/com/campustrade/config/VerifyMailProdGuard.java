package com.campustrade.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 生产环境校园认证邮件通道的启动期守卫（fail-fast）。
 *
 * <p>校园认证验证码是"发布商品的硬前置 + 卖家已认证标识"的唯一依据，因此生产环境的验证码
 * <b>只能</b>经真实邮件下发。以下两种情况一律拒绝启动，而不是带着可被绕过的通道对外服务：</p>
 * <ul>
 *   <li>{@code verify.mail-enabled=false}：否则验证码会落进服务端日志文件，任何能读日志的人都能
 *       零成本完成认证；</li>
 *   <li>邮件通道已开启但 SMTP 连接信息缺失：此时发信必然失败，与其运行期每次认证都报错，
 *       不如在启动期就把问题暴露出来。</li>
 * </ul>
 *
 * <p>校验放在构造函数中，因此它发生在 Spring 上下文刷新阶段：校验不通过即上下文创建失败、
 * 进程拒绝启动（与阶段 2 的 JWT 密钥 fail-fast 风格一致）。</p>
 */
@Slf4j
@Component
@Profile("prod")
public class VerifyMailProdGuard {

    /** 生产环境必须注入的 SMTP 环境变量清单（用于错误提示，不含任何值本身）。 */
    private static final String REQUIRED_ENV_HINT =
            "请注入环境变量 MAIL_HOST / MAIL_PORT / MAIL_USERNAME / MAIL_PASSWORD / MAIL_FROM 后重新启动";

    public VerifyMailProdGuard(VerifyProperties properties) {
        if (!properties.isMailEnabled()) {
            throw new IllegalStateException(
                    "校园认证邮件通道未启用：生产环境（prod）拒绝启动。"
                            + "verify.mail-enabled=false 时验证码会写入服务端日志文件，"
                            + "等于任何能读取日志的人都能零成本完成校园认证。"
                            + "请将 verify.mail-enabled 置为 true 并配置 SMTP，"
                            + REQUIRED_ENV_HINT);
        }

        if (!properties.isSmtpConfigured()) {
            throw new IllegalStateException(
                    "校园认证 SMTP 配置缺失：生产环境（prod）拒绝启动。"
                            + "verify.mail-enabled=true 但 MAIL_HOST / MAIL_USERNAME / MAIL_PASSWORD / MAIL_FROM "
                            + "存在空白项，验证码将无法送达学生邮箱。"
                            + REQUIRED_ENV_HINT);
        }

        log.info("校园认证邮件通道配置校验通过（来源: 环境变量 MAIL_*，端口: {}，SSL: {}）",
                properties.getMailPort(), properties.isMailSslEnabled());
    }
}
