# syntax=docker/dockerfile:1.7
#
# 多阶段构建：builder 阶段编译，runtime 阶段只带 JRE 与最终产物。
#
# 为什么用多阶段而不是「本地构建好 jar 再 COPY 进镜像」：
#   1) 构建环境可复现——不依赖构建者本机的 JDK/Maven 版本
#   2) 镜像里不含源码、不含 Maven、不含编译中间产物，攻击面小得多
#   3) CI 里一条 docker build 就能从源码到可用镜像，不需要前置步骤
# 代价是构建慢一些、且每次都要在容器里拉依赖。
# 替代方案（CI 先 mvn package 再把 jar COPY 进一个极简单阶段镜像）在文档
# docs/03-docker.md 里做了对比，那条路径更快，但把「构建可复现」的责任转移给了 CI。

# ============================================================
# Stage 1: builder
# ============================================================
FROM maven:3.9.16-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# ------------------------------------------------------------------
# 关键：必须把所有子模块的 pom.xml 都 COPY 进来，不能只 COPY 根 pom。
#
# Maven 读取根 POM 的 <modules> 后会去找 demo-common/、demo-core/ 等目录。
# 目录不存在时直接报错：
#   [ERROR] Child module /build/demo-common of /build/pom.xml does not exist
# 依赖缓存这一步会直接中断，构建失败。
# ------------------------------------------------------------------
COPY pom.xml .
COPY demo-common/pom.xml demo-common/
COPY demo-core/pom.xml demo-core/
COPY demo-api/pom.xml demo-api/
COPY demo-coverage/pom.xml demo-coverage/

# 只依赖 pom、不依赖源码，所以这一层能被最大程度复用：
# 只要 pom 没变，改业务代码不会让依赖重新下载。
#
# 用 BuildKit 的 cache mount 而不是把 ~/.m2 烤进镜像层：
#   - 缓存不进入最终镜像，镜像更小
#   - 跨构建复用，且不受层失效影响
# 注意 dependency:go-offline 不保证把所有插件依赖都拉全（它不解析动态解析的插件），
# 所以下面 package 阶段仍可能补拉少量依赖——这是正常的，不是配置错误。
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests dependency:go-offline

# 源码放在后面 COPY：改代码只会让这一层之后失效，依赖层仍然命中缓存
COPY demo-common/src demo-common/src
COPY demo-core/src demo-core/src
COPY demo-api/src demo-api/src

# -DskipTests 跳过测试执行，但不跳过测试代码编译（编译错误仍会暴露）。
# 测试已经在 CI 的 build-test job 里跑过了，这里重复跑只会拖慢镜像构建。
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests package

# ============================================================
# Stage 2: runtime
# ============================================================
FROM eclipse-temurin:21-jre-alpine

# OCI 标准标签，便于供应链溯源与镜像治理
LABEL org.opencontainers.image.title="order-demo-api" \
      org.opencontainers.image.description="订单服务演示：Spring Boot 3 多模块 + 金丝雀发布" \
      org.opencontainers.image.source="https://github.com/blueenergy/java-devops-demo" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.base.name="eclipse-temurin:21-jre-alpine"

# ------------------------------------------------------------------
# 非 root 运行。
# alpine 用的是 BusyBox，没有 useradd/groupadd，必须用 addgroup/adduser。
# 直接用 useradd 会报 "useradd: not found"。
# ------------------------------------------------------------------
RUN addgroup -S -g 10001 app \
 && adduser  -S -u 10001 -G app -s /sbin/nologin -H app

WORKDIR /app

# ------------------------------------------------------------------
# 只 COPY 可执行 jar。
# 文件名带 -exec 后缀，来自 demo-api/pom.xml 里的 <classifier>exec</classifier>。
# 不加 classifier 的话 repackage 会替换主 artifact，这里就得用不带后缀的名字，
# 但那样 demo-coverage 的聚合覆盖率报告会变成空的。
# ------------------------------------------------------------------
COPY --from=builder --chown=app:app /build/demo-api/target/demo-api-*-exec.jar /app/app.jar

USER app

EXPOSE 8080

# JVM 参数说明：
#   MaxRAMPercentage=75      —— 让 JVM 按容器内存上限计算堆大小。
#                               容器里默认值是基于宿主机内存的，不设这个会导致
#                               Pod 内存超限被 OOMKilled。
#   ExitOnOutOfMemoryError   —— OOM 后直接退出，让 k8s 重建 Pod。
#                               不设的话进程可能带着损坏的状态继续服务，
#                               表现为「偶发 500」而不是「明确的崩溃」，更难排查。
#   UseG1GC                  —— JDK 21 默认就是 G1，显式写出是为了让意图可见。
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -XX:+UseG1GC -Dfile.encoding=UTF-8"

# 容器级健康检查。
# 注意：k8s 环境不用这个，k8s 用自己的 liveness/readiness probe。
# 这里保留是为了 docker-compose 本地开发时能正确判断依赖顺序。
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1

# 用 exec 形式（JSON 数组）而不是 shell 形式：
# shell 形式会包一层 /bin/sh -c，导致 java 进程不是 PID 1，
# 收不到 k8s 发的 SIGTERM，优雅停机失效，滚动更新时会出现 502。
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
