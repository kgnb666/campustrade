package com.campustrade.support;

import java.util.UUID;

/**
 * 测试凭据工具类。
 *
 * <p>提供测试所需的随机口令生成能力，避免在测试源码中固化任何可用口令字面量，
 * 从而消除硬编码凭据带来的安全扫描告警与凭据泄漏风险。</p>
 *
 * <p>注意：同一测试用例中"注册时设置的口令"与"随后登录使用的口令"必须复用
 * 同一个常量（只调用一次 {@link #randomPassword()}），否则两次生成的值不同会导致登录失败。</p>
 */
public final class TestCredentials {

    private TestCredentials() {
        // 工具类，禁止实例化
    }

    /**
     * 生成符合平台口令规则（长度 ≥ 8 且 ≤ 50，且同时包含字母与数字）的随机测试口令。
     *
     * <p>每次运行随机生成，源码中不固化任何可用口令。生成结果形如
     * {@code "Tt" + 12 位十六进制随机串 + "9"}，长度为 15，必定包含字母与数字。</p>
     *
     * @return 随机测试口令，每次调用返回不同值
     */
    public static String randomPassword() {
        return "Tt" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "9";
    }
}
