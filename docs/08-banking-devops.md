# 银行/金融场景下的 DevOps 额外考量

> 通用的 DevOps 实践在银行环境下会遇到额外的约束。这份文档梳理**哪些做法需要改变、
> 哪些需要加强**，以及本仓库里对应的实现或说明。
>
> 核心差异一句话：**互联网追求「快速失败、快速恢复」，银行追求「不出错、可追溯」。**
> 这不是保守，而是业务性质决定的——一次交易错误可能涉及真实的资金损失和监管处罚。

---

## 一、变更管理：从「自动部署」到「授权后自动部署」

### 差异在哪

互联网团队的典型流程是「合并即部署」。银行不行——**变更必须可追溯到授权人**。
监管检查时会问：「2026 年 3 月 15 日那笔生产变更，谁批准的？依据是什么？」

### 本仓库的实现

GitHub Environments 的 required reviewers 机制：

```yaml
# .github/workflows/cd.yml 中的部署 job
deploy-production:
  environment:
    name: production
    url: https://order-demo.example.com
  # environment 配置里设置 required reviewers，
  # 未获批准时 job 会停在 "Waiting for approval" 状态
```

**关键点**：审批发生在**部署动作之前**，而不是代码合并时。
这两者的区别很重要——合并进 main 只代表「代码可以进入发布流程」，
不代表「可以上生产」。

### 还需要什么

| 能力 | 实现方式 | 本仓库状态 |
|---|---|---|
| 变更单号关联 | 部署时校验 commit message 含 `CHG-xxxxx` | 未实现，需要接入内部变更系统 |
| 审批留痕 | GitHub deployment 记录自带审批人与时间戳 | 由平台提供 |
| 变更窗口限制 | 禁止在交易高峰/月末结算期部署 | 需要自定义校验脚本 |
| 双人复核 | required reviewers 至少配 2 人 | 平台配置项 |

---

## 二、制品不可变：按 digest 而不是 tag 部署

### 为什么这是硬要求

`ghcr.io/org/app:v1.2.3` 这个 tag 是**可变的**。今天它指向 commit A，
下周有人重新推送同名 tag，它就指向 commit B 了。这破坏两件事：

- **可审计性**：三个月后回查「当时上线的是哪份代码」，tag 给不出答案
- **可回滚性**：回滚到 `v1.2.2` 时，拉到的可能已经不是当初那个 `v1.2.2`

### 实现

```yaml
# k8s/overlays/prod/kustomization.yaml
images:
  - name: order-demo-api
    newName: ghcr.io/blueenergy/java-devops-demo/demo-api
    digest: sha256:...   # CI 推送镜像后把真实 digest 写回这里
```

CI 里对应的一步：

```yaml
- name: 输出镜像摘要
  run: echo "digest=${{ steps.build.outputs.digest }}" >> $GITHUB_OUTPUT
```

**digest 是镜像内容的 SHA256**，内容变一个字节 digest 就变。
按 digest 部署意味着「部署了哪个版本」有唯一确定的答案。

> 面试延伸：K8s 的 `imagePullPolicy` 与 digest 的关系。
> 用 tag 时默认策略是 `IfNotPresent`，节点上已有同名 tag 就不会重新拉——
> 这在 tag 被覆盖的场景下会导致「不同节点跑着不同代码」。
> 用 digest 时不存在这个问题，因为 digest 唯一。

---

## 三、供应链安全：SBOM、provenance、签名

### 三者的分工

| 能力 | 回答的问题 | 本仓库实现 |
|---|---|---|
| **SBOM** | 这个镜像里有哪些软件包和版本？ | `build-push-action` 的 `sbom: true` |
| **provenance** | 这个镜像从哪份代码、哪个构建器产出的？ | `provenance: true`（SLSA Level 3 能力） |
| **签名** | 这个镜像确实是我们构建的、没被篡改？ | 未实现，见下方 |

### SBOM 的实际价值

CVE 爆发时（比如 Log4Shell 那种），第一个问题永远是：
**「我们有哪些镜像受影响？」**

- 没有 SBOM：逐个镜像扫描、或者靠人回忆哪个服务用了这个库，可能要几天
- 有 SBOM：一条查询就能列出所有含 `log4j-core < 2.17.0` 的镜像

对银行来说，监管可能会要求**在 N 小时内报告受影响范围**。没有 SBOM 这个时限根本达不到。

### 签名（本仓库未实现，但要知道怎么做）

```bash
# 用 cosign 做 keyless 签名，基于 OIDC 身份而非长期密钥
cosign sign --yes ghcr.io/org/app@sha256:...

# 部署时校验，拒绝未签名的镜像
cosign verify ghcr.io/org/app@sha256:... \
  --certificate-identity-regexp='https://github.com/org/.*' \
  --certificate-oidc-issuer=https://token.actions.githubusercontent.com
```

配合 Kyverno 或 OPA Gatekeeper 做准入控制，未签名的镜像**根本进不了集群**。

**keyless 签名比密钥签名更适合 CI**：不需要在仓库里存私钥，
签名身份绑定到 GitHub Actions 的 OIDC token，审计链更清晰。

---

## 四、环境隔离与部署来源限制

### 三套环境的差异化要求

| 维度 | dev | staging | prod |
|---|---|---|---|
| 触发方式 | push 自动 | push 自动 | **tag + 人工审批** |
| 部署来源 | 任意分支 | main | **仅 tag** |
| 数据库 | 容器内 PostgreSQL | 独立实例 | **托管数据库 + 只读副本** |
| 故障注入 | 允许 | 允许 | **禁止** |
| 日志级别 | DEBUG | INFO | INFO |
| 副本数 | 1 | 2 | 3+ |

### 「仅 tag 部署」的实现

```yaml
on:
  push:
    tags: ['v*.*.*']    # 只有打 tag 才触发生产部署
```

**为什么不用分支**：分支是移动的，`main` 今天和下周指向不同的 commit。
tag 一旦打过就不再移动（配合 branch protection 禁止 force push tag），
是「这个版本」的稳定引用。

### 生产环境的配置兜底

```yaml
# k8s/overlays/prod/kustomization.yaml
configMapGenerator:
  - name: order-demo-api-config
    behavior: merge
    literals:
      # 即便有人误操作改了 base，overlay 也会把它覆盖回来
      - FAULT_ENABLED=false
      - FAULT_ERROR_RATE=0.0
```

这是**防御性配置**：不依赖「大家记得别在生产开故障注入」，而是让配置本身不可能出错。

---

## 五、数据库变更：银行最痛的一环

### 为什么比互联网更敏感

银行的核心库往往有这些特征：
- 单表数据量极大（亿级），加索引要几十分钟
- 有大量下游系统在读，不能随意改表结构
- 变更窗口极窄（可能只有凌晨几小时）
- 出错影响面大（涉及资金）

### expand/contract 四步法

```
发布 N     发布 N+1        发布 N+2        发布 N+3
  │           │               │               │
  ▼           ▼               ▼               ▼
[加新列] → [双写新旧] → [回填历史数据] → [删除旧列]
  │           │               │               │
对旧代码    新旧代码        新旧代码        新代码
无影响      都能跑          都能跑          独占
```

**关键认识**：数据库变更不是「一次发布」，而是一个跨越多次发布的周期。
想在一个 release 里做完，就必然要停机。

### Flyway 的具体限制

Flyway **社区版没有 `undo`**。这意味着：

- `V1__xxx.sql` 执行后，没有 `flyway undo` 可以回退
- 唯一的方法是写一个新的 `V2__revert_xxx.sql` 前滚
- 而前滚脚本如果是 `DROP COLUMN`，数据就真的没了

所以**迁移脚本必须按「不可回滚」来设计**：

```sql
-- 好的迁移：只加不改
ALTER TABLE orders ADD COLUMN channel VARCHAR(32);
-- 旧代码不写这一列，INSERT 时为 NULL，完全不受影响

-- 危险的迁移：直接删列
ALTER TABLE orders DROP COLUMN legacy_status;
-- 旧版本代码还在跑，一读就报错。必须等所有实例都升级完才能做。
```

本仓库的迁移脚本 `V1__create_orders_table.sql` 是纯新建表，
属于最安全的类型。真实的迭代场景要按上面的四步法组织。

---

## 六、可观测性：从「能看」到「能举证」

### 银行场景的特殊要求

| 要求 | 互联网常见做法 | 银行要求 |
|---|---|---|
| 日志留存 | 7-30 天 | **6 个月到 2 年**（监管） |
| 审计日志 | 可选 | **强制，且不可篡改** |
| 敏感信息 | 尽量脱敏 | **强制脱敏，且要能证明** |
| 交易可追溯 | 尽力而为 | **每笔可追溯，含完整链路** |

### 本仓库的对应实现

**日志脱敏**——`GlobalExceptionHandler` 的兜底分支刻意不返回 `ex.getMessage()`：

```java
@ExceptionHandler(Exception.class)
public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
    log.error("未预期异常 path={}", request.getRequestURI(), ex);   // 细节进日志
    return ...body(ApiErrorResponse.of(
            ErrorCode.INTERNAL_ERROR.code(),
            ErrorCode.INTERNAL_ERROR.defaultMessage(),   // 对外只给通用消息
            request.getRequestURI()));
}
```

原因：内部异常消息可能包含表名、SQL 片段、文件路径、甚至连接串。
对外返回这些等于免费给攻击者提供侦察信息。

**链路追踪预留**——`application.yml` 的日志格式里预留了 traceId/spanId 占位：

```yaml
logging:
  pattern:
    console: "%d{...} %-5level [${spring.application.name:},%X{traceId:-},%X{spanId:-}] %logger{36} - %msg%n"
```

接入 Micrometer Tracing 后无需改配置。**预先留位比事后改造便宜得多**。

### DORA 四指标

银行 DevOps 面试常问「你怎么衡量交付效能」。四个指标：

| 指标 | 定义 | 银行场景的目标倾向 |
|---|---|---|
| **部署频率** | 多久部署一次 | 不求高，求稳（可能是每周/每月） |
| **变更前置时间** | 从提交到上线 | 受审批流程影响，通常以天计 |
| **变更失败率** | 导致故障的变更占比 | **核心指标，要求极低** |
| **MTTR** | 从故障到恢复 | **核心指标，要求极短** |

**银行的优先级明显偏向后两个**。所以本仓库在设计上：
- 自动回滚（降低 MTTR）
- 金丝雀分析在放量前拦截（降低变更失败率）
- 快速回滚能力（旧 ReplicaSet 保留，selector 切换即可）

---

## 七、把一次回滚写成 RCA

面试时最能体现深度的材料，是一份真实的故障复盘。格式：

```markdown
## 事故编号 INC-2026-1019

**时间线**（全部为 UTC+8）
| 时刻 | 事件 |
|---|---|
| 14:02 | 发布 v1.3.0，金丝雀开始放量至 20% |
| 14:03 | AnalysisRun `order-demo-api-smoke-xxx` 第 1 次探测失败 |
| 14:03 | 第 2 次探测失败，达到 failureLimit: 1，AnalysisRun 判定 Failed |
| 14:03 | Rollout 自动中止，stableService 切回 v1.2.9 |
| 14:04 | 确认稳定版本服务正常，告警恢复 |
| **MTTR** | **2 分钟**（其中人工介入 0 分钟） |

**根因**
新版本引入的变更导致 /api/v1/orders 接口 50% 概率返回 500。
（演示中由 FAULT_ENABLED 注入，真实场景对应代码缺陷或配置错误。）

**为什么金丝雀拦住了**
1. 分析任务探测的是**业务接口**而不只是健康检查——
   应用完全可能「活着但业务全挂」
2. 探测包含写路径（创建订单 → 查询 → 撤单），覆盖了数据库交互
3. failureLimit 设为 1 而非 0，容忍单次网络抖动但不容忍持续性故障

**改进项**
| 项 | 负责人 | 状态 |
|---|---|---|
| 在 CI 增加对该接口的契约测试 | - | 待办 |
| 把分析窗口从 30s 延长到 60s | - | 待办 |
| 评估是否需要接入真实流量做压测 | - | 待办 |
```

**为什么这份材料值钱**：它证明了你不是「配置了金丝雀」，
而是**理解金丝雀为什么能拦住问题、以及它可能拦不住什么**。

---

## 八、本仓库刻意没做的部分

诚实地列出边界，比假装完备更可信：

| 项 | 为什么没做 | 生产该怎么做 |
|---|---|---|
| Secret 管理 | 仓库公开，且方案依赖具体基础设施 | External Secrets Operator + Vault/KMS |
| 镜像签名 | 需要额外的密钥/身份基础设施 | cosign keyless + Kyverno 准入校验 |
| 多集群/容灾 | 超出 demo 范围 | 双活或多活，配合 DNS 故障转移 |
| 数据库高可用 | 单副本足够演示 | 云托管 RDS 或 CloudNativePG Operator |
| 备份与 PITR | 同上 | 定期全量 + WAL 归档，定期演练恢复 |
| Service Mesh | 引入成本高，且非必需 | Istio/Linkerd 做精确流量切分与 mTLS |
| 合规审计日志 | 需要对接内部系统 | 不可篡改存储 + 定期归档 |

**面试时的说法**：「这些我都知道该怎么做，但这个 demo 的重点是
把主链路跑通并讲清楚每个决策的取舍。真要我上生产，
我会先补 Secret 管理和镜像签名这两块，因为它们是安全底线；
容灾和多集群可以后面再说。」
