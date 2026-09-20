package com.campustrade.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

/**
 * 校园认证验证码邮件通道装配。
 *
 * <p>只有 {@code verify.mail-enabled=true} 时才装配 {@link JavaMailSender}：
 * 关闭邮件通道（本地开发）时应用不会持有任何 SMTP 连接器，也就不存在"误用空配置发信"的可能。</p>
 *
 * <p>这里刻意不使用 Spring Boot 的 {@code spring.mail.*} 自动装配，而是把 SMTP 连接信息统一收敛到
 * {@code verify.*}（同样由 {@code MAIL_*} 环境变量注入）：配置来源单一，启动期校验（见
 * {@link VerifyMailProdGuard}）与运行时发送用的是同一份属性对象，不会出现"校验看的是 A、发信用的是 B"。</p>
 */
@Configuration
public class VerifyMailConfig {

    @Bean
    @ConditionalOnProperty(prefix = "verify", name = "mail-enabled", havingValue = "true")
    public JavaMailSender verifyJavaMailSender(VerifyProperties properties) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(properties.getMailHost());
        sender.setPort(properties.getMailPort());
        sender.setUsername(properties.getMailUsername());
        sender.setPassword(properties.getMailPassword());
        sender.setDefaultEncoding("UTF-8");

        Properties mailProperties = sender.getJavaMailProperties();
        mailProperties.put("mail.smtp.auth", "true");
        // 465 端口为 SMTPS 直连；587 端口改用 STARTTLS（verify.mail-ssl-enabled=false）
        mailProperties.put("mail.smtp.ssl.enable", String.valueOf(properties.isMailSslEnabled()));
        mailProperties.put("mail.smtp.starttls.enable", String.valueOf(!properties.isMailSslEnabled()));
        // 超时必须显式设置：否则 SMTP 不可达时请求线程会长时间挂住，前端只看到"转圈"
        mailProperties.put("mail.smtp.connectiontimeout", String.valueOf(properties.getMailTimeoutMs()));
        mailProperties.put("mail.smtp.timeout", String.valueOf(properties.getMailTimeoutMs()));
        mailProperties.put("mail.smtp.writetimeout", String.valueOf(properties.getMailTimeoutMs()));

        return sender;
    }
}
