# 04 CI/CD：五个 job 的依赖关系，以及「门禁会不会被绕过」这条设计准绳

> 一句话结论：`ci.yml` 的五个 job 构成「三个并行前置检查 → 构建镜像 → 扫描镜像」的三层结构；顶层只给 `contents: read`，每个 job 按需提权 —— 权限模型和门禁设计遵循同一条原则：**假设某一个环节会出问题，然后限制它的爆炸半径**。

## 触发策略与并发控制

| 事件 | 条件 | 意图 |
|---|---|---|
| `push` | 分支 `main` | 主干必须始终可发布 |
| `push` | tag `v*.*.*` | 发布版本，产出带语义化版本号的镜像 |
| `pull_request` | 目标 `main` | 合并前拦截 |
| `workflow_dispatch` | 手动 | 排查 flaky 测试时重跑 |

```yaml
concurrency:
  group: ci-${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true
```

同一分支的新提交会取消上一次仍在跑的流水线。不配这个的话，连续推送 3 次会同时跑 3 条流水线 —— 既浪费额度，也让「最新一次到底绿没绿」变得难以判断（旧的那条红、新的那条绿，得点进去看时间戳）。

`cancel-in-progress: true` 在 CI 上是对的，**在 CD 上是错的**：`cd.yml` 用的是 `false`，因为部署被中途掐断会让集群停在中间状态（半新半旧的 ReplicaSet、进行到一半的 AnalysisRun）。

## Job 依赖图

```
build-test ─┬─▶ dependency-scan    ─┬─▶ docker ──▶ image-scan
            ├─▶ manifest-validation ─┘    (push)     (扫 digest)
            └────────────────────────┘
            needs 三者全部通过，才产出可部署的镜像
```

| Job | 职责 | 超时 | 权限 | 关键产出 |
|---|---|---|---|---|
| `build-test` | 编译、单测、IT、覆盖率聚合与门禁、测试报告 | 20m | `contents: read` + `checks: write` + `pull-requests: write` | 覆盖率 artifact、Actions 上的测试列表 |
| `dependency-scan` | 扫 `pom.xml` 解析出的全部传递依赖 | 10m | `contents: read` | Trivy 表格输出 |
| `manifest-validation` | 跨资源引用检查 + kubeconform schema 校验 | 10m | `contents: read` | 校验结论 |
| `docker` | 构建并推送镜像（带 provenance 与 sbom） | 25m | `contents: read` + `packages: write` | `digest`、`version`、GHA 层缓存 |
| `image-scan` | 扫最终镜像（含基础镜像里的 OS 包） | 15m | `contents: read` + `packages: read` | Trivy 表格输出 |

`docker` 依赖三个前置 job，落地的是「**三个门禁都过了才允许产出可部署的产物**」。`image-scan` 单独拆出来而不是塞进 `docker`，是因为它扫的是**已经推上去的镜像**（`image-ref` 用 `@${{ needs.docker.outputs.digest }}`），必须等推送完成。

## 权限模型：顶层最小，按需提权

```yaml
permissions:
  contents: read      # 顶层，所有 job 的默认值
```

顶层给 `write` 是常见的过度授权。**关键在于爆炸半径**：GitHub Actions 里第三方 action 被投毒（上游仓库被入侵、tag 被移动、`@main` 指向恶意提交）是真实发生过的攻击类型。如果顶层就是 `contents: write`，一个被投毒的 action 拿到的就是整个仓库的写权限 —— 可以改代码、改 workflow、直接推到 main。按 job 提权之后，同一个被投毒的 action 在 `manifest-validation` 里能拿到的只有「读代码」。

这也是每个 job 都用 `actions/checkout@v7` 这种固定大版本 tag 而不是 `@main` 的原因。更严格的做法是钉 commit SHA，本仓库没走到那一步（会在上游每次发版时增加维护成本），但这是一个明确的下一步。

镜像推送到 GHCR 用的 `secrets.GITHUB_TOKEN` 是 workflow 自动获得的临时令牌，**不需要在仓库里配置任何长期凭证**。

## build-test

### 只用 `setup-java` 的缓存，不再手写 `actions/cache`

```yaml
- uses: actions/setup-java@v6
  with:
    distribution: temurin
    java-version: '21'
    cache: maven
```

**两者指向同一个路径（`~/.m2/repository`），同时启用会互相覆盖，偶发 restore 失败。** 症状是「有时候构建特别慢」这种难以复现的问题 —— 缓存 restore 失败不会让 job 失败，只会静默地重新下载全部依赖。这类问题的排查成本远高于它省下的那点灵活性。

### `./mvnw -B -ntp verify` 一次完成什么

`compile`（含 `<parameters>true</parameters>`）→ Surefire 单元测试（`*Test`）→ `package` → Failsafe 集成测试（`*IT`，起 Testcontainers 的 PostgreSQL）→ JaCoCo 各模块采集 `jacoco.exec`、`demo-coverage` 聚合出报告。

GitHub 的 `ubuntu-latest` runner 自带 Docker，Testcontainers 能直接起 PostgreSQL，**不需要任何额外配置** —— 这也是选 GitHub Actions 而不是自建 runner 的原因之一（自建 runner 要自己保证 Docker daemon 可用且 `DOCKER_HOST` 正确）。

### 覆盖率门禁为什么单独一步

```yaml
- name: 覆盖率门禁
  run: python3 scripts/check-coverage.py demo-coverage/target/site/jacoco-aggregate/jacoco.xml \
         --min-line 0.80 --min-branch 0.70 --min-module-line 0.60
```

塞进 `mvn verify`（比如用 `jacoco:check`）也能失败，但**失败时看不出是哪一类问题**。单独一步之后能一眼区分：

| 失败在哪一步 | 含义 | 处理方式 |
|---|---|---|
| 「运行单元测试与集成测试」 | 测试挂了 | 修代码 |
| 「覆盖率门禁」 | 测试全过，但覆盖不够 | 补测试 |

两者的处理方式完全不同，混在一起会让「测试挂了」和「覆盖率不达标」在日志里长得一模一样。

门禁脚本是手写的 Python 而不是现成 action，三个理由：**确定性**（第三方 action 在不同事件类型下行为不一致，「仅对比变更文件」这类功能在非 PR 场景会静默跳过，而门禁最忌讳「有时候不生效」）、**可解释**（能逐行讲清判定逻辑）、**零依赖**（少一个供应链攻击面）。

阈值不搞一刀切：全局行 ≥ 80%、分支 ≥ 70%，同时**分模块卡 60%**。只看全局会被平均掉 —— 一个 100% 覆盖的模块可以掩盖另一个 0% 覆盖的模块。

### `dorny/test-reporter` 的 glob：必须覆盖两个目录，且必须是单行

```yaml
# 正确写法：一个 brace 展开的 glob
path: '**/target/{surefire-reports,failsafe-reports}/TEST-*.xml'
```

| 只写 surefire | 两个都覆盖（本仓库） |
|---|---|
| 单元测试结果可见 | 单元测试 + 集成测试结果都可见 |
| **集成测试的失败在报告里完全看不到** | 集成测试失败会出现在同一个列表里 |

这个疏漏的**症状具有欺骗性**：报告里有一堆绿色用例，看起来测试跑得挺好，只是「IT 没显示出来」而已。实际上 IT 可能正在失败 —— 你只是没在报告里看到它。

**为什么必须写成单行**：直觉上会写成 YAML 多行块：

```yaml
path: |
  **/target/surefire-reports/TEST-*.xml
  **/target/failsafe-reports/TEST-*.xml
```

但 v3 内部用 fast-glob 解析，实测会把这个多行字符串当成**一个带换行的路径**，
CI 直接报：

```
##[warning]No file matches path **/target/surefire-reports/TEST-*.xml
**/target/failsafe-reports/TEST-*.xml
##[error]No test report files were found
```

注意报错把两行原样拼在一起 —— 这就是「被当成一个模式」的直接证据。
换成 brace 展开后一次通过。fast-glob 的 `{}` 语法本来就支持多选，
不需要依赖换行分隔。

`fail-on-error: false` 也是刻意的：这一步的职责是「呈现」，不是「判定」。判定已经由 `mvn verify` 做过了，让它再失败一次只会让「哪一步才是真正的失败点」变模糊。

### `if: always()` 为什么关键

```yaml
- name: 发布测试报告
  uses: dorny/test-reporter@v3
  if: always()
```

测试失败时这一步**才最需要执行** —— 不加 `if: always()`，失败路径下它会被跳过，恰恰看不到失败详情。这是 Actions 的默认行为：前序步骤失败后，后续步骤除非显式声明 `if: always()` 或 `if: failure()`，否则一律跳过。

同一个道理适用于「上传测试与覆盖率报告」（`actions/upload-artifact@v7` 也带 `if: always()`）。报告只在成功时上传，等于在最需要它的时候拿不到它。`cd.yml` 里也是这个模式：`if: always()` 汇总集群最终状态，`if: failure()` 导出诊断信息。

## dependency-scan：Trivy 扫依赖

```yaml
scan-type: fs
scan-ref: .
scanners: vuln
format: table
exit-code: '1'
severity: 'CRITICAL,HIGH'
ignore-unfixed: true
```

### 这个门禁真拦下了一次（不是理论）

首次跑 CI 时被它拦住了，报的是：

```
org.postgresql:postgresql │ CVE-2026-54291 │ HIGH │ fixed │ 42.7.11 → 42.7.12
  "Man-in-the-middle protection bypass via SCRAM-SHA-256-PLUS downgrade"
```

42.7.11 正是 Spring Boot 3.5.16 BOM 管理的版本 —— **BOM 统一管理的依赖也会带 CVE**，
而且你没有任何感知。

修法是在父 POM 的 `dependencyManagement` 里显式覆盖：

```xml
<dependency>
  <groupId>org.postgresql</groupId>
  <artifactId>postgresql</artifactId>
  <version>42.7.14</version>
</dependency>
```

显式条目优先于 import 进来的 BOM，不需要升整个 Boot 版本。

**这暴露了 BOM 统一版本的真实代价**：集中管理省掉了版本冲突，但单个依赖的
紧急安全修复必须显式覆盖，否则要等下一个 Boot patch 发布。
判断标准：安全修复等不起就覆盖，功能升级可以等。

> 这也是「为什么依赖扫描不能省」的最好论据：没有这道门禁，
> 这个 HIGH 漏洞会一直躺在生产镜像里，直到某天被外部扫描或审计发现。

### 为什么用 `format: table` 而不是 `sarif`

| 维度 | `table` + `exit-code`（本仓库） | `sarif` |
|---|---|---|
| 结果落点 | Actions 日志，直接可读 | GitHub Security 页签 |
| 前置条件 | 无 | **私有仓库需要 GitHub Advanced Security（额外付费）** |
| 权限 | 不需要额外权限 | 需要 `security-events: write` |
| 门禁确定性 | 退出码非零即失败，行为一致 | 上传失败与扫描失败难以区分 |

`sarif` 的可视化确实更好，但它把一个**确定性的门禁**变成了「依赖某个付费特性才能看到结果」的东西。公开仓库可以改用 `sarif`，代价是多一个权限；本仓库选 `table`，是为了在任何仓库类型下行为一致。

### `ignore-unfixed: true` 的门禁设计考量

含义是：**只对「已有修复版本」的漏洞失败**。

不过滤的话，一个尚未发布补丁的 CVE 会**永久卡住流水线**。第一次遇到会去查，第二次会去查，第三次发现「上游根本还没修」，于是只剩下两个选择 —— 关闭扫描，或者把它加进忽略列表。**门禁设计要考虑「会不会被绕过」**：一条会长期红的门禁最终一定会被关掉，而且关掉之后会把真正有用的那部分一起关掉。

`ignore-unfixed: true` 把这个必输的局面提前化解掉：能修的立刻拦下来，修不了的记录在案但不阻塞。代价是「未修复的高危漏洞会流进镜像」—— 这个风险由镜像扫描和定期的依赖巡检来兜。

## manifest-validation：schema 校验查不出的那一类错误

```yaml
- run: python3 scripts/check-k8s-manifests.py
- run: docker run --rm -v "$PWD/k8s:/m:ro" ghcr.io/yannh/kubeconform:v0.8.0 \
         -strict -summary -ignore-missing-schemas -kubernetes-version 1.31.0 /m/base /m/rollout
```

| 工具 | 管什么 | 查不出什么 |
|---|---|---|
| `kubeconform` | 单个资源是否符合 schema（字段名拼错、类型不对、必填缺失） | 资源之间的关系 |
| `check-k8s-manifests.py` | 资源之间的关系 | 字段级错误 |

后者覆盖的检查项，每一个都是「每个资源单看都合法，但合起来不工作」：

| 检查项 | 不查的后果 |
|---|---|
| Service selector 能否选中某个工作负载的 Pod 标签 | 「部署成功但零流量」，Endpoints 为空 |
| `envFrom` 引用的 ConfigMap / Secret 是否存在 | Pod 卡在 `CreateContainerConfigError`，而 Deployment 看起来只是「副本没起来」 |
| Rollout 的 `stableService` / `canaryService` 是否存在 | 发布时流量无处可切 |
| Rollout 引用的 AnalysisTemplate 是否存在 | 金丝雀步骤直接报错 |
| 探针路径对应的健康分组是否在 `application.yml` 里定义过 | Pod 永远不 Ready，而报错信息只有 404 |

`-ignore-missing-schemas` **是必须的**：Argo Rollouts 的 `Rollout` 与 `AnalysisTemplate` 是 CRD，kubeconform 的内置 schema 里没有，不加这个参数会把它们当成校验失败。

## docker：构建、打标签、推送

### PR 上不推送，但仍然验证构建

```yaml
if: github.event_name != 'pull_request' || github.event.pull_request.head.repo.full_name == github.repository
...
push: ${{ github.event_name != 'pull_request' }}
```

| 场景 | job 是否运行 | 是否推送 |
|---|---|---|
| push 到 main / tag | 运行 | 推送 |
| 同仓库的 PR | 运行 | **不推送**（`push: false`），但验证 Dockerfile 能构建成功 |
| fork 来的 PR | **整个 job 跳过** | — |

PR 的代码还没合并，推上去会污染镜像仓库的 tag 空间。但「Dockerfile 还能不能构建」是 PR 必须验证的，所以用 `push: false` 跑一次。fork PR 直接跳过整个 job，是因为 fork 拿不到有写权限的 `GITHUB_TOKEN`，硬跑只会失败。

### 标签策略

| 标签类型 | 例子 | 用途 |
|---|---|---|
| `sha` | `4f3a…`（长 sha，无前缀） | **唯一可追溯到 commit 的标签** |
| `semver` | `1.2.3`、`1.2` | 发布版本 |
| `branch` | `main` | 主干最新 |
| `latest` | 仅在默认分支 | 便利标签，不可用于生产 |

`docker` job 把 `digest` 与 `version` 输出出去，并在 Step Summary 里写明：**生产部署应使用 digest 而不是 tag** —— tag 可变，digest 不可变。

### provenance + sbom：金融场景为什么值得多花时间

| 产物 | 回答什么问题 | 没有它时的代价 |
|---|---|---|
| `provenance`（来源证明） | 「这个镜像是用哪个 Dockerfile、从哪个 Git commit、在哪个构建器上产出的」 | 审计问「当时上线的是哪份代码」，只能靠人肉对时间戳 |
| `sbom`（软件物料清单） | 「这个镜像里有哪些软件包及版本」 | 新 CVE 爆发时回答「我们哪些镜像受影响」，要靠人肉排查，可能要几天 |

金融场景里这两个不是加分项，而是审计的硬性要求。**它们回答的是不同的问题**：provenance 是「怎么来的」，sbom 是「里面有什么」。两者缺一不可 —— 只有 provenance，CVE 爆发时仍然不知道影响面；只有 sbom，无法证明这份清单对应的就是线上跑的那个镜像。SLSA 分级里 provenance 属于 Level 3 的能力。

### GHA 层缓存不覆盖 cache mount

```yaml
cache-from: type=gha
cache-to: type=gha,mode=max
```

缓存的是**镜像层**，不包含 Dockerfile 里 `--mount=type=cache` 的目录 —— 所以每次构建仍会重新下载 Maven 依赖。这是「多阶段 Dockerfile 自包含构建」这个选择的固有代价，替代方案见 `03-docker.md`。

## image-scan：与依赖扫描的分工

| 扫描 | 覆盖范围 | 看不到什么 |
|---|---|---|
| 依赖扫描（`fs`，扫 `pom.xml`） | Java 依赖树里的库及其版本 | 基础镜像里的 OS 包 |
| 镜像扫描（`image`，扫最终镜像） | Java 依赖 + **基础镜像（`eclipse-temurin:21-jre-alpine`）里的 OS 包** | 未打包进镜像的开发依赖 |

**很多高危 CVE 来自 `openssl`、`zlib` 这类系统库，依赖扫描完全看不到。** 两者互补，缺一不可。

用 `@digest` 而不是 `:tag` 引用镜像是刻意的：tag 可变，扫描时可能拉到与刚才推送内容不同的镜像 —— 那就变成「扫了个别的镜像」。`image-scan` 的 `if: github.event_name != 'pull_request'` 是因为 PR 上根本没有推送镜像，没有东西可扫。

## 取舍讨论：为什么不加 lint / format 门禁

这不是疏忽，是一个有意的决定。判断依据不是「格式重不重要」，而是**「这条门禁会不会被绕过」**。

### 门禁的价值取决于它被绕过的成本

| 门禁 | 绕过方式 | 绕过的成本与可见性 |
|---|---|---|
| 覆盖率门禁 | 把阈值从 0.80 改成 0.60 | 要改一个数字，**diff 里明明白白**，reviewer 会问「为什么降」 |
| 漏洞扫描 | 把 CVE 加进忽略列表 | 要写一个 ID，**同样在 diff 里可见**，且会累积成一份可审计的清单 |
| 格式检查 | 本地跑一次 `spotless:apply` 再推 | **零成本、零痕迹**，PR diff 里只看到格式化结果，看不出「这里曾被 CI 拦过一次」 |

第三行是问题所在。格式检查的失败信息对开发者**没有信息量**（「第 37 行缩进应为 4 空格」），修复动作是机械的。机械的修复动作一定会被自动化：开发者会装一个 pre-commit hook，或者养成「CI 红了就跑一遍格式化再推」的习惯。于是这条门禁的实际效果是**多跑一次流水线** —— 它没有提升代码质量，只是增加了一次往返，而这次往返消耗的是开发者对 CI 的耐心。

### 更糟的后果：它侵蚀其它门禁的可信度

一条会被绕过的门禁，伤害的不只是它自己。当团队养成「红了先看看是不是那个格式检查」的习惯之后，**真正重要的门禁（覆盖率、漏洞、集成测试）被认真对待的概率也会下降** —— 红点的信号密度被稀释了。

门禁数量与信号密度成反比。加一条低价值门禁，成本不是「多一条检查」，而是「所有检查都变得可以商量」。

### 那格式统一怎么办

本仓库用 `.editorconfig` 在**编辑器层面**解决，而不是在 CI 层面：

```ini
[*]
end_of_line = lf
indent_style = space
indent_size = 4
[*.{xml,pom}]        # 缩进 2
[*.{yml,yaml,json}]  # 缩进 2
```

编辑器与 IDE 直接读取它，格式在**保存文件的那一刻**就统一了 —— 毫秒级、零等待、不会失败、不需要绕过。`*.md` 单独关掉 `trim_trailing_whitespace`，因为 Markdown 里行尾两个空格是有意义的换行符。

`.gitattributes` 兜住另一半：`* text=auto eol=lf`，避免 Windows 检出时把 `mvnw` 变成 CRLF 导致 `bad interpreter: /bin/bash^M` —— 这个错误在 WSL + Windows 双环境下极常见。

### 什么时候该加

不是永远不加。团队规模上去之后（大约十人以上），格式一致性的收益会超过摩擦成本，但**正确的接入点仍然不是「每次 push 都跑一遍完整检查」**：

| 做法 | 评价 |
|---|---|
| CI 里跑格式检查并阻塞合并 | 摩擦最高，最容易养成「先绕过再说」的习惯 |
| 本地 pre-commit 自动格式化 | 摩擦低，问题在提交前就消失 |
| CI 里只校验「产物是否与格式化结果一致」，单独一个 job 不阻塞其余 | 可接受的折中 |

本仓库的选择是把 CI 的位置留给「测试、覆盖率、漏洞、清单」这四类**绕过成本高、且失败信息有信息量**的门禁。

## 失败排查速查

| 症状 | 大概率原因 |
|---|---|
| 「运行单元测试与集成测试」红，日志里有容器启动失败 | Testcontainers 拉镜像失败或 Docker daemon 不可用 |
| 「覆盖率门禁」红，且报告为空 | `demo-coverage` 没拿到 `jacoco.exec`；常见根因见 `03-docker.md` 里关于 `repackage` 的现状核对 |
| 「覆盖率门禁」红，提示「未找到 `<group>` 节点」 | JaCoCo 版本变化导致聚合报告 XML 结构变了 |
| 测试报告里只有单测、没有 IT | `dorny/test-reporter` 的 `path` 少了 `failsafe-reports` |
| 「清单校验」红，报 `Rollout` 找不到 schema | 少了 `-ignore-missing-schemas` |
| 构建特别慢但缓存看起来是开的 | `setup-java` 的 `cache: maven` 与手写的 `actions/cache` 撞路径了 |

## 与 CD 的衔接

`ci.yml` 的终点是「一个带 digest、带 provenance、带 sbom 的镜像」。`cd.yml` 从这里接手，在 kind 集群里跑两个场景：金丝雀发布逐步放量到 100%、注入故障触发自动回滚。两个 workflow 的 `concurrency` 策略不同（CI 取消进行中的，CD 不取消），文件里都写了原因。
