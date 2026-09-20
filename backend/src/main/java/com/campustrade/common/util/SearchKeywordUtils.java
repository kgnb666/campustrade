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

    /**
     * LIKE 模式中的转义字符（配合 SQL 的 {@code ESCAPE '\'} 使用）。
     *
     * <p>选反斜杠作为转义符与 PostgreSQL 的默认行为一致：{@code standard_conforming_strings = on} 时
     * 字符串字面量里的反斜杠就是普通字符，因此 {@code ESCAPE '\'} 声明的转义符就是单个反斜杠。</p>
     */
    public static final char LIKE_ESCAPE_CHAR = '\\';

    /**
     * 把用户关键词转换为"只做字面匹配"的 LIKE 模式串。
     *
     * <h2>为什么必须转义</h2>
     * SQL 的 LIKE 把 {@code %}（任意长度任意字符）与 {@code _}（任意单个字符）当作元字符。
     * 用户输入关键词后若直接拼成 {@code "%keyword%"}，那么用户输入 {@code %} 会得到 {@code "%%%"}（匹配任意字符串 => 命中全表），
     * 输入 {@code _} 会得到 {@code "%_%"}（匹配任意非空字符串 => 同样命中全表）。
     * 这既是"搜索结果明显错误"的体验问题，也让本来能走索引的查询退化为全表扫描。
     * 因此这里把 {@code \}、{@code %}、{@code _} 逐个加上转义符，再交给
     * {@code LIKE ? ESCAPE '\'}（参数仍是预编译占位符，不引入任何 SQL 拼接）。
     *
     * <p>顺序很重要：先转义反斜杠本身，否则后续为 {@code %} / {@code _} 加上的转义符会被二次转义。</p>
     *
     * @param keyword 已标准化的关键词（{@link #normalize(String)} 的返回值），可为 null
     * @return 可直接嵌入 {@code %...%} 的模式串；关键词为空时返回 null（调用方据此跳过该条件）
     */
    public static String escapeLikePattern(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return null;
        }
        StringBuilder sb = new StringBuilder(keyword.length() + 8);
        for (int i = 0; i < keyword.length(); i++) {
            char c = keyword.charAt(i);
            if (c == LIKE_ESCAPE_CHAR || c == '%' || c == '_') {
                sb.append(LIKE_ESCAPE_CHAR);
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
