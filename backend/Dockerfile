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
#   4b. 端口 8081（容器内与宿主机一致，避免"容器内 8080 / 宿主机 8081"两套心智模型）：
#      本机开发端口由项目根目录 .env 的 BACKEND_PORT 统一配置；生产由
#      docker-compose.prod.yml 注入 SERVER_PORT（本文件的 ENV 也给了同一个默认值），
#      Spring Boot 侧读的是 application.yml 的 server.port: ${SERVER_PORT:8081}。
#      EXPOSE 与下面的 HEALTHCHECK 探针必须与被注入的端口保持一致。
#   5. 不使用 `latest`：基础镜像固定到具体版本号（补丁级固定），升级必须显式改本文件。
#      运行阶段镜像固定为 `eclipse-temurin:21.0.12_8-jre-alpine-3.24`（此前是浮动的
#      `21-jre-alpine`）：
#        - 版本号来源：Docker Hub 官方镜像页 https://hub.docker.com/_/eclipse-temurin 的
#          Tags 页（或用命令 `docker buildx imagetools inspect eclipse-temurin:21-jre-alpine`
#          查"浮动 tag 当下指向哪个具体版本"）；也可在本地用 `docker images` 核对已拉取的版本。
#        - tag 结构：`<JDK 补丁>_<build>-jre-alpine-<Alpine 小版本>`，即 JDK 补丁号与
#          Alpine 小版本**都**固定，避免上游发布新补丁时"昨天还好、今天构建结果就变了"。
#        - 该 tag 当前的 manifest list digest：
#          sha256:1a29e1fe337eb28b5bec30f0ee8ed29f0ff80ab6f75dcf9313efe82911065a52
#          （digest 是比 tag 更硬的锚；如何记录与固化 digest 见 README"生产部署步骤"一节。)
#        - 构建阶段 `maven:3.9.16-eclipse-temurin-21` 已固定在 Maven 补丁版本 3.9.16，
#          故此处保持不变。
# ==============================================================================

# ------------------------------------------------------------------------------
# 阶段 1：编译打包
# ------------------------------------------------------------------------------
FROM maven:3.9.16-eclipse-temurin-21 AS build

WORKDIR /workspace

# 先只拷 pom.xml 再下载依赖：源码改动不会让依赖层缓存失效。
COPY pom.xml ./

# 这里刻意**不加** `|| true`：那会把"依赖解析失败"静默吞掉，让构建在缓存层"假装成功"，
# 真正的问题推迟到下面的 package 阶段才爆出来（错误信息离根因很远）。
# 若某天 mvn 3.9.16 的 dependency:go-offline 确实无法离线解析某个插件，
# 正确做法是显式修正/排除该依赖，而不是用 `|| true` 掩盖它。
# 耗时说明：本步骤是"把依赖全量解析并拉到本地仓库"，冷缓存下明显慢于后续步骤
# （整份依赖树解析是分钟级）；下面的 `--mount=type=cache,target=/root/.m2` 就是为了让
# 本步骤与 package 步骤复用同一个 ~/.m2，第二次构建起不必重新下载。
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests dependency:go-offline

# 再拷源码并打包（跳过测试：CI/质量门禁已跑过 mvn -B test，镜像构建不再重复）
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests package \
    && cp target/campus-trade-backend-*.jar /workspace/app.jar

# ------------------------------------------------------------------------------
# 阶段 2：运行
# ------------------------------------------------------------------------------
FROM eclipse-temurin:21.0.12_8-jre-alpine-3.24

# wget 来自 busybox，供 HEALTHCHECK 使用；tzdata 让日志/时间字段按东八区输出
RUN apk add --no-cache tzdata \
    && addgroup -g 10001 -S campustrade \
    && adduser -u 10001 -S -G campustrade -h /app campustrade

WORKDIR /app

COPY --from=build --chown=campustrade:campustrade /workspace/app.jar /app/app.jar

# 日志目录归普通用户所有（logback-spring.xml 默认写 ${LOG_DIR}/campustrade.log）
RUN mkdir -p /app/logs && chown -R campustrade:campustrade /app

USER campustrade

EXPOSE 8081

ENV TZ=Asia/Shanghai \
    LOG_DIR=/app/logs \
    SERVER_PORT=8081 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"

# 说明：这里刻意不设置 SPRING_PROFILES_ACTIVE —— profile 属于部署环境的运行期决策
# （docker-compose.prod.yml 会注入 prod），镜像本身保持可复用。
# SERVER_PORT 给出与 EXPOSE / 探针一致的默认值：直接 docker run 本镜像也能探活成功。
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8081/api/school/list || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
