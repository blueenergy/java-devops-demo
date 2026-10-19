# 05 Kubernetes：三类探针各管一段，配错任何一类都会把故障放大

> 一句话结论：这份清单里最贵的知识不是「怎么配」，而是「配错会怎样」—— `liveness` 里加一个数据库检查，能把一次数据库抖动放大成一次全站故障；`preStop` 少一个 `sleep`，能让每次滚动更新都稳定产生 502。

## 清单结构

| 文件 | 内容 |
|---|---|
| `namespace.yaml` | 命名空间 + Pod Security Admission 标签 |
| `serviceaccount.yaml` | 专用 ServiceAccount，关闭 token 自动挂载 |
| `configmap.yaml` / `secret.yaml` | 非敏感配置 / 占位 Secret（生产走 External Secrets） |
| `postgres.yaml` | **演示用**单副本 PostgreSQL（无流复制、无备份） |
| `service.yaml` | 三个 Service：`-stable`、`-canary`、统一入口 |
| `rollout.yaml` | Argo Rollouts 的 `Rollout`（替代 Deployment） |
| `hpa.yaml` / `pdb.yaml` | 自动扩缩容 / 中断预算 |
| `networkpolicy.yaml` | default-deny + 按需放行 |
| `servicemonitor.yaml` | Prometheus Operator 的抓取声明（base 里默认注释掉） |

环境差异全部通过 `overlays/`（`dev` / `ci` / `prod`）表达，`base` 里不出现任何环境特定值。

## Rollout：为什么不用 Deployment

原生 `Deployment` 的 `strategy` 只支持 `RollingUpdate` 与 `Recreate`。`RollingUpdate` 会立刻把新版本铺到所有副本，**没有「先放 20% 流量观察」这一步**。`Rollout` 是 `Deployment` 的超集：同样管理 ReplicaSet，但支持分步放量、暂停、以及在每一步插入自动分析。

```yaml
steps:
  - setWeight: 20
  - pause: { duration: 30s }
  - analysis:
      templates: [{ templateName: order-demo-api-smoke }]
      args: [{ name: service-name, value: order-demo-api-canary }]
  - setWeight: 50
  - pause: { duration: 30s }
  - setWeight: 100
```

| 设计点 | 理由 |
|---|---|
| 先 `setWeight` 再 `pause` 30s | 让新 Pod 有机会暴露问题。太短会让分析跑在冷启动阶段（误判），太长会拖慢发布节奏 |
| 分析失败 → 自动回滚 | 银行场景下 MTTR 是核心指标，能自动回滚的就不该等人 |
| **不**显式设 `scaleDownDelaySeconds` | 无 `trafficRouting` 时 Argo 的准入校验会拒绝这个字段（`can only be used with traffic routing`）。金丝雀期间 stable 本身保持满编，兜底已由副本策略保证 |
| `revisionHistoryLimit: 5` | 保留 5 个历史版本，够回滚且不堆积 RS |

### `setWeight` 在没配 trafficRouting 时是按副本数近似

**本配置没有配 `trafficRouting`**（那需要 Istio / Nginx Ingress / Gateway API 之一）。在没有流量路由器的情况下，`setWeight` 无法真正按百分比切分流量，它只能按**副本数比例**近似：

| 配置 | 实际结果 |
|---|---|
| `replicas: 3` + `setWeight: 20` | 1 个 canary 副本对 2 个 stable 副本，**真实比例约 33%**，不是 20% |
| `replicas: 3` + `setWeight: 50` | 3 个副本无法对半分，实际仍是 1:2 或 2:1 |

生产环境要精确控流**必须接入 `trafficRouting`**。这里选择不接，是为了让 demo 不依赖特定的 Ingress 实现，能在任何 kind 集群里跑起来。

分析的判定用 **Job 探测**（`k8s/base/analysis-job.yaml`）而不是 Prometheus 查错误率：演示环境金丝雀期间**没有真实用户流量**，错误率的分母是 0，PromQL 返回空集，`AnalysisRun` 判 `Inconclusive` 并一直重试到超时 —— 表面看「回滚了」，实际是「探测没结果所以超时了」。两者的故障定位路径和回滚触发条件完全不同。

## 三类探针的分工

| 探针 | 路径 | 参数 | 回答的问题 | 失败后的动作 |
|---|---|---|---|---|
| `startupProbe` | `/actuator/health/liveness` | `period 5s` × `failureThreshold 24`（≤120s） | 启动完了没？ | 继续等，**期间禁用另两个探针** |
| `livenessProbe` | `/actuator/health/liveness` | `period 10s` × `failureThreshold 3`（30s） | 进程是不是卡死了？ | **重启容器** |
| `readinessProbe` | `/actuator/health/readiness` | `period 5s` × `failureThreshold 3`（15s） | 现在能不能接流量？ | 从 Endpoints **摘除**，不重启 |

路径与 `application.yml` 里的健康分组一一对应：

```yaml
liveness:  { include: livenessState,ping }      # 不含 db
readiness: { include: readinessState,db }       # 含 db
```

### liveness 绝不能包含数据库检查

`livenessProbe` 失败三次（约 30 秒）就会触发 kubelet **重启容器**。如果 liveness 里包含数据库检查，数据库抖动几分钟时的连锁反应是：

```
数据库抖动
   → 所有 Pod 的 liveness 同时失败
   → 所有 Pod 被同时重启
   → 重启期间没有任何 Pod 在服务
   → 一次「数据库变慢」放大成一次「全站不可用」
```

而且重启**对数据库故障毫无帮助**：新起来的 Pod 一样连不上数据库，于是进入「重启 → 起不来 → 再重启」的循环。真正该做的是**把 Pod 从 Endpoints 摘除，等数据库恢复** —— 这正是 readiness 的职责。

所以分工必须严格：**liveness 只管进程本身**，**readiness 才管下游依赖**。`OrderApiIT` 里有一条断言专门守住这条线：

```java
assertThat(response.getBody()).doesNotContain("\"db\"");
```

`show-details: always` 是演示配置（方便直接看到各组件状态），生产建议改 `when-authorized`，否则健康详情本身就是信息泄漏面。

### startupProbe 为什么必要

启动慢的应用（JVM 冷启动 + Flyway 迁移 + 连接池预热，几十秒很正常）**没有 `startupProbe` 就会被 `livenessProbe` 反复杀死**：

```
应用启动需要 60 秒
   → liveness 在第 30 秒就判定失败
   → 容器被杀，重启
   → 又要 60 秒，又在第 30 秒被杀
   → 永远起不来的死循环
```

`startupProbe` 成功之前，kubelet **不评估** liveness 和 readiness，等于把「启动期」从另外两个探针的管辖范围里摘出来。

注意它用的是 **liveness 端点**而不是 readiness 端点：readiness 包含数据库检查，而启动期数据库可能刚好不可用 —— 用它做启动判定会让「依赖暂时不可用」变成「Pod 永远起不来」。

## 优雅停机：`preStop` 与 `terminationGracePeriodSeconds`

```yaml
terminationGracePeriodSeconds: 45          # Pod 级
lifecycle:
  preStop:
    exec: { command: ["sh", "-c", "sleep 10"] }
```

### `preStop: sleep 10` 解决什么问题

Pod 被删除时，k8s 会**同时**做两件事：

```
kubectl delete pod / 滚动更新
   ├── 从 Service 的 Endpoints 摘除该 Pod
   └── 发送 SIGTERM 给容器
```

这两件事是**并行**的，而 `kube-proxy` 刷新 iptables 规则需要时间。在这个窗口里，**流量仍然会被转发到正在关闭的 Pod** —— 因为 iptables 里那条规则还没被删掉。

`preStop` 让应用在收到 SIGTERM 前多等一会儿，等 Endpoints 变更传播完。不配它的后果是：滚动更新期间**几乎必然出现偶发 502**。

### `terminationGracePeriodSeconds` 必须大于 `timeout-per-shutdown-phase`

| 参数 | 位置 | 值 |
|---|---|---|
| `terminationGracePeriodSeconds` | `rollout.yaml`（Pod 级） | 45s |
| `spring.lifecycle.timeout-per-shutdown-phase` | `application.yml` | 30s |
| `server.shutdown` | `application.yml` | `graceful` |

```
t=0    收到 SIGTERM（preStop 已先执行完 10s）
t=0..30 优雅停机：停止接收新请求，等在途请求处理完
t=45   kubelet 发 SIGKILL，强杀
```

**如果它小于 30s**，k8s 会在应用还没处理完在途请求时就发 SIGKILL，`preStop` 的 10 秒还没算进去，实际留给优雅停机的窗口更小。表现为滚动更新期间的偶发 502，而且因为 `preStop` 固定占掉 10 秒，这个问题会**稳定复现**。`docker-compose.yml` 里的 `stop_grace_period: 35s` 是同一个约束在 compose 里的对应物。

## resources 与 QoS：注释的意图与实际值

| 概念 | 作用 |
|---|---|
| `requests` | **调度依据**。scheduler 按它找有足够余量的节点；也是 HPA 计算利用率的基准 |
| `limits` | **运行时上限**。CPU 超限被限流（throttle），内存超限被 OOMKill |

### QoS class：当前配置实际是 Burstable

`rollout.yaml` 的注释写着「两者都设且相等时，Pod 会获得 Guaranteed QoS …… 用 Guaranteed」。但**判定 Guaranteed 的条件是每个容器（含 init 容器）的每种资源的 `request` 与 `limit` 完全相等**，而当前的值并不相等：

| 位置 | requests | limits | 实际 QoS |
|---|---|---|---|
| `base/rollout.yaml` | cpu 250m / mem 512Mi | cpu 1 / mem 768Mi | **Burstable** |
| `overlays/prod` | cpu 500m / mem 768Mi | cpu 2 / mem 1Gi | **Burstable** |
| `overlays/dev` / `ci` | cpu 100m / mem 384Mi | cpu 500m / mem 512~640Mi | **Burstable** |

要真正拿到 Guaranteed，得把两组值写成完全一样（例如两边都填 `cpu: 250m / memory: 768Mi`）。这不是笔误，而是需要明确的取舍：

| QoS | 优点 | 代价 |
|---|---|---|
| `Guaranteed` | 最不容易被驱逐（节点内存压力时**最后**被杀） | 失去突发能力：CPU 被限死在 request 上，尖峰只能靠限流扛 |
| `Burstable`（当前） | 可以突发到 limit，平均资源利用率更高 | 节点内存压力时**优先**被驱逐 |

订单服务对延迟敏感、内存需求平稳 —— 注释的判断是倾向 Guaranteed。当前值没做到这一点，属于「注释与配置不一致」，值得在上生产前对齐（要么改值，要么改注释）。

另外注意 `JAVA_TOOL_OPTIONS` 里的 `-XX:MaxRAMPercentage=75`：base 的 memory limit 是 768Mi，堆上限约 576Mi，剩下约 190Mi 留给元空间、线程栈、直接内存。**调小 limit 之前先算这笔账**，否则会出现「堆没满但 Pod 被 OOMKilled」这种很难理解的现场。

### HPA 用 CPU 的局限

```yaml
minReplicas: 2
maxReplicas: 8
metrics: [{ type: Resource, resource: { name: cpu, target: { type: Utilization, averageUtilization: 70 } } }]
```

| 局限 | 机制 |
|---|---|
| **滞后于真实压力** | 等 CPU 上来了，用户已经开始排队；扩容又要 30-60 秒（拉镜像 + JVM 启动） |
| **JVM 的 CPU 与吞吐非线性** | JIT 预热后同样请求量下 CPU 会**下降**，HPA 可能误判为「压力减轻」而缩容；缩容后 CPU 又上来，形成抖动 |
| 目标 70% 而非 90% | 留出余量。设太高会导致扩容触发时已接近饱和 |

`behavior` 的两个窗口是防抖动的关键：

| 方向 | 配置 | 理由 |
|---|---|---|
| 扩容 | `stabilizationWindowSeconds: 15`，一次最多翻倍 | 扩容要快 |
| 缩容 | `stabilizationWindowSeconds: 300`，每分钟最多减 1 个 | 尖峰过去后不要立刻缩，否则下一个尖峰又要重新扩容，而 JVM 冷启动期间性能最差 |

**更贴近业务的做法是用自定义指标扩缩容**：请求队列深度、P99 延迟、每秒订单创建数。这需要 Prometheus Adapter 把 Prometheus 指标暴露成 k8s 的 custom metrics API。本 demo 没有部署 Adapter（它依赖一整套 Prometheus Operator），所以先用 CPU —— 但知道工具边界比会用工具更重要。

**HPA 与 PDB 的交互**要注意：`minReplicas: 2` 与 PDB 的 `minAvailable: 2` 组合起来，意味着副本数被缩到下限时**任何自愿中断都不被允许**，节点排水会一直阻塞。要么把 `minAvailable` 降到 1，要么把 `minReplicas` 提到 3。

## PDB：保护「自愿中断」，不保护节点宕机

| 中断类型 | 例子 | PDB 是否保护 |
|---|---|---|
| **自愿中断** | `kubectl drain`、集群升级、节点池缩容 | **是** |
| **非自愿中断** | 节点宕机、`OOMKilled`、内核 panic | **否** |

PDB 只约束「主动驱逐」这类 API 调用。所以它是**锦上添花而不是高可用保证** —— 真正的高可用来自多副本 + 反亲和/拓扑分布 + 多可用区。

`minAvailable: 2` 与 `replicas: 3` 的搭配：允许同时只有 1 个 Pod 不可用。集群升级时驱逐会被阻塞直到有 Pod 恢复，**这是刻意的** —— 宁可升级慢，不可服务降级。改成 `minAvailable: 1` 升级会快很多，但任何时刻都可能只剩 1 个 Pod 扛流量。

`overlays/dev` 与 `overlays/ci` 都用 `$patch: delete` 把 PDB 删掉了，原因写在注释里：单副本下 `minAvailable: 2` 永远无法满足，会让任何节点排水操作被**无限阻塞**。这是「直接把生产配置搬到 dev」的典型翻车点。

## Kustomize 与 CRD 的三个坑（都真实踩过）

### 坑三：patch CRD 的容器数组会整体替换，不是按 name 合并

这是三个坑里最危险的一个，因为它**渲染成功、schema 校验也通过**。

```yaml
# 看起来无害：只想改 resources
patches:
  - target: {group: argoproj.io, version: v1alpha1, kind: Rollout, name: order-demo-api}
    patch: |-
      apiVersion: argoproj.io/v1alpha1
      kind: Rollout
      metadata: {name: order-demo-api}
      spec:
        template:
          spec:
            containers:
              - name: order-demo-api
                resources: {requests: {cpu: 100m, memory: 384Mi}}
```

对内置类型（Deployment 等），kustomize 有 OpenAPI 字段元数据，会做**战略合并**，
按 `name` 把字段合进已有容器。但 Rollout 是 CRD —— 没有元数据，
kustomize 退化成 **JSON merge**，而 JSON merge 对数组是**整体替换**。

结果：`image`、`ports`、三类探针、`securityContext`、`envFrom` 全部消失。
`kubectl apply` 时才报：

```
InvalidSpec: The Rollout "order-demo-api" is invalid:
spec.template.spec.containers[0].image: Required value
```

**为什么校验没拦住**：CI 里 kubeconform 必须加 `-ignore-missing-schemas`
（CRD 没有内置 schema），而加了之后 CRD 会被**直接跳过**，
等于 Rollout 从未被验证过。

**正确写法是 JSON6902，精确定位到字段**：

```yaml
    patch: |-
      - op: replace
        path: /spec/template/spec/containers/0/resources
        value: {requests: {cpu: 100m, memory: 384Mi}, limits: {cpu: 500m, memory: 640Mi}}
      - op: add
        path: /spec/template/spec/containers/0/env
        value: [{name: LOG_LEVEL, value: DEBUG}]
```

**并补一道断言**：`scripts/check-rendered-manifests.py` 对渲染后的产物检查
`image` 非空、三类探针齐全、`resources` 与 `securityContext` 存在。
CI 的渲染校验步骤会调用它。这是唯一能在 apply 之前发现这类问题的手段。

**`replicas:` 字段不认 CRD。** kustomization 里的

```yaml
replicas:
  - name: order-demo-api
    count: 3
```

只会去匹配内置工作负载（Deployment/StatefulSet/ReplicaSet/ReplicationController）。
我们的工作负载是 Argo Rollouts 的 `Rollout`（CRD），于是渲染直接失败：

```
Error: resource with name order-demo-api does not match a config with the
following GVK [Deployment.[noVer].[noGrp] StatefulSet... ReplicationController...]
```

修法是用 JSON patch：

```yaml
patches:
  - target:
      group: argoproj.io
      version: v1alpha1
      kind: Rollout
      name: order-demo-api
    patch: |-
      - op: replace
        path: /spec/replicas
        value: 3
```

**`$patch: delete` 必须写准确的资源名。** 它按 apiVersion/kind/name 精确匹配，
写一个不存在的占位名会报 `no matches for Id ...`。删除多个同类资源要逐个写 patch。

**共同点**：这两个错误都只在 `kustomize build` 时暴露，单文件的 schema 校验全绿。
所以 CI 的清单校验分两步：先对 base 做 schema 校验，再**渲染每个 overlay 后校验最终产物**。

## 拓扑分布约束：防止副本全落一个节点

只在 `overlays/prod` 里配，两个 `topologySpreadConstraints`：`topology.kubernetes.io/zone` 与 `kubernetes.io/hostname`，都是 `maxSkew: 1`。

不做这个约束时，调度器可能把 3 个副本全放到一个节点上 —— **那个节点一挂，服务就整体不可用，多副本的意义荡然无存**。

用 `whenUnsatisfiable: ScheduleAnyway`（软约束）而不是 `DoNotSchedule`（硬约束）：硬约束在节点数不足时会让 Pod **永远 Pending**，把一个「分布不均」的问题升级成「服务起不来」。软约束只影响调度偏好。

## NetworkPolicy：default-deny 与极易遗漏的 DNS

```yaml
podSelector: {}          # 选中本 namespace 所有 Pod
policyTypes: [Ingress]   # 不允许任何入站
```

不写任何 NetworkPolicy 时，集群内任意 Pod 都能访问任意其它 Pod —— **一个被攻破的边缘服务可以直接连数据库**。NetworkPolicy 把爆炸半径限制在显式声明的调用关系内。

放行清单（每一条都对应一个真实的调用方）：

| 来源 | 端口 | 为什么需要 |
|---|---|---|
| `ingress-nginx` namespace | 8080 | Ingress Controller 转发的外部流量 |
| 同 namespace 内 `part-of: order-demo` 的 Pod | 8080 | 集群内其它业务服务调用 |
| `component: canary-analysis` 的 Pod | 8080 | 金丝雀分析的探测 Job |
| `monitoring` namespace | 8080 | Prometheus 抓指标 |

第三条特别值得注意：少了它，`AnalysisRun` 的 Job 连不上 canary Service，金丝雀会一直判 `Inconclusive` 直到超时 —— **表面看像「回滚了」，实际是探测根本没打通**。Job 模板里的标签（`app.kubernetes.io/component: canary-analysis`）与这条策略是配套的。

出站侧只放行两条：到 `order-demo-postgres` 的 5432，以及到 `kube-system` 的 **53 端口（UDP + TCP）**。

**DNS 那条是最容易漏的**。漏掉它之后的现象是：所有出站连接失败，报错信息是 `UnknownHostException` —— 看起来像 DNS 服务坏了，实际是自己把 DNS 查询给拦了，排查方向会被完全带偏。

注意 `default-deny-ingress` 只写了 `policyTypes: [Ingress]`，**没有默认拒绝出站**；出站限制只作用于被 egress 策略选中的 Pod（即订单服务）。要做到完整的零信任还需要一条 `default-deny-egress`。

另一个前提：**集群的 CNI 必须支持 NetworkPolicy**。kind 默认的 `kindnet` 不支持，需要 Calico/Cilium。不支持时这些对象会被 API Server 接受但**静默不生效** —— 容易误判「已经加固了」，验证方法是在 Pod 里实际 `curl` 一个本该被拒绝的地址。`overlays/ci` 因此显式删掉了 NetworkPolicy 并写明原因。

## ServiceAccount：`automountServiceAccountToken: false`

| 不这么做的后果 | 机制 |
|---|---|
| 容器内任意代码都能拿到一把访问 API Server 的钥匙 | 默认会把 token 挂到 `/var/run/secrets/kubernetes.io/serviceaccount/token` |
| 用 `default` SA 时，权限是**整个 namespace 所有应用权限的并集** | 同 namespace 的所有 Pod 共用 `default` ServiceAccount |

**本应用不需要访问 k8s API，就不该在容器里挂载 token。** 很多容器逃逸利用链的第一步就是读这个文件。金丝雀分析的 Job 模板里也写了同样的配置 —— 探测任务同样不需要访问 API。

## Pod Security Admission：baseline enforce + restricted warn

```yaml
pod-security.kubernetes.io/enforce: baseline
pod-security.kubernetes.io/warn: restricted
pod-security.kubernetes.io/audit: restricted
```

| 级别 | 拦什么 |
|---|---|
| `baseline` | 特权容器、`hostNetwork`、`hostPID`、以 root 运行等明显危险的配置 |
| `restricted` | 在此之上还要求 `runAsNonRoot` + `seccompProfile` + 丢弃全部 capabilities + 只读根文件系统 |

**从 `baseline` 开始而不是直接 `restricted`**，是渐进策略：

| 做法 | 结果 |
|---|---|
| 直接 `enforce: restricted` | 存量应用往往需要改造，一次性 enforce 会让所有部署失败，团队的第一反应是把标签删掉 |
| `enforce: baseline` + `warn/audit: restricted`（本仓库） | 危险配置被拒绝，不符合 restricted 的只产生**警告**和审计记录 —— 问题被暴露，但不阻塞任何人 |

用平台能力强制安全基线，而不是靠 code review 记住要写 `securityContext` —— **人一定会忘，准入控制器不会**。

`rollout.yaml` 里的 `securityContext` 已经满足 restricted 的要求：

| 层级 | 字段 | 作用 |
|---|---|---|
| Pod | `runAsNonRoot: true` | 拒绝以 root 启动 |
| Pod | `runAsUser` / `runAsGroup` / `fsGroup: 10001` | 与镜像里 `adduser -u 10001` 对齐 |
| Pod | `seccompProfile: RuntimeDefault` | 运行时默认的系统调用过滤 |
| 容器 | `allowPrivilegeEscalation: false` | 禁止 setuid 提权 |
| 容器 | `readOnlyRootFilesystem: true` | 根文件系统只读 |
| 容器 | `capabilities: drop: ["ALL"]` | 丢弃全部 Linux capabilities |

### 只读根文件系统必须配 `/tmp` emptyDir

`readOnlyRootFilesystem: true` 之后，**JVM 仍然需要写临时文件**（`java.io.tmpdir` 默认就是 `/tmp`，JIT 编译产物、类数据共享、某些库的临时文件都走这里）。不给一个可写的 `/tmp`，应用会在启动或运行中途因为 `Read-only file system` 失败。

```yaml
volumeMounts: [{ name: tmp, mountPath: /tmp }]
volumes: [{ name: tmp, emptyDir: {} }]
```

`emptyDir: {}` 是合适的：随 Pod 生命周期创建和销毁，不需要持久化，也不占节点上的持久存储。

## 部署与镜像：按 digest 而不是 tag

`overlays/prod` 用 `digest: sha256:...`（占位，由 CI 替换为真实值）。

| 维度 | tag | digest |
|---|---|---|
| 可变性 | 可变（`v1.2.3` 下周可能指向别的内容） | 不可变（内容的 SHA256） |
| 可审计性 | 三个月后回查「当时上线的是哪份代码」答不上来 | 有确定答案 |
| 可回滚性 | 回滚到 `v1.2.2` 时拉到的可能已不是当初那个 | 确定 |

受监管行业（银行、证券）里这是审计的硬性要求。**用 digest 时 `newTag` 必须省略** —— 同时指定 tag 和 digest 会直接报错。`overlays/dev` 用固定 tag（迭代快优先），`overlays/ci` 用 `kind load` 预载的 `:ci` 标签（绕过 registry）。

## 排错速查

| 现象 | 大概率原因 |
|---|---|
| Pod 反复重启，`Reason: OOMKilled`、`exitCode: 137` | `-XX:MaxRAMPercentage` 没生效，堆按宿主机内存算 |
| Pod 永远 `NotReady`，探针报 404 | 探针路径对应的健康分组在 `application.yml` 里没定义 |
| Pod 卡在 `CreateContainerConfigError` | `envFrom` 引用的 ConfigMap / Secret 不存在 |
| Pod 启动失败，报以 root 运行 | 镜像里少了 `USER app`，与 `runAsNonRoot: true` 冲突 |
| 应用启动报 `Read-only file system` | `readOnlyRootFilesystem: true` 但没给 `/tmp` 挂 emptyDir |
| 滚动更新期间稳定出现 502 | `preStop` 缺失，或 `terminationGracePeriodSeconds` 小于优雅停机超时 |
| 节点排水一直阻塞 | PDB 的 `minAvailable` 与当前副本数冲突（单副本 + `minAvailable: 2`） |
| 部署成功但零流量 | Service 的 selector 匹配不到 Pod 标签；`scripts/check-k8s-manifests.py` 能在 CI 里提前发现 |
| 所有出站连接报 `UnknownHostException` | NetworkPolicy 放行了目标端口但漏了 DNS（53/UDP+TCP） |
| 以为做了网络隔离但实际没有 | CNI 不支持 NetworkPolicy（如 kind 默认的 kindnet），对象被接受但静默不生效 |
| 金丝雀「回滚」了但日志里是 `Inconclusive` | 分析用 Prometheus 判定而金丝雀期间没有真实流量，分母为 0 |
