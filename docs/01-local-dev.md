# 01 本地开发：模块边界由依赖方向定义，父 POM 的每一处「自己来」都有代价

> 一句话结论：`demo-api → demo-core → demo-common` 严格单向，`demo-coverage` 是挂在 reactor 末尾的聚合模块；父 POM 刻意不继承 `spring-boot-starter-parent`，换来插件版本与默认行为的完全可控，代价是三处 Boot 的默认行为必须自己补回来。

## 模块地图

| 模块 | 职责 | 依赖 | 产物 |
|---|---|---|---|
| `demo-common` | 对外契约：DTO、错误码、业务异常 | **无**（刻意不依赖 Spring Web） | 普通 `jar` |
| `demo-core` | 领域层：JPA 实体、仓储、业务服务、Flyway 迁移 | `demo-common` | 普通 `jar` |
| `demo-api` | 接入层：控制器、全局异常处理、可观测性配置 | `demo-core` | `jar` + 可执行 jar |
| `demo-coverage` | 只做覆盖率聚合，**没有 `src` 目录** | 上面三个业务模块 | 聚合覆盖率报告 |

依赖方向单向：`api → core → common`，**没有任何一条反向依赖**。这不是审美偏好，是 Maven reactor 的硬约束 —— reactor 按依赖关系做拓扑排序，出现环就无法确定构建顺序，构建直接失败。

比「能构建」更重要的是**契约模块必须待在依赖树底部**。`demo-common` 一旦反向依赖任何东西，所有消费者都被迫接受那份依赖；放在底部，它才可能长期稳定 —— 契约的稳定性来自「没人能改动它的依赖」。

## demo-common 的克制：只要注解，不要实现

| 依赖 | 要的是什么 | 刻意不要什么 | 加进来会怎样 |
|---|---|---|---|
| `jakarta.validation` API | `@NotNull`、`@Positive` 等校验注解 | `hibernate-validator` 实现 | 契约模块被绑死到某个校验实现，换实现要动所有下游 |
| `jackson-annotations` | `@JsonInclude` 等序列化注解 | `jackson-databind`、`ObjectMapper` | 引入运行时代码，破坏「框架无关」这条约束 |

注解库没有传递依赖、没有运行时代码，所以「在 DTO 上标注解」和「保持模块框架无关」这两件事并不冲突。校验实现由 `demo-api` 通过 `spring-boot-starter-validation` 提供，序列化实现由 `spring-boot-starter-web` 带进来。

同理，`demo-core` 只引 `micrometer-core`（埋点 API），**不引任何 registry 实现** —— 那属于接入层的决定。

## 两个结构决策，以及不这么做会怎样

### 集成测试为什么放 demo-api

`@SpringBootTest` 需要一个 `@SpringBootConfiguration` 作为配置源，而这个启动类是 `demo-api` 里的 `OrderDemoApplication`。测试放在 `demo-core` 就得让 core 依赖 api，而 api 本来就依赖 core —— 直接构成循环依赖，reactor 无法排序。

| 方案 | 结果 |
|---|---|
| IT 放 `demo-api`（本仓库采用） | 依赖方向保持单向，IT 与启动类同模块，`@ServiceConnection` 直接可用 |
| IT 放 `demo-core` | `core → api` 与 `api → core` 成环，构建失败 |
| 把启动类下沉到 `demo-core` | 能让 IT 编译，但 core 从此有了「应用」语义，且迟早要为启动引入 Web 相关依赖 |
| 手工用 `@ContextConfiguration` 指定配置类 | 失去自动配置与 `@ServiceConnection`，测试代码量与维护成本都上升 |

### JPA 配置为什么放 demo-core 而不是启动类

`@EnableJpaRepositories` 与 `@EntityScan` 写在启动类上时，会**跟着启动类一起被测试切片加载** —— 因为切片始终以 `@SpringBootConfiguration` 作为配置源。而 `@WebMvcTest` 的切片里没有 JPA 自动配置，Spring Data 仍然会尝试创建仓储 bean，于是抛：

```
NoSuchBeanDefinitionException: No bean named 'entityManagerFactory' available
```

结果是**所有控制器切片测试一起失败**，而报错信息完全指向 JPA，与控制器毫无关系，排查方向会被严重带偏。

放进 `demo-core` 的 `CoreJpaConfiguration` 之后：

| 场景 | 行为 |
|---|---|
| 完整应用启动 | `scanBasePackages = "io.github.blueenergy.demo"` 扫得到它，JPA 正常装配 |
| `@WebMvcTest` 切片 | 切片注册的 `TypeExcludeFilter` 把普通 `@Configuration` 排除掉，持久化配置不参与 |

附带收益是职责更清晰：仓储和实体在哪个包下，本来就该由拥有它们的模块自己声明，而不是让启动类去了解其它模块的内部包结构。

### 同源的第三个坑：`@EnableConfigurationProperties` 而不是 `@ConfigurationPropertiesScan`

`OrderDemoApplication` 用的是 `@EnableConfigurationProperties(FaultInjectionProperties.class)`。两者在完整应用里都能工作，但在测试切片下行为不同：`@ConfigurationPropertiesScan` 走类路径扫描，而切片注册的 `TypeExcludeFilter` 会把不属于本切片的配置类排除掉，于是 `FaultInjectionFilter` 拿不到配置对象、上下文启动失败。`@EnableConfigurationProperties` 按类引用直接注册 bean 定义，不经过扫描。

配置类数量少且明确时，显式列出比扫描更可预测。

启动类还显式写了 `scanBasePackages`。默认扫描范围只有启动类所在包及其子包，够不着 `io.github.blueenergy.demo.core`，症状是启动时报 `No qualifying bean of type OrderService` —— 多模块项目里最高频的踩坑点。

## 测试分层：Surefire 与 Failsafe 的分工

| 插件 | 识别的类名 | 绑定阶段 | 跑什么 |
|---|---|---|---|
| `maven-surefire-plugin` | `*Test` / `Test*` / `*Tests` / `*TestCase` | `test` | 秒级的纯单测，无容器、无数据库 |
| `maven-failsafe-plugin` | `*IT` / `IT*` / `*ITCase` | `integration-test` + `verify` | 起 Testcontainers 的端到端测试 |

收益是明确的：`./mvnw test` 是秒级反馈循环，改一行业务代码就能跑一遍；`./mvnw verify` 才付出起容器、连数据库的代价。

父 POM 里有两个容易踩的细节：

| 细节 | 写错的后果 |
|---|---|
| Failsafe 的执行必须同时绑 `integration-test` 与 `verify` 两个 goal | 只绑 `integration-test`，**集成测试失败不会让构建失败** —— 失败在 `integration-test` 阶段记录，要到 `verify` 阶段才据此判定构建结果 |
| Failsafe 必须写在 `<build><plugins>` 而不是只写 `pluginManagement` | 插件根本不会执行。构建全绿，集成测试一个都没跑，覆盖率还虚高 |

```xml
<plugin>
  <artifactId>maven-failsafe-plugin</artifactId>
  <executions>
    <execution>
      <id>run-integration-tests</id>
      <goals>
        <goal>integration-test</goal>
        <goal>verify</goal>   <!-- 缺了它，失败的集成测试静默通过 -->
      </goals>
    </execution>
  </executions>
</plugin>
```

## `@{argLine}`：为什么是 `@{}` 而不是 `${}`

JaCoCo 的 `prepare-agent` 在**运行期**才把 `-javaagent` 参数写进 `argLine` 属性。所以：

```xml
<argLine>@{argLine} -XX:+EnableDynamicAgentLoading</argLine>
```

| 写法 | 求值时机 | 结果 |
|---|---|---|
| `@{argLine}` | 运行期延迟求值 | agent 参数被正确拼接，覆盖率正常 |
| `${argLine}` | 配置阶段立即求值 | 求值成空串，把 agent 参数冲掉，**覆盖率恒为 0** |

父 POM 还给了 `<argLine></argLine>` 一个空默认值。原因是：`demo-coverage` 模块不跑测试、也不跑 `prepare-agent`，属性未定义时 `@{argLine}` 会求值失败。

`-XX:+EnableDynamicAgentLoading` 用来消除 JDK 21 + Mockito 5 的 self-attach 警告。

## demo-coverage 模块的三个硬性要求

这个模块没有 `src` 目录，不产出任何代码，只产出报告。三个要求缺一个就是空报告：

| 要求 | 原因 | 不满足的后果 |
|---|---|---|
| **显式依赖所有要统计的模块** | `report-aggregate` 只扫「本模块 + 它的依赖」，不会自动扫整个 reactor | 报告里少模块，而且不会有任何提示 |
| **必须是 reactor 里最后一个 module** | 它要在 `verify` 阶段读取前面所有模块产出的 `jacoco.exec` | 排在前面时 `jacoco.exec` 还没生成，聚合出来是空的 |
| **自己不能跑 `prepare-agent`** | 本模块没有测试，跑 agent 没有意义 | 用一个 `<phase>none</phase>` 把继承来的执行关掉 |

父 POM 的 `<modules>` 顺序已经保证第二条，并且在注释里写明了原因。第三条是这样关的：

```xml
<execution><id>prepare-agent</id><phase>none</phase></execution>
<execution><id>report</id><phase>none</phase></execution>
```

这里有一个**必须记住的反面做法**：绝不要在模块级用 `jacoco:check`。它只读本模块的 `jacoco.exec`，没有聚合能力，`demo-common` 这种纯 DTO 模块永远达不到阈值，绑上去必然红。父 POM 里只声明阈值数字，真正的判定在 CI 层对聚合报告执行。

## Maven Wrapper：三处版本一致

`.mvn/wrapper/maven-wrapper.properties` 的内容决定了「谁来提供 Maven」：

```properties
wrapperVersion=3.3.4
distributionType=only-script
distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip
```

| 场景 | 用什么 | 版本来源 | 漂移风险 |
|---|---|---|---|
| 本地开发 | `./mvnw` | wrapper 下载 3.9.16 到 `~/.m2/wrapper/dists` | 无 |
| CI | `./mvnw -B -ntp verify` | 同上 | 无 |
| Docker 构建 | `maven:3.9.16-eclipse-temurin-21-alpine` | 镜像 tag 里手写的版本号 | **有**，靠人工对齐 |

`distributionType=only-script` 表示不使用 `maven-wrapper.jar`，由 `mvnw` 脚本自行下载 `distributionUrl` 指向的发行版。这样 wrapper 本身没有二进制依赖，`mvnw` 与 `mvnw.cmd` 是纯脚本。

Docker 那一路是唯一无法自动对齐的：镜像 tag 里的 `3.9.16` 是手写的。**改 wrapper 版本时必须同步改 `Dockerfile` 的 `FROM`**，否则本地能构建、镜像里报错。`maven-enforcer-plugin` 是最后一道防线：

```xml
<requireMavenVersion><version>[3.9.0,)</version></requireMavenVersion>
<requireJavaVersion><version>[21,)</version></requireJavaVersion>
```

版本不对时给出明确报错，而不是几百行编译错误。

## 自定义父 POM vs 继承 spring-boot-starter-parent

这是整个构建里最需要解释的一个取舍。用 `import` scope 引入 BOM，而不是把 `spring-boot-starter-parent` 当父 POM：

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-dependencies</artifactId>
      <version>${spring-boot.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

**BOM 只带来版本仲裁，不带来 Boot 那套会覆盖我们配置的默认行为。** 代价是下面三样东西要自己补：

| 能力 | `spring-boot-starter-parent` 提供 | 本仓库怎么办 | 不补的后果 |
|---|---|---|---|
| 依赖版本仲裁 | BOM 继承 | `spring-boot-dependencies` + `import` scope | 每个依赖都要手写版本 |
| **插件版本** | 由 parent 的 `pluginManagement` 管 | **必须逐个显式钉死** | 见下 |
| 资源过滤定界符 | `<delimiter>@</delimiter>` | 自己配 `maven-resources-plugin` | `application.yml` 里的 `@project.version@` 不被替换，**构建静默通过，运行期才炸** |
| 编译级别默认值 | `maven.compiler.release` | 自己设 `java.version` / `maven.compiler.release` | 编译目标与运行时不一致 |

关于资源过滤，还有一层必须知道：用 `@` 作定界符是为了**不干扰 Spring 自己的 `${SERVER_PORT:8080}` 占位符**。如果用默认的 `${}`，Spring 的占位符会在构建期被 Maven 尝试替换，而那个属性根本不存在，替换结果不可预期。

`application.yml` 里的 `@project.version@` 还必须**加引号**：YAML 规范里 `@` 是保留字符，不加引号时源文件不是合法 YAML —— 运行期没问题（Maven 过滤先替换掉了），但编辑器标红、YAML linter 失败、IDE 配置提示全部失效。

### 父 POM 钉死了哪些插件版本

`spring-boot-dependencies` 是 BOM，**只管理 `<dependencies>`，不管理 `<plugins>`**。不钉版本时 Maven 会去仓库 metadata 解析最新版 —— `spring-boot-maven-plugin` 现在已是 4.x，`repackage` 行为与产物结构都可能变化，构建会在某天毫无征兆地挂掉。

| 插件 | 钉住的版本 | 为什么必须钉 |
|---|---|---|
| `maven-compiler-plugin` | 3.16.0 | `<release>` 与 `<parameters>true</parameters>` 的行为随版本变 |
| `maven-surefire-plugin` | 3.6.0 | 与 Failsafe 版本必须一致，否则报告格式对不上 |
| `maven-failsafe-plugin` | 3.6.0 | 同上 |
| `maven-resources-plugin` | 3.5.0 | `<delimiter>` 的解析行为 |
| `maven-enforcer-plugin` | 3.6.3 | 规则集随版本增减 |
| `jacoco-maven-plugin` | 0.8.15 | 报告 XML 结构（`<group>` 节点）会随版本变，门禁脚本依赖它 |
| `git-commit-id-maven-plugin` | 10.0.1 | 7.x → 10.x 是 groupId 变更（`pl.project13.maven` → `io.github.git-commit-id`） |
| `spring-boot-maven-plugin` | `${spring-boot.version}` | 与 BOM 同源，避免 BOM 与插件版本错配 |

`maven-compiler-plugin` 的 `<parameters>true</parameters>` 不是可选项：Spring 依赖参数名做构造器绑定与 `@RequestParam` 名称推断，关掉之后运行期会出现「参数名丢失」类的错误，而编译期毫无提示。

## 本地命令速查

| 目的 | 命令 |
|---|---|
| 只跑单测（秒级） | `./mvnw test` |
| 完整验证（单测 + Testcontainers IT + 覆盖率聚合） | `./mvnw verify` |
| 覆盖率门禁 | `python3 scripts/check-coverage.py demo-coverage/target/site/jacoco-aggregate/jacoco.xml` |
| K8s 清单跨资源引用检查 | `python3 scripts/check-k8s-manifests.py` |
| 一键起完整环境 | `docker compose up -d --build` |

**本地不需要安装任何数据库。** 集成测试由 Testcontainers 自动起一个临时的 `postgres:17-alpine` 容器，测试结束自动销毁。用真库而不是 H2 的理由见 `02-testing.md`。
