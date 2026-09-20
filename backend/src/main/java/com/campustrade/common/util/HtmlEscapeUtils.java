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
 * <h2>当前策略：用户内容按原文存储与返回（唯一展示端是纯文本渲染）</h2>
 * <p>当前唯一的展示端是 Flutter 客户端，正文经 {@code Text} 组件按纯文本渲染，
 * <b>不解析 HTML</b>；后端没有任何 HTML 模板（{@code src/main/resources} 下不存在
 * {@code templates} 目录，也不存在 {@code static} 目录下的 .html 页面），因此 API 的 JSON 负载保持原文——
 * 既安全，也不会把 {@code &} 之类的正常字符改成实体而让用户看到 {@code &amp;}。</p>
 *
 * <h2>新增 HTML 展示端时必须做什么（强制）</h2>
 * <p>一旦出现任何 HTML 展示端（后台管理页、邮件模板、导出报表、静态回调页等），
 * <b>必须在输出点调用 {@link #escape(String)}</b>（HTML 文本节点与属性值都适用），
 * 或改用模板引擎的自动转义并显式标注"已转义"；该策略变更同时要更新 README「安全约定」章节。</p>
 *
 * <p>这条约束不是靠自觉：{@code BackendHtmlSurfaceGuardTests} 会在每次 {@code mvn test} 时扫描后端资源目录，
 * 一旦出现 {@code templates/} 或 HTML 类文件就<b>失败</b>，把"先落地转义再新增模板"推到提交者面前。</p>
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
