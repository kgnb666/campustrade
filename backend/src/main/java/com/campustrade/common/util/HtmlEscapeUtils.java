package com.campustrade.common.util;

/**
 * HTML 输出转义工具 —— 用户产生的内容进入 HTML 上下文时的<b>唯一</b>转义点。
 *
 * <h2>为什么转义发生在输出侧，而不是输入侧</h2>
 * <p>此前的做法是在写入时做"黑名单清洗"（{@code replaceAll("<[^>]*>","").replace("script","")}），
 * 这种方案有两个无法修补的缺陷：</p>
 * <ol>
 *   <li><b>破坏正常文本</b>：{@code "javascript"} 会被改写成 {@code "java"}，
 *       {@code "<3 这本书"} 这类正常表达会被当成标签整段删除，且损坏不可逆（原文永久丢失）；</li>
 *   <li><b>覆盖不全</b>：黑名单永远列不全（{@code <ScRiPt>}、{@code <scr<script>ipt>}、
 *       事件属性 {@code onerror=} 等都能绕过），误以为"洗过了"反而放松了真正的出口防护。</li>
 * </ol>
 * <p>正确模型是：<b>输入保真存储，输出按上下文转义</b>。本类提供输出侧的转义与检测能力，
 * 与"存什么"完全解耦。</p>
 *
 * <h2>当前项目实际需要转义的地方</h2>
 * <p>当前唯一的展示端是 Flutter 客户端，正文经 {@code Text} 组件按纯文本渲染，
 * <b>不解析 HTML</b>，因此 API 的 JSON 负载保持原文（既安全，也不会把 {@code &} 之类的
 * 正常字符改成实体而让用户看到 {@code &amp;}）。一旦出现任何 HTML 展示端
 * （后台管理页、邮件模板、导出报表等），在该处调用 {@link #escape(String)} 即可。</p>
 */
public final class HtmlEscapeUtils {

    private HtmlEscapeUtils() {
    }

    /**
     * 对文本做 HTML 实体转义（{@code & < > " '}），使其可安全嵌入 HTML 文本节点与属性值。
     *
     * @param input 原始文本，可为 null
     * @return 转义后的文本；null 入参返回 null
     */
    public static String escape(String input) {
        if (input == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(input.length() + 16);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                case '\'':
                    sb.append("&#39;");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 检测文本中是否含有 HTML 标记迹象（{@code <...>} 形式的标签或未转义的实体起始符）。
     *
     * <p><b>只用于观测与审计</b>（例如"这条评价正文里出现了标签，请留意是否需要在 HTML 端转义"），
     * 绝不据此改写内容：检测不是防护，改写才是数据损坏的来源。</p>
     *
     * @param input 原始文本，可为 null
     * @return true = 看起来包含 HTML 标签
     */
    public static boolean containsHtmlMarkup(String input) {
        if (input == null || input.isEmpty()) {
            return false;
        }
        int open = input.indexOf('<');
        if (open < 0) {
            return false;
        }
        // 形如 <tag ...> 或 </tag> 才视为标记；单独的 "<"（如 "价格 <100"）不算
        int close = input.indexOf('>', open + 1);
        if (close < 0) {
            return false;
        }
        String inner = input.substring(open + 1, close).trim();
        if (inner.isEmpty()) {
            return false;
        }
        if (inner.charAt(0) == '/') {
            inner = inner.substring(1).trim();
        }
        if (inner.isEmpty()) {
            return false;
        }
        char first = inner.charAt(0);
        return Character.isLetter(first) || first == '!' || first == '?';
    }
}
