# java-devops-demo

> 一个订单服务，走完从 `git commit` 到生产环境金丝雀发布与自动回滚的完整链路。
> 重点不在业务代码，而在**每一步的工程决策与取舍**。

[![CI](https://github.com/blueenergy/java-devops-demo/actions/workflows/ci.yml/badge.svg)](https://github.com/blueenergy/java-devops-demo/actions/workflows/ci.yml)
[![CD](https://github.com/blueenergy/java-devops-demo/actions/workflows/cd.yml/badge.svg)](https://github.com/blueenergy/java-devops-demo/actions/workflows/cd.yml)

---

## 这个仓库想回答的问题

「熟悉 DevOps 全流程」这句话在简历上没有任何信息量。这个仓库试图用**可验证的证据**替代形容词：

| 常见说法 | 本仓库的证据 |
|---|---|
| 熟悉 CI/CD | Actions 页面上有绿色的流水线记录，含 build/test/scan/push 四个阶段 |
| 熟悉容器化 | 多阶段 Dockerfile，镜像从 600MB+ 降到 ~200MB，非 root 运行 |
| 熟悉 K8s | 完整的 Kustomize 清单，含探针、PDB、NetworkPolicy、资源配额 |
| 懂金丝雀发布 | `cd.yml` 里有一次真实的逐步放量到 100% 的 run |
| 懂回滚 | 同一个 workflow 里有一次**故障注入触发自动回滚**的 run，带 AnalysisRun 失败日志 |
| 懂可观测性 | 自定义业务指标 + Grafana 面板 + 6 条告警规则，含「为什么这条规则会静默失效」的分析 |

---

## 技术栈

```
Java 21 (Temurin)          Spring Boot 3.5.16        Maven 3.9.16 (wrapper)
PostgreSQL 17              Flyway 11.7               Testcontainers 1.21.4
JUnit 5.12 + AssertJ       JaCoCo 0.8.15             Micrometer 1.15
Docker 多阶段构建            Kustomize                 Argo Rollouts 1.10
GitHub Actions             Trivy                     Prometheus + Grafana
```

版本全部经过实测确认（见 `pom.xml` 注释）。特别说明一处：**Testcontainers 锁在 1.21.4，不升 2.x** —— Spring Boot 3.5.16 的 BOM 管理的就是 1.21.4，而 `@ServiceConnection` 的集成是按 1.x 写的，跨大版本升级会踩坑。

---

## 快速开始

```bash
# 一键起完整环境：应用 + PostgreSQL + Prometheus + Grafana
docker compose up -d --build

curl localhost:8080/actuator/health          # 健康检查
curl localhost:8080/actuator/prometheus | grep order_   # 自定义指标
open http://localhost:9091                # Prometheus（映射避开了 mihomo 占用的 9090）
open http://localhost:3000                    # Grafana (admin/admin)

# 本地开发：只跑单测（秒级）
./mvnw test

# 完整验证：单测 + Testcontainers 集成测试 + 覆盖率聚合
./mvnw verify
python3 scripts/check-coverage.py demo-coverage/target/site/jacoco-aggregate/jacoco.xml

# K8s 清单校验（不需要集群，两层）
python3 scripts/check-k8s-manifests.py          # 跨资源引用一致性
docker run --rm -v "$PWD/k8s:/m:ro" ghcr.io/yannh/kubeconform:v0.8.0 \
  -strict -summary -ignore-missing-schemas -kubernetes-version 1.31.0 \
  -schema-location 'https://fastly.jsdelivr.net/gh/yannh/kubernetes-json-schema@master/{{.NormalizedKubernetesVersion}}-standalone{{.StrictSuffix}}/{{.ResourceKind}}{{.KindSuffix}}.json' \
  -schema-location default \
  /m/base /m/rollout
```

> 两个校验是互补的，不是重复：**kubeconform 管格式**（字段名拼错、类型不对），
> **`check-k8s-manifests.py` 管关系**（Service 的 selector 能否选中 Pod、
> 探针指向的健康分组是否存在）。后者能查出的问题，schema 校验完全看不到——
> 因为每个资源单看都是合法的。

**本地不需要安装任何数据库。** 集成测试由 Testcontainers 自动起一个临时 PostgreSQL 容器，测试结束自动销毁。这是它相对 H2 内存库的核心价值：H2 的 SQL 方言、约束语义、事务隔离都和 PostgreSQL 不同，用它测出来的「通过」不能说明生产能跑。

---

## 模块结构

```
java-demo-parent
├── demo-common      对外契约：DTO、错误码、业务异常。不依赖 Spring Web
├── demo-core        领域层：JPA 实体、仓储、业务服务。不认识 HTTP
├── demo-api         接入层：控制器、全局异常处理、可观测性配置。可执行 jar 落点
└── demo-coverage    只做一件事：把各模块的 jacoco.exec 合并成跨模块覆盖率报告
```

依赖方向严格单向：`api → core → common`。

两个值得说明的结构决策：

**集成测试放在 demo-api 而不是 demo-core。** `@SpringBootTest` 需要启动类，而启动类在 demo-api（它依赖 demo-core）。把 IT 放进 core 会造成 `core → api` 的循环依赖。

**JPA 配置放在 demo-core 而不是启动类。** 写在启动类上的 `@EnableJpaRepositories` 会跟着 `@WebMvcTest` 切片一起加载，而 Web 切片里没有 JPA 自动配置，于是所有控制器测试都会因为「找不到 entityManagerFactory」而失败——报错信息完全指向 JPA，与控制器毫无关系，排查方向会被严重带偏。

---

## 流水线

```
                    ┌──────────────┐
   git push ───────▶│  构建与测试   │  mvn verify（含 Testcontainers 集成测试）
                    │              │  覆盖率门禁：行 ≥80%，分支 ≥70%，单模块 ≥60%
                    └──────┬───────┘
                           │
              ┌────────────┼────────────┐
              ▼            ▼            ▼
      ┌─────────────┐ ┌─────────┐ ┌──────────────┐
      │ 依赖漏洞扫描 │ │清单校验 │ │  构建镜像     │  buildx + provenance + sbom
      │   (Trivy)   │ │(kubeconf)│ │  推送 GHCR    │  多 tag：sha / 分支 / semver
      └─────────────┘ └─────────┘ └──────┬───────┘
                                          ▼
                                   ┌─────────────┐
                                   │ 镜像漏洞扫描 │  覆盖基础镜像里的 OS 包
                                   └──────┬──────┘
                                          ▼
                        ┌─────────────────────────────────┐
                        │  CD：kind 集群里跑真实金丝雀发布  │
                        │  ┌───────────────────────────┐  │
                        │  │ 场景 A：正常发布 → 20%→50%→100% │
                        │  │ 场景 B：注入故障 → 分析失败 → 自动回滚 │
                        │  └───────────────────────────┘  │
                        └─────────────────────────────────┘
```

---

## 文档

| 文档 | 内容 |
|---|---|
| [00-overview](docs/00-overview.md) | 一页纸全景：从代码到生产的完整路径 |
| [01-local-dev](docs/01-local-dev.md) | 多模块结构与依赖方向 |
| [02-testing](docs/02-testing.md) | 测试金字塔与覆盖率门禁怎么定 |
| [03-docker](docs/03-docker.md) | 多阶段构建、层缓存、镜像瘦身 |
| [04-cicd](docs/04-cicd.md) | Actions 逐 job 拆解与权限模型 |
| [05-kubernetes](docs/05-kubernetes.md) | 部署清单与生产化细节 |
| [06-canary-rollback](docs/06-canary-rollback.md) | **金丝雀与回滚剧本（含真实日志）** |
| [07-observability](docs/07-observability.md) | 指标、日志、告警规则设计 |
| [08-banking-devops](docs/08-banking-devops.md) | **银行/金融场景的额外考量** |
| [09-interview-playbook](docs/09-interview-playbook.md) | **面试故事线、高频问答、反问清单** |

---

## 踩过的坑

这些不是编的，是构建这个仓库时真实撞到的。每一条都写进了代码注释或文档。

**`_created` 是 Prometheus 的保留后缀。** 指标名 `order.created` 抓出来不是 `order_created_total`，而是 `order_total`——`created` 整个消失了。因为 Prometheus 文本格式里 `foo_created` 表示「计数器 foo 的创建时间戳」。Grafana 查询返回空集而不报错，故障完全静默。改成 `order.create` 解决。

**`management.prometheus.metrics.export.enabled` 必须显式打开。** 配置元数据标称默认 `true`，但实际条件判定会失败。症状极其隐蔽：应用启动无报错、`/actuator/health` 正常，但 `/actuator/prometheus` 根本没注册，请求落到静态资源处理器返回 500。排查方法是启动时加 `--debug` 看 CONDITIONS EVALUATION REPORT。

**`@ConfigurationPropertiesScan` 在测试切片里不生效。** 它走类路径扫描，而切片注册的 `TypeExcludeFilter` 会把不属于本切片的配置类排除掉。改用 `@EnableConfigurationProperties` 显式注册。

**`@Nested` 与 `@MockitoBean` 的 reset 生命周期对不上。** stubbing 会跨测试方法泄漏，而 `when(mock.method())` 这个写法**会先真实调用一次方法**，于是 `when(...)` 还没执行完就抛异常了，报错位置指向一行看起来毫无问题的 stubbing 代码。

**JaCoCo 的 `prepare-agent` 会覆盖 `argLine`。** 必须写成 `<argLine>@{argLine} ...</argLine>`（`@{}` 是延迟求值），否则 agent 参数被冲掉，覆盖率恒为 0。

**`repackage` 会替换主 artifact。** 不加 `<classifier>exec</classifier>`，类会被塞进 `BOOT-INF/classes`，聚合覆盖率报告扫不到，出空报告。

**Dockerfile 只 COPY 根 pom 会直接构建失败。** Maven 读到 `<modules>` 但子目录不存在，`dependency:go-offline` 立刻中断。

**`argoproj/argo-rollouts` 不是一个 GitHub Action。** 该仓库根目录没有 `action.yml`，写进 `uses:` 会报 `Can't find 'action.yml'`，而且错误信息不会提示你「这不是一个 Action」。

**金丝雀分析用 Prometheus 判定在演示环境会假回滚。** 没有真实流量时指标为空 → AnalysisRun 判 `Inconclusive` → Rollout 卡到超时。表面看「回滚了」，实际是超时，两者机制完全不同。改用 Job 探测作为主路径。

---

## 这个仓库刻意不做的事

**不加 lint / format 门禁。** 这不是疏忽。强制格式检查在团队规模扩大后收益递减，而它带来的摩擦是持续的：一个变量名对齐全角半角就能让流水线红掉，开发者学会的是「怎么绕过检查」而不是「怎么写好代码」。见 [04-cicd](docs/04-cicd.md) 里的取舍讨论。

**不把业务逻辑写复杂。** 订单服务只有三个接口，因为这里的重点不是业务建模。真实的复杂度留给读者自己的项目。

**不假装这是生产可用系统。** 数据库是单副本无备份、Secret 是占位、没有接入真实的密钥管理。每一处都在注释里写明了「生产应该怎么做」以及「为什么 demo 没这么做」。
