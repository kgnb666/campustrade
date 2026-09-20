package com.campustrade.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;
import org.springframework.core.env.PropertySource;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 测试基础设施装配：用 Testcontainers 为每个测试 JVM 拉起 PostgreSQL / Redis / MinIO 一次性容器。
 *
 * <h2>为什么需要它</h2>
 * 测试原先直连开发环境的中间件（127.0.0.1:5432 的 campustrade 库、6379 的 Redis、9000 的 MinIO），
 * 导致两个问题：跑测试会污染开发库；在没有这些服务的机器（或 CI）上测试根本跑不起来。
 * 本类让测试自带中间件：只要机器上有 Docker，`mvn test` 即可重复通过，且完全不触碰开发库。
 *
 * <h2>为什么容器只启动一次（static 单例 + destroyMethod = ""）</h2>
 * <ul>
 *   <li>三个容器是 {@code static final} 单例，并在整个测试 JVM 内复用：Spring TestContext 框架会缓存
 *       应用上下文，但一旦出现第二个上下文（不同注解组合、上下文被驱逐等），容器若随之重启，
 *       既浪费时间，也会让"一次性干净库"的语义变得不可预测。</li>
 *   <li>容器由本类自己管理，不由 Spring 容器管理：{@code @Bean(destroyMethod = "")} 明确禁止 Spring
 *       在上下文关闭时调用容器自身的 {@code close()}/{@code stop()}（GenericContainer 实现了
 *       AutoCloseable，默认会被推断为销毁方法）。容器生命周期交由 Testcontainers 的 Ryuk 资源回收器
 *       在测试 JVM 退出时统一清理。</li>
 *   <li>因此容器只在"首次被需要"时启动一次（{@link #startOnce} 幂等），后续上下文直接复用实例。</li>
 * </ul>
 *
 * <h2>迁移脚本无需改动</h2>
 * {@code db/migration/V1..V9} 的 SQL 里硬编码了 {@code campus_trade.} schema 前缀。容器内的
 * PostgreSQL 是全新的空实例，Flyway（主配置 {@code spring.flyway.schemas=campus_trade}）会在其中
 * 正常创建 {@code campus_trade} schema 并建表，因此迁移脚本保持原样即可。
 *
 * <h2>三类中间件的注入方式</h2>
 * <ul>
 *   <li><b>PostgreSQL</b>：{@code @ServiceConnection} 自动装配（Boot 内置
 *       {@code JdbcContainerConnectionDetailsFactory}），并把 {@code currentSchema=campus_trade}
 *       作为 URL 参数带上——{@code CampusTradeApplicationTests} 断言 {@code current_schema()}
 *       必须是 {@code campus_trade}，与开发库的 URL 形态保持一致。容器启动时还会先执行
 *       {@code testcontainers-postgres-init.sql} 预建 schema（原因见该脚本注释）。</li>
 *   <li><b>Redis</b>：{@code GenericContainer("redis:7")} + {@code @ServiceConnection(name = "redis")}
 *       （Boot 内置 {@code RedisContainerConnectionDetailsFactory}）。名字必须显式写成 "redis"，
 *       原因见 {@link #REDIS} 的注释。</li>
 *   <li><b>MinIO</b>：Boot 没有内置的 MinIO service connection，改为动态注入
 *       {@code minio.endpoint / access-key / secret-key / url-prefix} 四个属性
 *       （见 {@link MinioPropertySource}）。</li>
 * </ul>
 *
 * <h2>为什么 MinIO 用 ContextCustomizerFactory 而不是 DynamicPropertyRegistrar</h2>
 * {@code org.springframework.test.context.DynamicPropertyRegistrar} 是 Spring Framework 6.2 才引入的
 * （本工程是 Boot 3.3.4 → Spring Framework 6.1.13，该接口不存在），而 {@code @DynamicPropertySource}
 * 只能声明在测试类自身（本任务不允许改动 20 个测试类）。因此这里用测试框架的等价扩展点：
 * 通过 {@code META-INF/spring.factories} 注册一个 {@link ContextCustomizerFactory}，在上下文
 * refresh 之前把一个 {@link PropertySource} 放到环境最前面——这正是 Spring 自身实现
 * {@code @DynamicPropertySource} 的机制，且对所有 {@code @SpringBootTest} 自动生效。
 */
@Configuration(proxyBeanMethods = false)
@Profile("test")
public class TestContainersConfig {

    /** Redis 与 MinIO 的容器内监听端口（非环境配置，仅容器内部约定）。 */
    private static final int REDIS_PORT = 6379;
    private static final int MINIO_PORT = 9000;

    /**
     * 测试用一次性容器的凭据：运行时随机生成，源码中不固化任何可用凭据
     * （容器用完即被 Ryuk 回收，随机值也不会影响其它环境）。
     */
    private static final String MINIO_ACCESS_KEY = "test-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String MINIO_SECRET_KEY = UUID.randomUUID().toString().replace("-", "");
    private static final String POSTGRES_PASSWORD = UUID.randomUUID().toString().replace("-", "");

    /** 与 application-test.yml 中的 minio.bucket-name 默认值保持一致。 */
    private static final String DEFAULT_MINIO_BUCKET = "campustrade-test";

    /** 预建 campus_trade schema 的初始化脚本（test classpath 根目录）。 */
    private static final String POSTGRES_INIT_SCRIPT = "testcontainers-postgres-init.sql";

    /**
     * PostgreSQL 16：库名/账号与开发环境同名同规格，避免测试代码任何隐式假设。
     * {@code currentSchema} 让连接的 search_path 落在 campus_trade（与开发 URL 一致）。
     * {@code withInitScript} 在容器启动时就预建 campus_trade schema——原因见该脚本内的注释：
     * 若让 Flyway 自己建 schema，迁移历史里会多出一条 version 为 NULL 的记录，破坏现有断言。
     */
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
                    .withDatabaseName("campustrade")
                    .withUsername("campustrade")
                    .withPassword(POSTGRES_PASSWORD)
                    .withUrlParam("currentSchema", "campus_trade")
                    .withInitScript(POSTGRES_INIT_SCRIPT);

    /**
     * Redis 7。用 {@code GenericContainer} 而不是 {@code RedisContainer}：本工程用的
     * Testcontainers 1.19.8 里还没有 {@code RedisContainer} 这个类（Boot 3.3.4 的
     * {@code RedisContainerConnectionDetailsFactory} 也因此直接声明为 {@code Container<?>}）。
     * 由于该工厂带固定的连接名 "redis"，{@code ContainerConnectionSource.accepts} 会要求
     * {@code @ServiceConnection(name = "redis")}，否则不匹配（表现为 Redis 配置回落开发环境）。
     * 另外必须显式暴露 6379：连接信息取的是 {@code getFirstMappedPort()}。
     */
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7"))
                    .withExposedPorts(REDIS_PORT);

    /**
     * MinIO：官方镜像以 {@code server /data} 启动，需显式给出 root 账号环境变量；
     * 就绪探针使用 MinIO 自带的存活检查端点 /minio/health/live。
     */
    private static final GenericContainer<?> MINIO =
            new GenericContainer<>(DockerImageName.parse("minio/minio:latest"))
                    .withCommand("server /data")
                    .withEnv("MINIO_ROOT_USER", MINIO_ACCESS_KEY)
                    .withEnv("MINIO_ROOT_PASSWORD", MINIO_SECRET_KEY)
                    .withExposedPorts(MINIO_PORT)
                    .waitingFor(Wait.forHttp("/minio/health/live")
                            .forPort(MINIO_PORT)
                            .withStartupTimeout(Duration.ofMinutes(2)));

    /**
     * PostgreSQL 连接信息（@ServiceConnection 自动装配 spring.datasource.*）。
     * {@code destroyMethod = ""}：容器由本类统一管理，不接受上下文的销毁回调。
     */
    @Bean(destroyMethod = "")
    @ServiceConnection
    public PostgreSQLContainer<?> postgresContainer() {
        return startOnce(POSTGRES);
    }

    /** Redis 连接信息（@ServiceConnection(name = "redis") 自动装配 spring.data.redis.*）。 */
    @Bean(destroyMethod = "")
    @ServiceConnection(name = "redis")
    public GenericContainer<?> redisContainer() {
        return startOnce(REDIS);
    }

    /**
     * MinIO 容器。属性注入不走 @ServiceConnection（Boot 无内置支持），
     * 由 {@link MinioContextCustomizerFactory} 注册的动态属性源提供；此处仅把实例注册为 Bean，
     * 让容器在上下文中有个明确的归属与日志锚点。
     */
    @Bean(destroyMethod = "")
    public GenericContainer<?> minioContainer() {
        return startOnce(MINIO);
    }

    /**
     * 幂等启动：只有第一次调用真正拉起容器，之后（同一 JVM 内的任意上下文）直接复用。
     */
    static <T extends GenericContainer<?>> T startOnce(T container) {
        if (!container.isRunning()) {
            container.start();
        }
        return container;
    }

    /** MinIO 对外端点，形如 {@code http://localhost:49123}（端口由 Docker 随机映射）。 */
    static String minioEndpoint() {
        GenericContainer<?> minio = startOnce(MINIO);
        return "http://" + minio.getHost() + ":" + minio.getMappedPort(MINIO_PORT);
    }

    /**
     * 把所有测试上下文都接上 MinIO 动态属性的 {@link ContextCustomizerFactory} 实现。
     *
     * <p>它返回的是同一个 {@code static final} 单例 customizer：ContextCustomizer 的 equals 会影响
     * Spring 的上下文缓存键，若每个测试类拿到不同实例，20 个测试类就会各自建一遍上下文（变慢），
     * 因此必须复用同一实例。</p>
     */
    public static class MinioContextCustomizerFactory implements ContextCustomizerFactory {

        private static final ContextCustomizer CUSTOMIZER = (context, mergedConfig) -> {
            ConfigurableEnvironment environment = context.getEnvironment();
            // 与 @Profile("test") 保持一致：只在 test profile 下改写 MinIO 目标
            if (environment.acceptsProfiles(Profiles.of("test"))) {
                environment.getPropertySources()
                        .addFirst(new MinioPropertySource(environment));
            }
        };

        @Override
        public ContextCustomizer createContextCustomizer(Class<?> testClass,
                                                         List<ContextConfigurationAttributes> configAttributes) {
            return CUSTOMIZER;
        }
    }

    /**
     * MinIO 动态属性源：值与容器映射端口绑定，因此<b>延迟解析</b>（每次 getProperty 时计算）。
     *
     * <p>延迟还有一个必要原因：{@code minio.url-prefix} 需要用到 {@code minio.bucket-name}，
     * 而桶名来自 application-test.yml；在 customizer 执行的那一刻（refresh 之前）读取环境属性
     * 才能拿到最终生效值，这里通过持有 environment 在解析时再取值来避免时序问题。</p>
     */
    private static final class MinioPropertySource extends PropertySource<Object> {

        private static final String PROPERTY_SOURCE_NAME = "testcontainers-minio";

        private final ConfigurableEnvironment environment;

        private MinioPropertySource(ConfigurableEnvironment environment) {
            super(PROPERTY_SOURCE_NAME);
            this.environment = environment;
        }

        @Override
        public Object getProperty(String name) {
            return switch (name) {
                case "minio.endpoint" -> minioEndpoint();
                case "minio.access-key" -> MINIO_ACCESS_KEY;
                case "minio.secret-key" -> MINIO_SECRET_KEY;
                case "minio.url-prefix" -> minioEndpoint() + "/"
                        + environment.getProperty("minio.bucket-name", DEFAULT_MINIO_BUCKET);
                default -> null;
            };
        }
    }
}
