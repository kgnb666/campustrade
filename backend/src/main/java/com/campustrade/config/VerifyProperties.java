package com.campustrade.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
 * <p><b>演示模式</b>：{@code verify.demo-mode-enabled} + {@code verify.demo-emails}（邮箱白名单）。
 * 面向"个人开发者没有学校邮箱，但仍要演示认证流程"的场景：白名单内的邮箱不发真实邮件，
 * 验证码改为随 {@code /student/verify} 的响应返回，前端自动填入并标注"演示模式"。</p>
 *
 * <p><b>为什么必须是白名单而不是全局开关</b>：验证码随响应返回等于"零成本自证"，
 * 一旦作用到全站，校园认证就不再可信。白名单把影响面钉死在配置点名的几个邮箱上，
 * 其余邮箱的链路（后缀校验、限流、真实邮件、失败不降级）一个字节都不变。
 * 白名单为空时整个演示模式视为<b>未开启</b>（{@link #isDemoModeActive()}），
 * 且 {@link VerifyDemoGuard} 会在"开关开着但白名单为空"时直接拒绝启动。</p>
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

    /**
     * 演示模式总开关（环境变量 {@code VERIFY_DEMO_MODE_ENABLED}），默认 {@code false}。
     *
     * <p>单独打开它没有任何效果：只有同时出现在 {@link #demoEmails} 白名单里的邮箱才会走演示通道。</p>
     */
    private boolean demoModeEnabled = false;

    /**
     * 演示邮箱白名单（环境变量 {@code VERIFY_DEMO_EMAILS}，逗号分隔）。
     *
     * <p>建议使用明显不存在的演示号（例如 {@code demo@xxx.edu.cn}），不要填入真实学生邮箱：
     * 白名单内的邮箱可以不做邮件验证直接完成认证。</p>
     */
    private List<String> demoEmails = new ArrayList<>();

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

    /**
     * 演示模式是否真正生效：开关已打开 <b>且</b> 白名单里至少有一个非空邮箱。
     *
     * <p>把"白名单为空"视作未开启，是为了让"误把开关打开"退化为无行为，而不是让全站邮箱
     * 都变成可以零成本自证。</p>
     */
    public boolean isDemoModeActive() {
        return demoModeEnabled && !normalizedDemoEmails().isEmpty();
    }

    /**
     * 该校园邮箱是否命中演示白名单（大小写与首尾空白不敏感）。
     *
     * @param email 待判定邮箱，可为 {@code null} / 空白（返回 {@code false}）
     */
    public boolean isDemoEmail(String email) {
        if (!isDemoModeActive() || !StringUtils.hasText(email)) {
            return false;
        }
        return normalizedDemoEmails().contains(email.trim().toLowerCase());
    }

    /**
     * 归一化后的白名单（去空白、转小写、去重、保持配置顺序）。
     *
     * <p>与校园邮箱的归一化规则保持一致（{@code trim().toLowerCase()}）：配置里写成
     * {@code Demo@X.edu.cn} 与 {@code demo@x.edu.cn} 必须命中同一个邮箱。</p>
     */
    public Set<String> normalizedDemoEmails() {
        Set<String> normalized = new LinkedHashSet<>();
        if (demoEmails == null) {
            return normalized;
        }
        for (String email : demoEmails) {
            if (StringUtils.hasText(email)) {
                normalized.add(email.trim().toLowerCase());
            }
        }
        return normalized;
    }
}
