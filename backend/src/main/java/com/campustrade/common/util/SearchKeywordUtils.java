package com.campustrade.common.util;

import org.springframework.util.StringUtils;

/**
 * 搜索关键词标准化与清洗工具类
 */
public final class SearchKeywordUtils {

    /**
     * 搜索关键词最大长度限制 (对齐数据库 VARCHAR(100) 约束)
     */
    public static final int MAX_KEYWORD_LENGTH = 100;

    /**
     * 匹配包括全角空格、不可见 Unicode 字符及所有空白字符的正则表达式
     * \s: 标准空白符 (\t, \n, \f, \r, 空格)
     * \u3000: 中文全角空格 (IDEOGRAPHIC SPACE)
     * \u00A0: 不间断空格 (NO-BREAK SPACE)
     * \u200B: 零宽空格 (ZERO WIDTH SPACE)
     * \uFEFF: 零宽无间断空格 (ZERO WIDTH NO-BREAK SPACE / BOM)
     */
    private static final String WHITESPACE_REGEX = "[\\s\\u3000\\u00A0\\u200B\\uFEFF]+";

    private SearchKeywordUtils() {
        // 私有构造函数，防止实例化
    }

    /**
     * 对搜索关键词进行严格标准化清洗：
     * 1. 空引用过滤 -> 返回 null
     * 2. 多重连续空白 (含全角/零宽字符) 折叠为单个标准空格
     * 3. 去除首尾空白
     * 4. 纯空白或空串过滤 -> 返回 null
     * 5. 长度严格收敛至最大 100 字符 (截断并再次 trim)
     * 6. 保留原词大小写与中英排版 (维持 iPhone、iPad、MacBook 等品牌词展示体验)
     *
     * @param rawKeyword 原始用户输入
     * @return 清洗规范化后的关键词，若无意义则返回 null
     */
    public static String normalize(String rawKeyword) {
        if (!StringUtils.hasText(rawKeyword)) {
            return null;
        }

        // 折叠各种空白与不可见字符为单个标准半角空格
        String cleaned = rawKeyword.replaceAll(WHITESPACE_REGEX, " ").trim();
        if (cleaned.isEmpty()) {
            return null;
        }

        // 截断超长部分
        if (cleaned.length() > MAX_KEYWORD_LENGTH) {
            cleaned = cleaned.substring(0, MAX_KEYWORD_LENGTH).trim();
        }

        return cleaned.isEmpty() ? null : cleaned;
    }

    /**
     * 判断给定关键词是否具备有效搜索价值
     *
     * @param rawKeyword 待检测关键词
     * @return true 若标准化后为非空有效词
     */
    public static boolean isSearchable(String rawKeyword) {
        return normalize(rawKeyword) != null;
    }
}
