package com.campustrade.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 令牌摘要工具类
 *
 * <p>用于在"不保存令牌明文"的前提下完成两件事：</p>
 * <ul>
 *   <li><b>服务端比对</b>：Redis 只保存 Refresh Token 的 SHA-256 十六进制摘要，
 *       即使缓存被读取也无法还原出可直接使用的令牌；</li>
 *   <li><b>安全日志</b>：日志中只打印摘要前 8 位（指纹），既可关联同一次请求，
 *       又不泄露令牌本身。</li>
 * </ul>
 *
 * <p>说明：SHA-256 未加盐是刻意的选择——这里的输入是签名 JWT（本身已含高熵随机载荷），
 * 不存在字典攻击面，加盐反而会让"按值查找"这一使用方式无法实现。</p>
 */
public final class TokenHashUtils {

    /** 日志指纹长度：摘要前 8 位十六进制字符足以在单次排查窗口内区分不同令牌。 */
    private static final int FINGERPRINT_LENGTH = 8;

    private TokenHashUtils() {
        // 工具类，禁止实例化
    }

    /**
     * 计算 SHA-256 摘要并以小写十六进制字符串返回。
     *
     * @param raw 原始文本（令牌等），允许为 null
     * @return 64 位十六进制摘要；入参为 null 时返回 null
     */
    public static String sha256Hex(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // JDK 必定内置 SHA-256，走到这里说明运行环境异常，直接抛出以免静默降级
            throw new IllegalStateException("当前 JDK 不支持 SHA-256 摘要算法", e);
        }
    }

    /**
     * 计算用于日志输出的短指纹（摘要前 8 位）。
     *
     * @param raw 原始文本（令牌等）
     * @return 8 位十六进制指纹；入参为空时返回 "empty"
     */
    public static String fingerprint(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "empty";
        }
        String hex = sha256Hex(raw);
        return hex.substring(0, FINGERPRINT_LENGTH);
    }
}
