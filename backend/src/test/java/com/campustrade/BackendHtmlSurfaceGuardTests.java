package com.campustrade;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "后端不存在 HTML 展示端"守护测试 —— 让 {@link com.campustrade.common.util.HtmlEscapeUtils} 的
 * 转义策略从一句注释变成一条会失败的约束。
 *
 * <h2>它守护什么</h2>
 * <p>当前项目的用户内容（商品描述、评价正文、举报原因等）<b>按原文存库、按原文出现在 JSON 负载里</b>，
 * 原因是唯一的展示端是 Flutter 客户端，正文经 {@code Text} 组件按纯文本渲染、<b>不解析 HTML</b>。
 * 在这个前提下"输出侧转义"没有出口：提前转义只会把 {@code &} 变成 {@code &amp;} 让用户看到实体字符，
 * 属于把可逆问题变成不可逆的数据损坏。</p>
 *
 * <p>但这个结论<b>依赖一条事实</b>：后端没有 HTML 展示端。事实一旦改变（有人加了
 * {@code src/main/resources/templates/} 下的 Thymeleaf 页、或 {@code static/} 下的 .html 管理页/回调页），
 * 上面那套"原文存储"的推理立刻失效——用户内容会被当成 HTML 解析，XSS 随之成立。</p>
 *
 * <h2>为什么用测试而不是代码注释</h2>
 * <p>注释不会阻止任何人新增模板。本测试在每次 {@code mvn test} 时扫描后端资源目录，
 * 一旦出现 HTML 模板/页面就<b>失败</b>，把"必须先在输出点调用
 * {@code HtmlEscapeUtils.escape(...)}（或改用其它安全的渲染方式）"这件事推到新增模板的人面前，
 * 而不是留给一次事后安全评审。</p>
 *
 * <h2>失败时怎么办（而不是删掉这个测试）</h2>
 * <ol>
 *   <li>在真正输出用户内容的那一处调用 {@code HtmlEscapeUtils.escape(text)}
 *       （HTML 文本节点与属性值都适用），或使用模板引擎的自动转义并把该处显式标为"已转义"；</li>
 *   <li>同步更新 README「安全约定」章节与本类的断言，
 *       让"已存在 HTML 展示端，且转义点已落地"成为新的、同样是可验证的事实。</li>
 * </ol>
 */
public class BackendHtmlSurfaceGuardTests {

    /**
     * 视为"HTML 展示端"的扩展名：模板/页面/片段。
     *
     * <p>刻意不包含 {@code .xml}：MyBatis 的 {@code mapper/*.xml} 不是展示端；
     * 也刻意不包含 {@code .js}/{@code .css}——它们自身不会渲染用户内容。</p>
     */
    private static final List<String> HTML_LIKE_EXTENSIONS =
            List.of(".html", ".htm", ".ftl", ".vm", ".mustache", ".jsp", ".jspx", ".thymeleaf");

    /** 约定俗成的模板根目录名（Spring Boot 默认 {@code spring.thymeleaf.prefix=classpath:/templates/}）。 */
    private static final String TEMPLATES_DIR = "templates";

    @Test
    @DisplayName("后端资源目录不得出现 HTML 模板/页面：出现即说明转义策略必须重新评审")
    void backendHasNoHtmlTemplates() throws IOException {
        Path resourcesRoot = resolveResourcesRoot();

        // 用 SortedSet 去重：一个 templates/ 下的 .html 会同时命中下面两条扫描规则
        List<String> offenders = new ArrayList<>();
        Set<String> uniqueOffenders = new TreeSet<>();

        // 1) 模板根目录（templates/ 下任何文件都算，模板引擎的扩展名不固定，不能只看后缀）
        Path templatesDir = resourcesRoot.resolve(TEMPLATES_DIR);
        if (Files.isDirectory(templatesDir)) {
            try (Stream<Path> walk = Files.walk(templatesDir)) {
                walk.filter(Files::isRegularFile)
                        .forEach(p -> uniqueOffenders.add(
                                resourcesRoot.relativize(p).toString().replace('\\', '/')));
            }
        }

        // 2) 资源目录下任何位置的 HTML 类文件（覆盖 static/**/*.html 这类静态页/回调页）
        try (Stream<Path> walk = Files.walk(resourcesRoot)) {
            walk.filter(Files::isRegularFile)
                    .filter(BackendHtmlSurfaceGuardTests::looksLikeHtml)
                    .forEach(p -> uniqueOffenders.add(
                            resourcesRoot.relativize(p).toString().replace('\\', '/')));
        }

        offenders.addAll(uniqueOffenders);
        offenders.sort(Comparator.naturalOrder());

        assertTrue(offenders.isEmpty(),
                "在后端资源目录里发现了 HTML 展示端：" + offenders
                        + "。当前后端刻意不产出 HTML——用户内容按原文存取，唯一展示端是 Flutter 的纯文本渲染。"
                        + "新增 HTML 展示端前，必须先在输出点调用 HtmlEscapeUtils.escape(...)"
                        + "（或使用模板引擎的自动转义），再更新 README「安全约定」章节与本测试的断言；"
                        + "直接放行会让商品描述/评价正文里的标记被当作 HTML 解析。");
    }

    /**
     * 定位 {@code src/main/resources}。
     *
     * <p>{@code mvn test} 的工作目录是模块根目录（{@code backend/}）；在 IDE 里把工作目录设成仓库根时
     * 也能命中 {@code backend/src/main/resources}。两者都找不到时直接让用例失败：
     * 这条守护测试一旦"因为找不到目录而静默通过"，就等于不存在。</p>
     */
    private static Path resolveResourcesRoot() {
        List<Path> candidates = List.of(
                Paths.get("src", "main", "resources"),
                Paths.get("backend", "src", "main", "resources")
        );
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException(
                "找不到后端资源目录（已尝试: " + candidates + "，当前工作目录: "
                        + Paths.get("").toAbsolutePath() + "）。请在 backend 模块目录下执行 mvn test，"
                        + "或在 IDE 里把工作目录设为模块根目录——本守护测试不允许以'路径找不到'的方式通过。");
    }

    private static boolean looksLikeHtml(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String extension : HTML_LIKE_EXTENSIONS) {
            if (name.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }
}
