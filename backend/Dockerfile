# ==============================================================================
#  CampusTrade 后端生产镜像（多阶段构建）
#
#  构建（在 backend/ 目录下执行，Dockerfile 即本文件）：
#      docker build -t campustrade-backend:0.0.1 .
#  或由 docker-compose.prod.yml 的 `build:` 段自动完成。
#
#  设计要点：
#   1. 多阶段：构建阶段用 maven + JDK 21 产出可执行 jar，运行阶段只保留 JRE 21，
#      镜像里不含源码、不含 Maven、不含本机 ~/.m2；
#   2. 非 root：容器内以普通用户 campustrade(uid 10001) 运行，被攻破也拿不到 root；
#   3. 无内置凭据：镜像里没有任何 .env / 口令 / 密钥，全部由运行期环境变量注入
#      （prod profile 由 ProdSecretsGuard 做启动期 fail-fast 校验，缺失即拒绝启动）；
#   4. 健康检查：探活使用公开接口 GET /api/school/list（无需认证），
#      与 docker-compose.prod.yml 的 healthcheck / depends_on 配合；
#   5. 不使用 `latest`：基础镜像固定到具体版本号（补丁级固定），升级必须显式改本文件。
# ==============================================================================

# ------------------------------------------------------------------------------
# 阶段 1：编译打包
# ------------------------------------------------------------------------------
FROM maven:3.9.16-eclipse-temurin-21 AS build

WORKDIR /workspace

# 先只拷 pom.xml 再下载依赖：源码改动不会让依赖层缓存失效
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests dependency:go-offline || true

# 再拷源码并打包（跳过测试：CI/质量门禁已跑过 mvn -B test，镜像构建不再重复）
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests package \
    && cp target/campus-trade-backend-*.jar /workspace/app.jar

# ------------------------------------------------------------------------------
# 阶段 2：运行
# ------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine

# wget 来自 busybox，供 HEALTHCHECK 使用；tzdata 让日志/时间字段按东八区输出
RUN apk add --no-cache tzdata \
    && addgroup -g 10001 -S campustrade \
    && adduser -u 10001 -S -G campustrade -h /app campustrade

WORKDIR /app

COPY --from=build --chown=campustrade:campustrade /workspace/app.jar /app/app.jar

# 日志目录归普通用户所有（logback-spring.xml 默认写 ${LOG_DIR}/campustrade.log）
RUN mkdir -p /app/logs && chown -R campustrade:campustrade /app

USER campustrade

EXPOSE 8080

ENV TZ=Asia/Shanghai \
    LOG_DIR=/app/logs \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"

# 说明：这里刻意不设置 SPRING_PROFILES_ACTIVE —— profile 属于部署环境的运行期决策
# （docker-compose.prod.yml 会注入 prod），镜像本身保持可复用。
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/api/school/list || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
