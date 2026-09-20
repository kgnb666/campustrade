package com.campustrade.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 校园身份认证（验证码下发与核销）配置属性。
 *
 * <p><b>邮件通道开关</b>：{@code verify.mail-enabled}</p>
 * <ul>
 *   <li>{@code true}：验证码通过真实邮件下发（SMTP 连接信息由 {@code MAIL_*} 环境变量注入）；
 *       发送失败一律抛出明确业务错误，<b>绝不</b>降级为把验证码写进响应或日志；</li>
 *   <li>{@code false}：仅本地开发使用，验证码写入服务端日志（{@code [DEV-ONLY]} 行）。
 *       生产环境（prod profile）禁止该组合，见 {@link VerifyMailProdGuard}。</li>
 * </ul>
 *
 * <p>SMTP 连接信息只从环境变量读取，源码与配置文件中不保留任何可用的邮箱账号或口令字面量。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "verify")
public class VerifyProperties {

    /**
     * 是否启用真实邮件下发。默认 {@code false}（本地开发从日志取验证码），生产必须为 {@code true}。
     */
    private boolean mailEnabled = false;

    /** SMTP 服务器主机（环境变量 MAIL_HOST，生产必需） */
    private String mailHost;

    /** SMTP 服务器端口（环境变量 MAIL_PORT，默认 465 = SMTPS） */
    private int mailPort = 465;

    /** SMTP 登录账号（环境变量 MAIL_USERNAME，生产必需） */
    private String mailUsername;

    /** SMTP 登录口令（环境变量 MAIL_PASSWORD，生产必需） */
    private String mailPassword;

    /** 发件人地址（环境变量 MAIL_FROM，生产必需；通常与 MAIL_USERNAME 相同） */
    private String mailFrom;

    /** 发件人显示名 */
    private String mailFromName = "CampusTrade 校园集市";

    /** 是否使用 SSL(SMTPS) 直连。465 端口为 true；587(STARTTLS) 时应置为 false */
    private boolean mailSslEnabled = true;

    /** SMTP 建连/读写超时（毫秒），避免发送失败时把请求线程长时间挂住 */
    private int mailTimeoutMs = 5000;

    /** 验证码有效期（分钟），同时也是核验失败计数键的 TTL */
    private int codeTtlMinutes = 5;

    /** 同一验证码允许的最大核验失败次数，达到即作废，必须重新获取 */
    private int maxFailures = 5;

    /**
     * 同一发起人对同一校园邮箱在 {@code userEmailSendWindowHours} 窗口内允许的最大发送次数。
     *
     * <p>维度是"发起人 × 邮箱"而不是"仅邮箱"：只按邮箱计数时，任意登录用户都能把他人邮箱的
     * 当日额度打满，使被攻击者当天无法完成认证，同时给对方持续投递垃圾验证码邮件。</p>
     */
    private int sendLimitPerUserEmail = 3;

    /** 上述"发起人 × 邮箱"计数的时间窗口（小时） */
    private int userEmailSendWindowHours = 24;

    /** 同一用户在同一窗口内允许的最大发送次数 */
    private int sendLimitPerUser = 3;

    /** 上述"按用户"计数的时间窗口（分钟） */
    private int userSendWindowMinutes = 10;

    /**
     * SMTP 连接信息是否完整（host / username / password / from 均非空白）。
     */
    public boolean isSmtpConfigured() {
        return StringUtils.hasText(mailHost)
                && StringUtils.hasText(mailUsername)
                && StringUtils.hasText(mailPassword)
                && StringUtils.hasText(mailFrom);
    }
}
