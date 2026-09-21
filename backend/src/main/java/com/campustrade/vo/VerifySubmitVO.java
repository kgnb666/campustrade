package com.campustrade.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 校园认证申请结果。
 *
 * <p><b>正常情况下这个对象根本不会出现在响应里</b>：{@code /student/verify} 成功时 {@code data} 恒为
 * {@code null}，因为验证码只能从校园邮箱（或本地开发的服务端日志）获取，绝不随响应返回。</p>
 *
 * <p>唯一例外是<b>演示模式</b>（{@code verify.demo-mode-enabled} 且邮箱命中 {@code verify.demo-emails}
 * 白名单）：那台环境没有真实学校邮箱可用，于是验证码随响应返回，由前端自动填入并标注"演示模式"。
 * 因此本 VO 的两个字段都只在演示通道下才有值，正常通道下整个对象为 {@code null}。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerifySubmitVO implements Serializable {

    /** 是否走了演示通道（前端据此决定是否显示"演示模式"提示） */
    private boolean demoMode;

    /** 演示通道下的 6 位验证码；正常通道为 {@code null} */
    private String demoCode;
}
