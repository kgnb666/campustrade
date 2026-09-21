package com.campustrade.service.mail;

import com.campustrade.config.VerifyProperties;
import com.campustrade.exception.BusinessException;
import jakarta.mail.internet.InternetAddress;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;

/**
 * 校园认证验证码下发通道（唯一出口）。
 *
 * <p>校园认证验证码是一次性凭据，它的去向有三个，取决于配置：</p>
 * <ul>
 *   <li><b>{@code mail-enabled=true}（生产必须）</b>：经 SMTP 发送到学生校园邮箱。发送失败时抛出明确的业务错误，
 *       <b>绝不</b>降级为"把验证码写进日志或响应"——降级等于把认证重新变回零成本自证；</li>
 *   <li><b>{@code mail-enabled=false}（仅本地开发）</b>：验证码写入服务端日志文件，供本机联调时取用。
 *       该分支在 prod profile 下不可达（{@link com.campustrade.config.VerifyMailProdGuard} 会拒绝启动），
 *       这里再做一次防御性判断，确保验证码在任何情况下都不会因为"配置写错"而被写进生产日志。</li>
 *   <li><b>演示通道（{@code verify.demo-mode-enabled} + {@code verify.demo-emails} 白名单）</b>：
 *       命中白名单的邮箱既不发邮件也不写日志，验证码改由调用方随接口响应返回，供"没有学校邮箱
 *       也要演示认证流程"的场景使用。它<b>只对配置点名的邮箱生效</b>，其余邮箱的链路完全不变。</li>
 * </ul>
 *
 * <p><b>日志纪律</b>：全类中只有 {@code [DEV-ONLY]} 那一行允许出现验证码明文，且它只在
 * {@code mail-enabled=false} 且非 prod 时执行。演示通道的日志只点名邮箱、不含验证码
 * （验证码已随响应返回给调用方，不需要也不应该再落进日志）。其余日志一律只记录掩码后的邮箱与结果。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VerifyCodeMailSender {

    /** 本地开发通道的固定标记：便于日志巡检与安全扫描一眼识别"此处会打印验证码"。 */
    public static final String DEV_ONLY_TAG = "[DEV-ONLY]";

    /**
     * 验证码实际去向。调用方据此判断"验证码是否已经离开服务端"。
     */
    public enum Channel {
        /** 经 SMTP 发送到学生校园邮箱 */
        MAIL,
        /** 写入服务端日志（仅本地开发） */
        DEV_LOG,
        /** 演示通道：未下发，由调用方随接口响应返回 */
        DEMO
    }

    private final VerifyProperties verifyProperties;
    private final ObjectProvider<JavaMailSender> javaMailSenderProvider;
    private final Environment environment;

    /**
     * 下发验证码。这是验证码离开服务端的唯一出口。
     *
     * @param schoolEmail 学生校园邮箱（收件人）
     * @param schoolName  高校名称（用于邮件正文与日志）
     * @param verifyCode  6 位验证码明文
     * @return 验证码的实际去向；{@link Channel#DEMO} 表示<b>没有</b>下发，调用方必须自行把验证码交给用户
     * @throws BusinessException 邮件通道故障时抛出（不降级、不返回验证码）
     */
    public Channel sendVerifyCode(String schoolEmail, String schoolName, String verifyCode) {
        // 演示通道优先级最高：命中白名单就既不发邮件也不写日志。
        // 放在 mail-enabled 判断之前是刻意的——演示机通常 mail-enabled=true（prod 强制），
        // 但那里的学生邮箱是虚构的，真发邮件只会得到退信，而学生永远收不到验证码。
        if (verifyProperties.isDemoEmail(schoolEmail)) {
            log.warn("校园认证演示模式：跳过验证码下发，验证码将随接口响应返回（仅限演示环境）: email={}",
                    maskEmail(schoolEmail));
            return Channel.DEMO;
        }

        if (!verifyProperties.isMailEnabled()) {
            if (isProdProfile()) {
                // 生产环境走到这里说明配置被改错了：宁可直接失败，也不能把验证码写进日志
                log.error("生产环境校园认证邮件通道未启用，拒绝降级为日志输出: email={}", maskEmail(schoolEmail));
                throw new BusinessException(500, "校园认证邮件通道未启用，验证码无法下发，请联系平台管理员");
            }

            // 仅此一处允许出现验证码明文：本地开发（verify.mail-enabled=false）从日志文件取码
            log.info("{} 校园认证验证码已生成（仅限本地开发使用，生产环境必须 verify.mail-enabled=true 走真实邮件）"
                            + "email={}, code={}, ttlMinutes={}",
                    DEV_ONLY_TAG, maskEmail(schoolEmail), verifyCode, verifyProperties.getCodeTtlMinutes());
            return Channel.DEV_LOG;
        }

        sendByMail(schoolEmail, schoolName, verifyCode);
        log.info("校园认证验证码邮件已发送: email={}, ttlMinutes={}",
                maskEmail(schoolEmail), verifyProperties.getCodeTtlMinutes());
        return Channel.MAIL;
    }

    /**
     * 经 SMTP 真实发送。任何异常都转为明确的业务错误向上抛出，不做任何降级处理。
     */
    private void sendByMail(String schoolEmail, String schoolName, String verifyCode) {
        // 配置缺失与"发送失败"必须给出不同提示：前者是运维配置问题（改配置即可），
        // 后者可能是 SMTP 暂时不可用（重试即可），把两者混为一谈会让排查走弯路。
        if (!verifyProperties.isSmtpConfigured()) {
            log.error("校园认证邮件通道已开启，但 SMTP 配置不完整（MAIL_HOST / MAIL_USERNAME / "
                    + "MAIL_PASSWORD / MAIL_FROM 存在空白项）");
            throw new BusinessException(500, "验证码邮件发送失败：服务端 SMTP 配置不完整，请联系平台管理员");
        }

        JavaMailSender mailSender = javaMailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            log.error("校园认证邮件通道已开启，但 JavaMailSender 未装配（SMTP 配置缺失）");
            throw new BusinessException(500, "验证码邮件发送失败：服务端 SMTP 配置缺失，请联系平台管理员");
        }

        try {
            var message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            String from = verifyProperties.getMailFrom();
            if (StringUtils.hasText(verifyProperties.getMailFromName())) {
                helper.setFrom(new InternetAddress(from, verifyProperties.getMailFromName(),
                        StandardCharsets.UTF_8.name()));
            } else {
                helper.setFrom(from);
            }
            helper.setTo(schoolEmail);
            helper.setSubject("【CampusTrade 校园集市】校园身份认证验证码");
            helper.setText(buildTextBody(schoolName, verifyCode), false);

            mailSender.send(message);
        } catch (Exception e) {
            // 刻意捕获 Exception：SMTP 不可达、认证失败、地址非法等任何原因都必须变成同一个明确的业务错误，
            // 绝不允许"发信失败但把验证码返回给调用方"这种降级路径存在。
            log.error("校园认证验证码邮件发送失败（不降级为日志输出或返回值）: email={}, reason={}",
                    maskEmail(schoolEmail), e.getMessage());
            throw new BusinessException(500, "验证码邮件发送失败，请稍后重试或联系平台管理员");
        }
    }

    private String buildTextBody(String schoolName, String verifyCode) {
        return """
                【CampusTrade 校园集市】校园身份认证

                您正在申请%s学生身份认证，验证码为：

                    %s

                验证码 %d 分钟内有效，请勿转发或告知他人。CampusTrade 工作人员不会向您索要验证码。
                如非本人操作，请忽略本邮件。
                """.formatted(StringUtils.hasText(schoolName) ? schoolName : "", verifyCode,
                verifyProperties.getCodeTtlMinutes());
    }

    private boolean isProdProfile() {
        return environment.acceptsProfiles(Profiles.of("prod"));
    }

    /**
     * 邮箱掩码：日志里只保留"能定位到具体邮箱、又不构成完整地址泄露"的最小信息。
     * 例如 {@code zhangsan@mails.tsinghua.edu.cn} → {@code z***n@mails.tsinghua.edu.cn}。
     */
    public static String maskEmail(String email) {
        if (!StringUtils.hasText(email)) {
            return "empty";
        }
        int at = email.lastIndexOf('@');
        if (at <= 0) {
            return "***";
        }
        String local = email.substring(0, at);
        String domain = email.substring(at);
        if (local.length() <= 2) {
            return local.charAt(0) + "***" + domain;
        }
        return local.charAt(0) + "***" + local.charAt(local.length() - 1) + domain;
    }
}
