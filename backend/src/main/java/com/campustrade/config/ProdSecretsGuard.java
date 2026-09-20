package com.campustrade.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 生产环境敏感配置的启动期守卫（fail-fast）。
 *
 * <h2>为什么需要它</h2>
 * 阶段 8 之前，{@code application.yml} 里的数据库口令、MinIO 凭据等都有可用的开发默认值
 * （{@code ${SPRING_DATASOURCE_PASSWORD:...}}）。这在本地很方便，对生产却是最危险的形状：
 * 漏配一个环境变量不会报错，服务会带着"开发默认口令 / 本机地址"正常起来并对外服务——
 * 直到有人发现生产库用的是开发口令，或者图片地址指向 127.0.0.1。
 *
 * <p>{@code application-prod.yml} 已把这些项改为空默认值（{@code ${ENV:}}），本类负责把
 * "空值"翻译成"拒绝启动 + 可照做的中文提示"。与另外两处 fail-fast 一起构成生产启动校验：</p>
 * <ul>
 *   <li>JWT 密钥：{@link com.campustrade.security.JwtTokenProvider#init()}（缺失 / 短于 32 字节 /
 *       命中历史默认密钥）；</li>
 *   <li>SMTP 通道：{@link VerifyMailProdGuard}（mail-enabled 必须为 true 且 MAIL_* 齐备）；</li>
 *   <li>本文：连接目标与凭据（数据库 / Redis / MinIO）、CORS 白名单。</li>
 * </ul>
 *
 * <h2>为什么实现 EnvironmentPostProcessor（而不是一个普通 @Component）</h2>
 * 校验必须发生在<b>任何 Bean 创建之前</b>。曾经把它写成 {@code @Profile("prod")} 的普通组件，
 * 实测漏配 {@code SPRING_DATASOURCE_HOST} 时先失败的是 Flyway，运维看到的是一行
 * {@code Driver org.postgresql.Driver claims to not accept jdbcUrl, jdbc:postgresql://:/?...}——
 * 真正的原因（少了一个环境变量）被埋在 JDBC 报错里。{@code EnvironmentPostProcessor} 在
 * "环境准备好、上下文刷新之前"执行，是 Spring Boot 为这类校验提供的标准时机，
 * 因此提示信息总是由本类先给出。
 *
 * <p>注册方式：{@code src/main/resources/META-INF/spring.factories}。
 * 非 prod profile（含测试的 test profile）下本类立即返回，不做任何校验。</p>
 */
public class ProdSecretsGuard implements EnvironmentPostProcessor, Ordered {

    /**
     * 占位符前缀：.env.example 里所有需要人工填写的值都以 CHANGE_ME 开头。
     * 生产环境仍带着它 = 直接照抄了模板，必须拒绝启动（"复制模板上线"的典型事故形状）。
     */
    private static final String PLACEHOLDER_PREFIX = "change_me";

    /**
     * 生产必须由部署平台注入的环境变量（key = 环境变量名，value = 中文用途说明）。
     * 只列出"没有它服务就无法正确对外工作"的项，可选调优项不在其中。
     */
    private static final Map<String, String> REQUIRED_ENV_VARS = new LinkedHashMap<>();

    static {
        REQUIRED_ENV_VARS.put("SPRING_DATASOURCE_HOST", "PostgreSQL 主机");
        REQUIRED_ENV_VARS.put("SPRING_DATASOURCE_PORT", "PostgreSQL 端口");
        REQUIRED_ENV_VARS.put("SPRING_DATASOURCE_DATABASE", "PostgreSQL 库名");
        REQUIRED_ENV_VARS.put("SPRING_DATASOURCE_USERNAME", "PostgreSQL 用户");
        REQUIRED_ENV_VARS.put("SPRING_DATASOURCE_PASSWORD", "PostgreSQL 口令");
        REQUIRED_ENV_VARS.put("SPRING_DATA_REDIS_HOST", "Redis 主机");
        REQUIRED_ENV_VARS.put("SPRING_DATA_REDIS_PORT", "Redis 端口");
        REQUIRED_ENV_VARS.put("SPRING_DATA_REDIS_PASSWORD", "Redis 口令");
        REQUIRED_ENV_VARS.put("MINIO_ENDPOINT", "MinIO 服务端点");
        REQUIRED_ENV_VARS.put("MINIO_ROOT_USER", "MinIO Access Key");
        REQUIRED_ENV_VARS.put("MINIO_ROOT_PASSWORD", "MinIO Secret Key");
        REQUIRED_ENV_VARS.put("MINIO_BUCKET_NAME", "MinIO 桶名");
        REQUIRED_ENV_VARS.put("MINIO_URL_PREFIX", "MinIO 对外图片前缀，必须是对外可访问的真实地址");
        REQUIRED_ENV_VARS.put("CORS_ALLOWED_ORIGINS", "CORS 允许来源，填真实域名清单");
    }

    /**
     * 顺序取最低优先级：必须等到配置数据（application.yml / application-prod.yml / 环境变量）
     * 全部加载完毕之后再校验，否则会读到一个"还没被覆盖"的中间状态。
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) {
            return; // 只在生产 profile 下校验
        }
        validate(environment);
    }

    private void validate(ConfigurableEnvironment environment) {
        List<String> missing = new ArrayList<>();
        List<String> placeholders = new ArrayList<>();

        for (Map.Entry<String, String> required : REQUIRED_ENV_VARS.entrySet()) {
            String name = required.getKey();
            String value = environment.getProperty(name);
            if (value == null || value.isBlank()) {
                missing.add(name + "（" + required.getValue() + "）");
            } else if (isPlaceholder(value)) {
                placeholders.add(name);
            }
        }

        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "生产环境敏感配置缺失：prod 拒绝启动。以下环境变量必须由部署平台注入，"
                            + "application-prod.yml 中已刻意不提供任何可用默认值：\n  - "
                            + String.join("\n  - ", missing)
                            + "\n提示：请对照 .env.example 的变量清单逐个补齐（.env 只用于本地开发，"
                            + "生产不要使用 CHANGE_ME 占位值或开发口令）。");
        }

        if (!placeholders.isEmpty()) {
            throw new IllegalStateException(
                    "生产环境仍在使用 .env.example 的占位值：prod 拒绝启动。以下环境变量的值以 "
                            + "CHANGE_ME 开头，说明它们是照抄模板后未替换的占位符：\n  - "
                            + String.join("\n  - ", placeholders)
                            + "\n请为生产环境生成独立凭据后重新启动（例如 openssl rand -base64 24）。");
        }

        // 已知的开发/示例口令：即使调用方给的是"非空、非 CHANGE_ME"的值，也必须拒绝。
        // 背景：仓库根目录存在开发用 .env，而 docker compose 会默认读取它 ——
        // 若生产直接用 `docker compose -f docker-compose.prod.yml up -d` 而未显式 --env-file，
        // 开发口令（与本地库/缓存/对象存储一致）会被当成生产凭据使用。
        List<String> knownDevValues = new ArrayList<>();
        for (Map.Entry<String, String> required : REQUIRED_ENV_VARS.entrySet()) {
            String name = required.getKey();
            if (!name.contains("PASSWORD") && !name.contains("SECRET") && !name.contains("USER")) {
                continue;
            }
            String value = environment.getProperty(name);
            if (value != null && KNOWN_DEV_SECRET_VALUES.contains(value.trim().toLowerCase(Locale.ROOT))) {
                knownDevValues.add(name);
            }
        }
        if (!knownDevValues.isEmpty()) {
            throw new IllegalStateException(
                    "生产环境正在使用开发环境的已知口令：prod 拒绝启动。以下环境变量的值与仓库中的"
                            + "开发配置（.env / .env.example 历史值 / docker-compose.yml 默认值）相同：\n  - "
                            + String.join("\n  - ", knownDevValues)
                            + "\n最常见的原因是直接执行了 docker compose -f docker-compose.prod.yml up -d "
                            + "而没有指定 --env-file，compose 于是读取了仓库根的开发 .env。"
                            + "\n请用 --env-file /etc/campustrade/prod.env 指定生产凭据后重新启动。");
        }

        String corsOrigins = environment.getProperty("CORS_ALLOWED_ORIGINS", "");
        if (corsOrigins.contains("*")) {
            throw new IllegalStateException(
                    "生产环境 CORS 来源包含通配符 \"*\"：prod 拒绝启动。"
                            + "allowCredentials 恒为 true，通配符等于允许任意站点携带用户凭据访问本服务。"
                            + "请在 CORS_ALLOWED_ORIGINS 中列出真实域名，例如 https://app.example.com");
        }
    }

    /**
     * 仓库中已经出现过的开发凭据值（小写比较）。它们可以被公开检索到，因此等同于公开口令。
     * 只做"拒绝"不做替换：生产凭据必须由部署平台生成。
     */
    private static final Set<String> KNOWN_DEV_SECRET_VALUES = Set.of(
            "campustrade123",
            "campustrade-secret",
            "campustrade-test",
            "password",
            "123456",
            "admin"
    );

    /** 占位符判定：CHANGE_ME_* / CHANGE_ME-* 等一律视为"未填写"。 */
    private static boolean isPlaceholder(String value) {
        return value.trim().toLowerCase(Locale.ROOT).startsWith(PLACEHOLDER_PREFIX);
    }
}
