package com.campustrade.support;

import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.Configuration;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * 测试用 SQL 语句计数器：统计一次业务调用真正发往数据库的语句条数。
 *
 * <h2>用途</h2>
 * 阶段 7 需要"每请求 SQL 条数"这类无法用返回值断言的事实（典型场景是 N+1）。
 * 手工数日志行易受并发与日志级别干扰，因此在 MyBatis 上挂一个拦截器：
 * 每次语句准备（{@code StatementHandler#prepare}）计数一次，并记录 SQL 文本片段，
 * 测试即可断言"条数上界"（回归护栏）并打印明细（证据）。
 *
 * <h2>为什么拦 {@code StatementHandler#prepare} 而不是 {@code Executor#query}</h2>
 * MyBatis-Plus 的分页插件在插件链<b>内部</b>直接调用下层执行器执行 count 查询：
 * 该调用不经过链上更外层的 Executor 代理，因此拦 {@code Executor} 会漏掉"分页 count"这一条 SQL。
 * {@code StatementHandler} 由 {@code Configuration#newStatementHandler} 统一创建并套用所有插件，
 * 每条真正执行的语句（含分页 count）都会经过 {@code prepare}，计数与插件注册顺序无关，因此更可靠。
 *
 * <h2>为什么由测试手动安装、且不卸载</h2>
 * 拦截器挂在共享的 MyBatis {@code Configuration} 上（Spring 上下文缓存会在多个测试类之间复用同一份配置）。
 * 测试在 {@code @BeforeEach} 里安装（{@code contains} 幂等，且本类在测试中作为单实例复用），
 * 用例之间只做 {@link #reset()}，从而避免把"某次调用"的语句混进下一次统计。
 * MyBatis 的 {@code Configuration#getInterceptors()} 返回的是不可修改视图（{@code InterceptorChain} 内部
 * {@code Collections.unmodifiableList}），无法在测试结束时摘除；因此这里只保证"幂等安装"，
 * 计数器本身完全被动（不改变 SQL 与执行计划），对其它测试无影响。
 */
@Intercepts({
        @Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class})
})
public class SqlStatementCounter implements Interceptor {

    /** SQL 文本在明细里的最大长度（仅用于可读输出，不参与断言）。 */
    private static final int SQL_SNIPPET_LIMIT = 70;

    private final List<String> executedSqlSnippets = Collections.synchronizedList(new ArrayList<>());

    /** 安装到给定 Configuration（幂等）。 */
    public void install(Configuration configuration) {
        if (!configuration.getInterceptors().contains(this)) {
            configuration.addInterceptor(this);
        }
        reset();
    }

    /** 清零计数（每次测量前调用）。 */
    public void reset() {
        executedSqlSnippets.clear();
    }

    /** 已执行的 SQL 条数。 */
    public int count() {
        return executedSqlSnippets.size();
    }

    /** 已执行的 SQL 明细（按执行顺序，单行化 + 截断）。 */
    public List<String> executedSqlSnippets() {
        return new ArrayList<>(executedSqlSnippets);
    }

    /** 按"语句类型 + 表名"汇总的条数，形如 {@code select.goods=60  select."user"=61}。 */
    public String summary() {
        List<String> snippets = executedSqlSnippets();
        List<String> buckets = new ArrayList<>();
        for (String sql : snippets) {
            buckets.add(bucketOf(sql));
        }
        Collections.sort(buckets);
        StringBuilder sb = new StringBuilder();
        String previous = null;
        int n = 0;
        for (String bucket : buckets) {
            if (!bucket.equals(previous)) {
                if (previous != null) {
                    sb.append(previous).append('=').append(n).append("  ");
                }
                previous = bucket;
                n = 0;
            }
            n++;
        }
        if (previous != null) {
            sb.append(previous).append('=').append(n);
        }
        return sb.toString();
    }

    /** 语句类型 + 首个表名（{@code select ... from campus_trade.goods ...} -> {@code select.goods}）。 */
    private String bucketOf(String sql) {
        String lower = sql.replaceAll("\\s+", " ").toLowerCase();
        String verb = lower.startsWith("insert") ? "insert"
                : lower.startsWith("update") ? "update"
                        : lower.startsWith("delete") ? "delete" : "select";
        int idx = lower.indexOf(" from ");
        if (idx < 0) {
            return verb + "(?)";
        }
        String rest = lower.substring(idx + " from ".length());
        if (rest.startsWith("campus_trade.")) {
            rest = rest.substring("campus_trade.".length());
        }
        StringBuilder table = new StringBuilder();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == ' ' || c == '(' || c == ',' || c == '\n' || c == '\r' || c == ';') {
                break;
            }
            table.append(c);
        }
        return verb + "." + (table.length() == 0 ? "?" : table);
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        Object target = invocation.getTarget();
        if (target instanceof StatementHandler handler) {
            try {
                String sql = handler.getBoundSql().getSql();
                executedSqlSnippets.add(snippet(sql));
            } catch (Exception ignored) {
                // 拿不到 SQL 文本时仍要计数，避免统计失真
                executedSqlSnippets.add("(unavailable)");
            }
        }
        return invocation.proceed();
    }

    private String snippet(String sql) {
        String oneLine = sql.replaceAll("\\s+", " ").trim();
        return oneLine.length() > SQL_SNIPPET_LIMIT ? oneLine.substring(0, SQL_SNIPPET_LIMIT) + "..." : oneLine;
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {
        // 无配置项
    }
}
