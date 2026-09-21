package com.campustrade.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 校园认证演示模式的启动期护栏。
 *
 * <p>演示模式（{@code verify.demo-mode-enabled}）会让白名单邮箱的验证码随接口响应返回，
 * 也就是"不做邮件验证即可完成认证"。它是给个人开发者的演示便利，不是安全特性，
 * 因此这里做两件事：</p>
 *
 * <ul>
 *   <li><b>开关开着但白名单为空 → 拒绝启动</b>。这种配置下 {@link VerifyProperties#isDemoModeActive()}
 *       本来就会把演示模式当作未开启（不会真的放开任何东西），但"想开演示却没写成白名单"几乎
 *       总是配置事故，与其静默无效果、让人以为演示模式坏了，不如在启动期就把话说明白；</li>
 *   <li><b>生效时每次启动都打一条醒目的 WARN</b>，点名哪些邮箱走演示通道。演示模式最危险的
 *       失败方式是"忘了关"，一条每次启动都出现的日志是发现它最便宜的手段。</li>
 * </ul>
 *
 * <p>刻意<b>不</b>在 prod profile 下拒绝该开关：项目要部署到公网演示机，而那台机器跑的就是
 * prod（{@code application-prod.yml} 里 {@code verify.mail-enabled: true}）。风险由白名单承担——
 * 未列入白名单的邮箱走的仍是完全不变的邮件通道。</p>
 */
@Slf4j
@Component
public class VerifyDemoGuard {

    public VerifyDemoGuard(VerifyProperties properties) {
        if (!properties.isDemoModeEnabled()) {
            return;
        }

        Set<String> demoEmails = properties.normalizedDemoEmails();
        if (demoEmails.isEmpty()) {
            throw new IllegalStateException(
                    "校园认证演示模式已开启（verify.demo-mode-enabled=true）但未配置任何演示邮箱："
                            + "该配置下演示模式不会生效，验证码仍会走邮件通道。"
                            + "请设置环境变量 VERIFY_DEMO_EMAILS（逗号分隔的邮箱白名单），"
                            + "或把 VERIFY_DEMO_MODE_ENABLED 置回 false。");
        }

        log.warn("校园认证演示模式已开启：{} 个白名单邮箱的验证码将随 /student/verify 响应返回，"
                        + "不再发送真实邮件（仅限演示环境，请勿在对外正式环境长期开启）。白名单: {}",
                demoEmails.size(), demoEmails);
    }
}
