# 03 Docker：多阶段构建换来的可复现性，以及它附带的三个代价

> 一句话结论：`Dockerfile` 用「builder 编译 + runtime 只带 JRE」的两阶段结构，把构建环境的一致性从「构建者的本机」搬进了镜像；代价是每次构建都要在容器里拉一遍依赖，而且这份代价**不会被 GHA 的层缓存覆盖**。

## 两个阶段各自负责什么

| 阶段 | 基础镜像 | 职责 | 进不进最终镜像 |
|---|---|---|---|
| `builder` | `maven:3.9.16-eclipse-temurin-21-alpine` | 拉依赖、编译、打包出可执行 jar | **不进**（整层丢弃） |
| `runtime` | `eclipse-temurin:21-jre-alpine` | 只放 JRE 与那一个 jar，跑进程 | 是 |

用多阶段而不是「本地构建好 jar 再 COPY 进镜像」，买到的是三件事：

1. **构建环境可复现** —— 不依赖构建者本机的 JDK / Maven 版本；
2. **镜像里没有源码、没有 Maven、没有编译中间产物**，攻击面小得多；
3. **CI 里一条 `docker build` 就能从源码到可用镜像**，不需要前置步骤。

代价在下一节。

## builder 阶段：为什么必须 COPY 所有子模块的 pom

```dockerfile
COPY pom.xml .
COPY demo-common/pom.xml demo-common/
COPY demo-core/pom.xml demo-core/
COPY demo-api/pom.xml demo-api/
COPY demo-coverage/pom.xml demo-coverage/
```

**只 COPY 根 pom 会直接构建失败。** Maven 读到根 POM 的 `<modules>` 后会去找 `demo-common/`、`demo-core/` 这些目录，目录不存在时报：

```
[ERROR] Child module /build/demo-common of /build/pom.xml does not exist
```

这不是「警告后继续」，而是 `dependency:go-offline` 立刻中断 —— 依赖缓存这一层就挂了，后面的编译根本不会开始。

这条约束来自「多模块 + 容器构建」的组合，而不是 Docker 本身。子模块多了之后它很烦人（每加一个模块都要改 Dockerfile），所以生产里更常见的做法是 `COPY . .` 一把梭再配合 `.dockerignore` 排除 `target/`。本仓库选择逐条列出，是因为要演示「依赖层与源码层分离」这个优化点 —— 见下。

### 层的顺序就是缓存命中率

| 层 | 内容 | 什么时候失效 |
|---|---|---|
| 1 | 四个 `pom.xml` | 依赖变化时 |
| 2 | `dependency:go-offline` | 只随第 1 层失效 |
| 3 | `demo-*/src` | **改任何业务代码就失效** |
| 4 | `mvn package` | 随第 3 层失效 |

源码 COPY 放在依赖下载**之后**，是为了让「改一行业务代码」只让第 3、4 层失效，第 2 层的依赖仍然命中缓存。顺序反过来（先 COPY 源码再 `go-offline`）会让每次改代码都重新下载全部依赖。

### BuildKit cache mount：缓存不进镜像，但也绕不过 CI 的冷启动

```dockerfile
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests dependency:go-offline
```

用 cache mount 而不是把 `~/.m2` 烤进镜像层，好处是：缓存不进入最终镜像（镜像更小），而且跨构建复用、不受层失效影响。

**但有一件事必须说清楚**：GHA 的层缓存（`cache-from: type=gha` / `cache-to: type=gha,mode=max`）缓存的是**镜像层**，不包含 `--mount=type=cache` 的目录。两者是两套完全独立的机制：

| 机制 | 缓存什么 | 跨 CI run 是否保留 |
|---|---|---|
| `--mount=type=cache` | `/root/.m2` 目录内容 | BuildKit 缓存后端决定，GHA 层缓存**不含**它 |
| `cache-from/to: type=gha` | 镜像层（含中间层） | 是 |

所以在 GitHub 的托管 runner 上，**每次 CI 构建仍然会重新下载 Maven 依赖**。这不是配置错误，而是「多阶段 Dockerfile 自包含构建」这个选择的固有代价 —— 想要构建可复现，就得让构建自己把依赖拉全。

### `dependency:go-offline` 不保证拉全

它不解析「动态解析的插件」依赖，所以下面的 `package` 阶段仍可能补拉少量东西。这是正常的，不是配置错误 —— 不要因为它多下了几 MB 就去怀疑缓存没生效。

### `-DskipTests` 跳过的是执行，不是编译

```dockerfile
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests package
```

`-DskipTests` 跳过测试**执行**，测试代码仍然会被编译 —— 编译错误照样暴露。测试本身已经在 CI 的 `build-test` job 里跑过了，这里重复跑只会拖慢镜像构建。

## git-commit-id：Docker 上下文里没有 `.git`

`demo-api/pom.xml` 里这一段是必须的：

```xml
<failOnNoGitDirectory>false</failOnNoGitDirectory>
<failOnUnableToExtractRepoInfo>false</failOnUnableToExtractRepoInfo>
```

`git-commit-id-maven-plugin` 的 `failOnNoGitDirectory` **默认是 `true`**。而 `.dockerignore` 排除了 `.git`，构建上下文里没有仓库信息 —— 不改这个开关，`docker build` 会在这一步直接 `BUILD FAILURE`。

`.dockerignore` 排除 `.git` 是有意的（构建上下文越小越快，也越不容易把敏感文件打进镜像）。**如果确实需要镜像里带 commit 信息，正确做法是用 `--build-arg` 在构建时传入**，而不是把 `.git` 塞回上下文。

插件同时用 `<includeOnlyProperties>` 白名单收窄了写入 `git.properties` 的字段：`git.branch`、`git.commit.id`、`git.commit.id.abbrev`、`git.commit.time`、`git.build.time`、`git.commit.message.short`。这些字段会出现在 `/actuator/info` 里，用来回答「线上跑的是哪个 commit」。

## runtime 阶段

```dockerfile
FROM eclipse-temurin:21-jre-alpine
```

| 选择 | 理由 |
|---|---|
| `21-jre` 而不是 `21-jdk` | 运行不需要编译器，JDK 镜像多出几百 MB 且多一批可被利用的工具 |
| `alpine` | 基础镜像小；代价是 BusyBox 工具集与 glibc 的差异（用 musl） |

### alpine 没有 `useradd`

```dockerfile
RUN addgroup -S -g 10001 app \
 && adduser  -S -u 10001 -G app -s /sbin/nologin -H app
```

alpine 用的是 BusyBox，**没有 `useradd` / `groupadd`**。照抄 Debian 系的写法会得到 `useradd: not found`。必须用 `addgroup` / `adduser`。

`-S` 是创建系统用户（无密码、无过期），`-H` 是不创建 home 目录，`-s /sbin/nologin` 是禁止交互登录。uid / gid 固定成 `10001` 是为了和 `k8s/base/rollout.yaml` 里的 `runAsUser: 10001` / `runAsGroup: 10001` / `fsGroup: 10001` 对上 —— 两边数字不一致时，Pod 会因为写不了挂载目录而启动失败。

### 非 root 运行

```dockerfile
USER app
```

不写这一行的后果是容器以 root 运行。配合 k8s 侧的 `runAsNonRoot: true`，此时 Pod 会直接**启动失败**（kubelet 拒绝以 root 启动），而不是「默默降级成 root」—— 这是好事，问题在部署时暴露而不是在事后。

### `JAVA_TOOL_OPTIONS` 三个参数

```dockerfile
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -XX:+UseG1GC -Dfile.encoding=UTF-8"
```

| 参数 | 解决什么 | 不设会怎样 |
|---|---|---|
| `-XX:MaxRAMPercentage=75` | 让 JVM 按**容器内存上限**计算堆大小 | 容器里默认基于**宿主机内存**计算，宿主机内存大时堆会开到远超 limit，Pod 被 `OOMKilled` |
| `-XX:+ExitOnOutOfMemoryError` | OOM 后直接退出，让 k8s 重建 Pod | 进程带着损坏的状态继续服务，表现为「偶发 500」而不是「明确的崩溃」，排查难度高一个量级 |
| `-XX:+UseG1GC` | JDK 21 默认就是 G1 | 显式写出是为了让意图可见，不是功能需要 |
| `-Dfile.encoding=UTF-8` | 固定字符集 | 中文日志在部分基础镜像里乱码 |

`MaxRAMPercentage` 之所以必要，机制在于：JDK 的容器感知（`UseContainerSupport`）会读 cgroup 的 memory limit，但**默认的 `MaxHeapSize` 是可用内存的 1/4**。如果 cgroup limit 读取失败（老内核、某些运行时）或基础镜像没配好，JVM 就退回按宿主机内存算 —— 云上宿主机有 64G，堆就敢开到 16G，而 Pod 的 limit 只有 768Mi。表现为 Pod 反复 `OOMKilled`、`kubectl describe pod` 里 `Reason: OOMKilled`、`exitCode: 137`。

`docker-compose.yml` 里的 `deploy.resources.limits.memory: 768M` 和这里的 `MaxRAMPercentage` 是**一条链路上的两半**：前者给 cgroup 设上限，后者让 JVM 读这个上限。只配一边都无效。

### `HEALTHCHECK` 在 k8s 里不用

```dockerfile
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1
```

k8s 用自己的 `livenessProbe` / `readinessProbe`，**会忽略镜像里的 `HEALTHCHECK`**。保留它是为了 `docker compose` 本地开发时能正确判断依赖顺序（`depends_on: condition: service_healthy`）。

### ENTRYPOINT：exec 形式不是风格问题

```dockerfile
ENTRYPOINT ["java", "-jar", "/app/app.jar"]     # exec 形式（本仓库采用）
# ENTRYPOINT java -jar /app/app.jar             # shell 形式
```

| 形式 | 进程结构 | 收到 SIGTERM 的是谁 | 后果 |
|---|---|---|---|
| exec（JSON 数组） | `java` 就是 PID 1 | java 进程 | 优雅停机生效 |
| shell（字符串） | `/bin/sh -c "java ..."` 是 PID 1，java 是子进程 | sh | **java 收不到信号**，10 秒后被 SIGKILL |

shell 形式的实际后果链条：k8s 删除 Pod 时向 PID 1 发 SIGTERM → 被 `/bin/sh` 吃掉（BusyBox sh 不会转发信号给子进程）→ `spring.lifecycle.timeout-per-shutdown-phase: 30s` 的优雅停机完全不生效 → 在途请求被硬切断 → **滚动更新期间出现 502**。而且因为 `preStop` 的 `sleep 10` 还在跑，这个 502 会稳定复现，不是偶发。

### `<classifier>exec</classifier>` 与 COPY 的文件名

`demo-api/pom.xml` 里的这段配置决定了产物名：

```xml
<configuration>
  <classifier>exec</classifier>
  <mainClass>io.github.blueenergy.demo.api.OrderDemoApplication</mainClass>
</configuration>
```

| 有没有 classifier | 主 artifact | 后果 |
|---|---|---|
| 有（本仓库） | 瘦 jar `demo-api-1.0.0-SNAPSHOT.jar` + fat jar `demo-api-1.0.0-SNAPSHOT-exec.jar` | Dockerfile COPY 用 `-exec.jar`；瘦 jar 供依赖与覆盖率扫描 |
| 没有 | `repackage` 会把**主 artifact 替换成 fat jar**，类被塞进 `BOOT-INF/classes` | ① `demo-coverage` 的 `report-aggregate` 扫不到这些类，聚合报告为空；② 其它模块若依赖本模块，拿到的是不可用的 fat jar |

所以 Dockerfile 里这一行与 POM 是绑定的：

```dockerfile
COPY --from=builder --chown=app:app /build/demo-api/target/demo-api-*-exec.jar /app/app.jar
```

### 构建这个仓库时踩到的坑（值得完整读一遍）

最初 `demo-api/pom.xml` 只在 `<build><plugins>` 里声明了 `spring-boot-maven-plugin`
并设置了 `classifier`，**没有绑定 `repackage` 执行**。直觉上「声明插件就会在 package
阶段跑 repackage」——这对继承 `spring-boot-starter-parent` 的项目成立，
但那份绑定并不在插件自身，而是 **starter-parent 的 `pluginManagement` 里预置的 execution**。
本项目选择自定义父 POM，绑定就跟着消失了。

失败链条非常隐蔽：

```
mvn verify 全绿（只跑了 jar:jar，没有 repackage 这一行）
    ↓  target/ 里只有瘦 jar，没有 -exec.jar
docker build 成功（COPY 的通配符没匹配到文件，BuildKit 未报错）
    ↓  镜像里 /app 是空的
docker run → "Error: Unable to access jarfile /app/app.jar"
```

**构建、测试、推送每一步都绿，死在运行期。** 这类故障最难排查，
因为所有自动化检查都给了「通过」的信号。

修法是补一个显式 execution（不需要改 Dockerfile）：

```xml
<executions>
  <execution>
    <id>repackage</id>
    <goals><goal>repackage</goal></goals>
  </execution>
</executions>
```

**通用教训**：自定义父 POM 时，`spring-boot-starter-parent` 提供的三样东西会一起消失
——插件版本、插件 execution 绑定、资源过滤 delimiter。
「BOM 不管插件」只是其中一条，另外两条更容易被忽略，因为它们不报错。

## 替代方案：CI 先 `mvn package`，Dockerfile 只做组装

| 维度 | 自包含多阶段（本仓库） | CI 先打包 + 单阶段 runtime |
|---|---|---|
| 构建速度 | 慢：每次都要在容器里拉依赖 | 快：依赖走 CI 的 Maven 缓存 |
| 可复现性责任 | 在 Dockerfile（自带 JDK/Maven 版本） | **转移到 CI 配置**：runner 的 JDK、Maven、`settings.xml` 都要管 |
| 本地能否构建镜像 | 能，`docker build .` 一条命令 | 不能，必须先跑对 `mvn package` |
| 镜像内容 | 同上 | 同上（都是只 COPY jar） |
| 缓存机制 | BuildKit cache mount + GHA 层缓存 | CI 的 Maven 缓存（命中率高） |
| 失败定位 | 失败在 Dockerfile 里，自包含 | 失败可能在 CI 步骤或 Dockerfile，链路更长 |

关键差别不在速度，而在**「构建可复现」这份责任放在谁身上**。自包含方案里，Dockerfile 是唯一事实源；CI 打包方案里，事实源变成了「CI 上装了哪个 JDK、哪个 Maven、`~/.m2/settings.xml` 配了什么镜像」—— 这套东西在本仓库里是存在的（`~/.m2/settings.xml` 配了阿里云镜像），但它**不进版本库、不可审计**，也就无法保证换一台机器构建出同样的东西。

受监管场景下这个差别是决定性的：审计问「这个镜像怎么来的」，自包含方案能指着 Dockerfile 回答；CI 打包方案要先证明「当时那台 runner 的环境」。

## `.dockerignore` 的作用

| 排除项 | 原因 |
|---|---|
| `.git`、`.github` | 上下文更小；且避免把 CI 配置打进镜像（同时触发上一节的 `failOnNoGitDirectory`） |
| `**/target/`、`*.jar` | 防止「本机构建产物」意外进镜像，掩盖真实的构建问题 |
| `docs/`、`*.md`（保留 `README.md`）、`k8s/`、`observability/`、`scripts/` | 与运行无关 |
| `.env`、`.env.*` | 防止本地密钥被打进镜像 |

第二条值得强调：**不排除 `target/` 时，Dockerfile 里 COPY 的是构建上下文里的旧 jar 还是新编译的 jar，会变成一个不确定的问题**。排除掉之后，镜像里的 jar 一定来自容器内的 `mvn package`。

## 本地验证命令

```bash
docker build -t order-demo-api:local .              # 构建镜像
docker run --rm -p 8080:8080 \
  -e DB_URL=jdbc:postgresql://host.docker.internal:5432/orders \
  order-demo-api:local                              # 单跑（需要外部数据库）
docker compose up -d --build                        # 推荐：一次起 app + PG + Prometheus + Grafana
```

`docker compose` 那条路径还会顺带验证 `HEALTHCHECK` 与 `depends_on: condition: service_healthy` 的配合 —— 后者保证 Flyway 启动时数据库已经能接受查询，而不是仅仅「容器起来了」。
