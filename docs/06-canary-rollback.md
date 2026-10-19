# 金丝雀发布与自动回滚：完整剧本

> 这是整个仓库最核心的一篇。它不只是「怎么配」，而是**为什么这样配**，
> 以及每一步失败时会发生什么。
>
> 真实执行记录见 GitHub Actions 的 `CD` workflow，选 `canary-rollback` 场景运行一次即可复现。

---

## 一、先厘清三个容易混淆的概念

| 策略 | 流量切换方式 | 回滚速度 | 资源成本 | 适用场景 |
|---|---|---|---|---|
| **滚动更新** | 逐批替换，新旧混合 | 中 | 低 | 常规发布 |
| **蓝绿** | 一次性全切 | **最快**（切回旧环境） | **高**（双倍资源） | 数据库变更等高风险发布 |
| **金丝雀** | 按比例逐步放量 | 快 | 中 | 大多数生产发布 |

**关键区别**：滚动更新**没有「观察期」**——新版本铺开的过程中已经在接真实流量了，
发现问题时影响面已经是全局的。金丝雀的价值在于**把观察期显式化**：
先放 20%，确认没问题再继续。

---

## 二、Argo Rollouts 的核心机制

### Rollout 与 Deployment 的关系

`Rollout` 是 `Deployment` 的**超集**。它同样通过 ReplicaSet 管理 Pod，
但额外支持分步放量、暂停、以及在每一步插入自动分析。

```yaml
strategy:
  canary:
    stableService: order-demo-api-stable   # 指向稳定版本的 Pod
    canaryService: order-demo-api-canary   # 指向金丝雀版本的 Pod
    steps:
      - setWeight: 20
      - pause: {duration: 30s}
      - analysis:
          templates: [{templateName: order-demo-api-smoke}]
      - setWeight: 50
      - pause: {duration: 30s}
      - setWeight: 100
```

### 回滚为什么快

Argo 通过修改**两个 Service 的 selector** 来导流，而不是去改 Pod：

```
正常状态：  stable ──▶ ReplicaSet-v1 (3 pods)
            canary ──▶ ReplicaSet-v2 (1 pod, 20%)

回滚瞬间：  stable ──▶ ReplicaSet-v1 (3 pods)   ← selector 切回来，秒级
            canary ──▶ (空)
```

**回滚快的关键是「金丝雀期间 stable 的副本数一直保持满编」**，不是延迟缩容。
发布进行中时，stable ReplicaSet 始终是完整容量，canary 只是额外多加几个 Pod；
中止发布只需把 selector 切回去，**不需要等新 Pod 启动** —— 这是 MTTR 的核心。

> 补充一个容易误解的点：在**没有配置 trafficRouting** 时，
> Argo Rollouts 会拒绝显式设置 `canary.scaleDownDelaySeconds`
> （`Canary scaleDownDelaySeconds can only be used with traffic routing`）。
> 因为该字段的语义是「流量切完后延迟多久缩容旧 ReplicaSet」，
> 没有流量路由器时无从谈起。所以本仓库没有显式设置它。
>
> 这意味着**全量发布完成之后**，旧 ReplicaSet 会被缩容；
> 此时若要回滚，需要按旧 revision 重新拉起 Pod（仍比重新构建快得多，
> 但要等容器启动）。金丝雀过程中中止则没有这个代价。

---

## 二点五、安装 Argo Rollouts 的两个坑

**坑一：它不是 GitHub Action。** 写成 `uses: argoproj/argo-rollouts@v1.10.1` 会在
流水线启动时直接报 `Can't find 'action.yml'`，且错误信息不会提示你「这不是一个 Action」。
正确做法是下载官方 `install.yaml` 用 `kubectl` 应用。

**坑二：`kubectl apply` 装不上它的 CRD。**

```
CustomResourceDefinition.apiextensions.k8s.io "rollouts.argoproj.io" is invalid:
metadata.annotations: Too long: may not be more than 262144 bytes
```

客户端 apply 会把整个清单写进 `kubectl.kubernetes.io/last-applied-configuration`
注解，而注解上限是 256 KB。Rollout 的 CRD 超过这个大小。

修法是服务端 apply：

```bash
kubectl apply --server-side --force-conflicts -n argo-rollouts \
  -f ".../install.yaml"
```

服务端 apply 由 API Server 管理字段归属，不写 last-applied 注解。
**这是安装大型 CRD 的通用做法**，不限于 Argo Rollouts。

另外别忘了单独安装 `kubectl-argo-rollouts` 插件 —— CRD 只让集群认识资源，
`kubectl argo rollouts ...` 这条子命令来自独立的插件，不装会报 `unknown command`。

**坑三：Argo 的准入校验无法离线预检。**

真实踩到的一条：

```
spec.strategy.scaleDownDelaySeconds: Invalid value: 30:
Canary scaleDownDelaySeconds can only be used with traffic routing
```

这类规则写在 Argo Rollouts 的控制器的**准入 webhook** 里，
既不在 CRD 的 OpenAPI schema 中，也不是 kubeconform 能查的东西。
所以：

- 渲染通过、schema 校验通过，不代表能 apply
- **改 Rollout / AnalysisTemplate 的 spec 必须有一个真实集群来试**，
  哪怕是一次性的 kind

这也是 CD 流水线存在的意义之一：它就是一个随时可用的、用完即弃的验证集群。

## 三、一个必须说清楚的局限：权重是近似的

**本配置没有配 `trafficRouting`。** 这意味着 `setWeight: 20` 无法真正按百分比切分流量，
它只能按**副本数比例**近似：

```
replicas: 3
setWeight: 20  →  实际是 1 个 canary 副本对 2 个 stable 副本 ≈ 33%
setWeight: 50  →  实际是 2 对 1 ≈ 67%（还受 maxSurge/maxUnavailable 影响）
```

精确控流需要接入以下之一：

| 方案 | 配置方式 | 代价 |
|---|---|---|
| Istio | `trafficRouting.istio.virtualService` | 需要 Service Mesh，运维成本高 |
| Nginx Ingress | `trafficRouting.nginx.ingress` | 需要 Ingress 支持 canary annotation |
| Gateway API | `trafficRouting.plugins` | 需要 Gateway API 实现 |

**面试时的说法**：「我没配 trafficRouting，因为那会引入对特定 Ingress 实现的依赖，
让 demo 没法在任何 kind 集群里跑起来。但我知道这个局限 ——
副本数近似意味着放量比例的精度取决于副本总数，
副本少的时候（比如 3 个）误差会很大。生产环境要精确控流必须接 Istio。」

**主动说出工具的边界，比等面试官问出来强得多。**

---

## 四、场景 A：金丝雀发布成功

### 触发方式

```bash
# ci-release overlay 只把 image tag 从 ci 换成 ci-v2
kubectl apply -k k8s/overlays/ci-release
```

生产里触发金丝雀最自然的就是**镜像更新**——pod template 变了，
Argo 就会创建新 ReplicaSet 并按 `steps` 走。

**为什么不用 `kubectl argo rollouts restart`**（这是实测踩到的坑）：

```bash
kubectl argo rollouts restart order-demo-api -n order-demo
```

它设置的是 Rollout 的 `spec.restartAt` 字段 —— **只重启 Pod，
不创建新 ReplicaSet / revision**，因此金丝雀步骤根本不会执行。
实测证据：

```
restart 之后立刻采样：step=6（已是终态）phase=Progressing
42 秒后：Healthy
```

而配置里两个 `pause` 各 30 秒，最短也要 60 秒 —— **时间上根本不够**，
说明步骤一步都没走。它演示的是「滚动重启」，不是「金丝雀发布」。

**为什么不用 `kubectl set env`**：

```bash
kubectl -n order-demo set env rollout/order-demo-api DEPLOY_REVISION=...
# error: no kind "Rollout" is registered for version "argoproj.io/v1alpha1"
#        in scheme "pkg/scheme/scheme.go:28"
```

`set env` 需要把对象解码成强类型 struct，而 CRD 不在 kubectl 的客户端 scheme 里。

**这里有个高频误区**：只改 ConfigMap 是**不会**触发 Rollout 的。
Argo Rollouts 与原生 Deployment 一样，只监听 **pod template** 的变化。
改完 ConfigMap 发现没有任何反应，会以为 Rollout 坏了，实际是它根本没理由重启 Pod。

> 演示用的 `ci-v2` 与 `ci` 是**同一份构建产物**打了两个 tag，
> 所以演示的是**发布流程本身**，不是代码差异。真实场景里这一步对应新版本镜像。

### 观察到的过程

**真实输出**（2026-10-19，CD run 39231690824，原样摘录）：

```
[1]..[7]   step=0  updated=1  ready=1  phase=Progressing   setWeight 20，新 Pod 就绪
[8]..[13]  step=1  updated=1  ready=2  phase=Paused        pause 30s
[14]..[20] step=2  updated=1  ready=2  phase=Progressing   AnalysisRun 探测
[21]..[24] step=4  updated=1  ready=2  phase=Paused        setWeight 50 + pause
✓ 金丝雀发布完成，已全量切换到新版本
```

注意 `ready=1 → 2`：stable 副本一直保持满编，canary 是**额外**起的 Pod。
这正是「回滚只需切 selector」的原因。

> **注意不要打印 `.status.canary.weights`**：那是 `trafficRouting` 才填充的字段，
> 没有流量路由器时它永远是空的（打印出来只有 `%`）。改用
> step / updated / ready 三个在副本比例模式下也有意义的字段。
>
> 这个细节值得记住：**监控面板上某个指标一直为空时，先确认它在当前配置下
> 是否本来就该有值**，而不是急着查「为什么采集不到」。

### 判定逻辑

`AnalysisTemplate` 每 10 秒跑一次冒烟任务，共 3 次，`failureLimit: 1`：

```sh
# 1) 就绪探针
curl -sf "$BASE/actuator/health/readiness"

# 2) 关键业务路径：写一笔订单再读回来
ORDER=$(curl -sf -X POST "$BASE/api/v1/orders" -d '{"symbol":"CANARY",...}')
ID=$(echo "$ORDER" | sed -n 's/.*"id"[[:space:]]*:[[:space:]]*\([0-9]*\).*/\1/p')
curl -sf "$BASE/api/v1/orders/$ID"

# 3) 撤单，验证状态机在真实数据库上工作
curl -sf -X POST "$BASE/api/v1/orders/$ID/cancel"

echo -n ok
```

**为什么探测业务接口而不只是健康检查**：应用完全可能「活着但业务全挂」——
数据库连接池耗尽、迁移脚本没跑、下游依赖不可用。
`/actuator/health` 返回 UP 不代表订单能创建成功。
金丝雀要验证的是**业务可用性**，不是进程存活。

---

## 五、场景 B：故障注入触发自动回滚

### 故障怎么注入

```bash
kubectl apply -k k8s/overlays/ci-fault
```

`k8s/overlays/ci-fault` 在 `ci` 的基础上，用 JSON6902 给 pod template 的容器
加上 `FAULT_ENABLED=true` / `FAULT_ERROR_RATE=0.5`：

```yaml
patches:
  - target: {group: argoproj.io, version: v1alpha1, kind: Rollout, name: order-demo-api}
    patch: |-
      - op: add
        path: /spec/template/spec/containers/0/env
        value:
          - {name: FAULT_ENABLED, value: "true"}
          - {name: FAULT_ERROR_RATE, value: "0.5"}
```

### 为什么不用 `kubectl set env` 或 merge patch

| 方式 | 结果 |
|---|---|
| `kubectl set env rollout/...` | `no kind "Rollout" is registered ... in scheme` —— CRD 不在客户端 scheme 里 |
| `kubectl patch --type=merge` | 能执行，但 merge 对数组是整体替换，`containers` 被补丁里的小数组覆盖，image/探针全丢 |
| `kubectl apply -k <overlay>` | **正确**。apply 走 unstructured 路径，对 CRD 完全支持，且变更可进 Git、可复现 |

### 两个关键设计

**第一，故障写进 pod template，而不是只改 ConfigMap。**

只改 ConfigMap 有两个问题：它**不会触发新 revision**（Rollout 只监听 pod template），
而且共享的配置会让后续任何重建的 Pod 都带上故障。

写进 pod template 后，故障只存在于新 ReplicaSet 的 Pod 里，
正在服务的 stable Pod（在变更前创建，`envFrom` 的值已固化进容器环境）不受影响 ——
这才是真实的金丝雀语义：**坏版本被拦在放量阶段，而不是把线上一起拖下水**。

**第二，故障注入刻意绕开 `/actuator/**`。**

```java
// FaultInjectionFilter
protected boolean shouldNotFilter(HttpServletRequest request) {
    return !properties.isActive()
            || !request.getRequestURI().startsWith("/api/");   // 只对 /api/ 生效
}
```

如果注入的故障也影响 `/actuator/health/readiness`，Pod 会一直 NotReady，
Kubernetes 根本不会把流量切给它——金丝雀分析还没开始跑，
发布就卡在「等待副本就绪」上了。

那演示的就不再是**「金丝雀分析发现错误率超标并自动回滚」**，
而是**「新版本启动失败」**。两者的故障定位路径、回滚触发机制完全不同：

| | 启动失败 | 金丝雀分析失败 |
|---|---|---|
| 触发者 | kubelet（readiness 探针） | Argo Rollouts（AnalysisRun） |
| Rollout 状态 | `Progressing` 卡住 → 超时 `Degraded` | `Progressing` → `Degraded`（分析判定） |
| 证据 | Pod 事件 `Unhealthy` | AnalysisRun 状态 `Failed` |
| 回滚时机 | `progressDeadlineSeconds` 超时后 | 分析失败后立即 |

### 观察到的过程

**真实输出**（同一次 CD run，原样摘录）：

```
[1]..[8]   rollout=Progressing analysisRun=Successful   ← 上一次（场景 A）的分析结果仍在
[9]..[14]  rollout=Paused      analysisRun=Successful   ← pause 30s
[15]..[18] rollout=Progressing analysisRun=Running      ← 新版本的探测任务启动
[19]       rollout=Degraded    analysisRun=Failed       ← 连续失败达到 failureLimit，自动中止
✓ 金丝雀分析判定失败，Rollout 已中止
```

`analysisRun=Successful` 是**上一次发布的**分析记录 —— 每个 revision 有独立的
AnalysisRun，所以观察时要取「最新的那一条」，不能简单假设只有一个。

整个过程约 **90 秒**，其中人工介入 **0 秒**。

### 回滚证据

```bash
kubectl -n order-demo describe rollout order-demo-api | grep -A3 -iE 'RolloutAborted'
```

**真实输出**（本次 CD run，原样摘录）：

```
Degraded
    Reason:                RolloutAborted
  Message:                 RolloutAborted: Rollout aborted update to revision 2:
                           Step-based analysis phase error/failed:
                           Metric "smoke-test" assessed Failed due to
                           failed (2) > failureLimit (1)
  Phase:                   Degraded

  Warning  RolloutAborted  1s  rollouts-controller
           Rollout aborted update to revision 2: Step-based analysis phase
           error/failed: Metric "smoke-test" assessed Failed due to
           failed (2) > failureLimit (1)
```

这三行就是面试时要展示的核心证据：

| 字段 | 说明 |
|---|---|
| `Reason: RolloutAborted` | 不是超时、不是手工中止，是**分析判定失败**触发的中止 |
| `failed (2) > failureLimit (1)` | 第 2 次探测失败时达到阈值 —— 时序与配置完全对得上 |
| `update to revision 2` | 被中止的是新版本，`revision 1` 继续服务 |

随后脚本还验证了稳定性：

```bash
kubectl exec <stable-pod> -- wget -qO- http://localhost:8080/actuator/health/readiness
# ✓ 稳定版本仍在正常服务
```

**只看 Rollout 状态是不够的** —— `Degraded` 只说明发布中止了，
不代表服务已恢复。必须实际打一次接口确认，这是「回滚完成」与「回滚成功」的区别。

```bash
kubectl -n order-demo get analysisrun
```

```
NAME                                STATUS   AGE
order-demo-api-smoke-7d9f8b6c4-1    Failed   4m
```

```bash
kubectl -n order-demo describe analysisrun order-demo-api-smoke-7d9f8b6c4-1 | tail -20
```

```
Status:
  Metric Results:
    smoke-test:
      Count:  2
      Failed: 2
      Measurements:
        Finished At:  2026-10-19T06:12:33Z
        Phase:        Failed
        Value:        failed: 创建订单未返回 id, 响应={"code":"INJECTED_FAULT",...}
  Phase:  Failed
Events:
  Type     Reason              Message
  ----     ------              -------
  Warning  AnalysisFailed      Metric "smoke-test" assessed Failed due to failed (2) > failureLimit (1)
  Normal   RolloutAborted      Rollout aborted update to revision 2
```

**`RolloutAborted: Rollout aborted update to revision 2`** ——
这一行就是面试时要展示的证据。

### 最后一步：验证服务真的恢复了

```bash
STABLE_POD=$(kubectl -n order-demo get pods -l app.kubernetes.io/name=order-demo-api \
  -o jsonpath='{.items[0].metadata.name}')
kubectl -n order-demo exec "$STABLE_POD" -- \
  wget -qO- http://localhost:8080/actuator/health/readiness
```

**只看 Rollout 状态是不够的。** `Degraded` 只说明「发布中止了」，
不代表「服务已恢复」。必须实际打一次接口确认——
这是「回滚完成」与「回滚成功」的区别。

---

## 六、为什么金丝雀分析用 Job 而不是 Prometheus

这是本仓库最重要的一个设计决策，也是最容易讲错的地方。

### 直觉上的做法

```promql
sum(rate(http_server_requests_seconds_count{status=~"5.."}[1m]))
/ sum(rate(http_server_requests_seconds_count[1m])) > 0.05
```

看起来完全合理。**但在没有真实流量的环境里它会失效。**

### 失效机制

```
金丝雀期间没有真实用户流量
        ↓
错误率的分母 ≈ 0
        ↓
PromQL 返回空集
        ↓
Argo Rollouts 判定 Inconclusive（不确定）
        ↓
Inconclusive ≠ 失败，分析继续重试
        ↓
一直重试到 progressDeadlineSeconds 超时
        ↓
Rollout 转为 Degraded
```

**表面现象是「发布失败了、回滚了」，看起来符合预期。
但实际机制完全不同：**

| | 分析判定失败 | 分析超时 |
|---|---|---|
| 触发条件 | 指标超标 | 拿不到数据 |
| 耗时 | 秒级（`interval × failureLimit`） | `progressDeadlineSeconds`（默认 10 分钟） |
| 调参方向 | `failureLimit`、阈值 | 抓取配置、流量来源 |
| 根因 | 新版本有缺陷 | **探测链路本身没通** |

面试时把这两者讲混，会立刻暴露「照着文档配了但没真正跑过」。

### 真实生产环境的判断

**在有真实流量、有 Prometheus 的环境里，Prometheus 判定才是主路径** ——
它能反映真实的用户影响，而 Job 探测只能验证「接口能通」。

所以本仓库提供了两份模板：

| 文件 | 判定方式 | 适用场景 |
|---|---|---|
| `k8s/base/analysis-job.yaml` | Job 退出码 | **任何环境**，尤其无流量时 |
| `k8s/rollout/analysis-prometheus.yaml` | PromQL 查询 | 有真实流量 + Prometheus |

Prometheus 版里三个指标缺一不可：

```yaml
- name: error-rate        # 错误率
- name: latency-p99       # 延迟分位数
- name: traffic-present   # 正向流量指标 ← 防「假绿」
```

**第三条最容易被忽略**：新版本可能因为配置错误完全不接流量，
此时错误率是 0、延迟是 0，前两条都会通过——但服务实际上已经死了。
**「没有错误」和「没有流量」必须能区分开。**

---

## 七、手动回滚

自动回滚之外，也要会手动操作：

```bash
# 查看发布历史
kubectl argo rollouts history rollout order-demo-api -n order-demo

# 立即中止当前发布并回滚
kubectl argo rollouts abort rollout order-demo-api -n order-demo

# 回滚到上一个版本
kubectl argo rollouts undo rollout order-demo-api -n order-demo

# 回滚到指定 revision
kubectl argo rollouts undo rollout order-demo-api -n order-demo --to-revision=3

# 查看回滚是否真的生效
kubectl argo rollouts status rollout order-demo-api -n order-demo
```

**`abort` 与 `undo` 的区别**：`abort` 中止当前正在进行的发布并回到 stable；
`undo` 是把 stable 本身换成一个历史版本。搞混会在故障时做错操作。

---

## 八、如果没有 Argo Rollouts

不是所有公司都用 Argo。被问到时要能答出降级方案：

### 方案一：原生双 Deployment（最轻）

```yaml
# 两个 Deployment：app-stable（3 副本）与 app-canary（1 副本）
# 共用一个 Service，靠副本数比例近似分流
```

```bash
# 放量：调整副本数
kubectl scale deployment app-canary --replicas=1   # ≈25%
kubectl scale deployment app-canary --replicas=2   # ≈40%

# 回滚：把 canary 缩到 0
kubectl scale deployment app-canary --replicas=0
```

**缺点**：比例不精确；**没有自动分析**——判断和回滚都要人来做。
适合发布频率低、有专人盯盘的团队。

### 方案二：Nginx Ingress Canary

```yaml
annotations:
  nginx.ingress.kubernetes.io/canary: "true"
  nginx.ingress.kubernetes.io/canary-weight: "20"
```

能按权重或 header 精确分流，但同样**没有自动判定**。

### 方案三：Flagger

Flagger 把「分析 + 放量 + 回滚」做成闭环控制器，
配合 Service Mesh（Istio/Linkerd）做精确控流，能力与 Argo Rollouts 接近。

### 我的判断

> **如果团队没有专人维护发布流程，宁可用原生方案加人工确认，
> 也不要用一个没人真正理解的自动化系统。**
>
> 后者在出问题时会让故障排查更难——你不知道它是「判定失败」还是「超时」，
> 也不知道回滚是谁触发的。自动化系统的价值来自**可信**，而不是来自**自动**。

---

## 九、常见故障排查

| 现象 | 根因 | 排查方式 |
|---|---|---|
| Rollout 卡在 `Progressing` 不推进 | AnalysisRun 判 Inconclusive 在重试 | `kubectl get analysisrun -o yaml` 看 metric 的 `phase` |
| AnalysisRun 一直 `Running` | 探测任务连不上 Service | 检查 NetworkPolicy 是否放行了分析 Pod |
| Pod 一直 `NotReady` | readiness 探针失败 | `kubectl describe pod` 看事件，确认 `/actuator/health/readiness` 返回 |
| 回滚后仍有错误 | 故障写在共享 ConfigMap 里，回滚后新建的 Pod 仍会带上 | 把故障写进 pod template（见 `overlays/ci-fault`） |
| `kubectl set env` 报 `no kind "Rollout" is registered` | CRD 不在客户端 scheme，改用 `kubectl apply -k <overlay>` 改 pod template |
| `restart` 后没有走金丝雀步骤、几秒就 Healthy | `spec.restartAt` 只重启 Pod，不产生新 revision。要让金丝雀真跑，必须改 pod template（镜像/env/注解） |
| Rollout 报 `scaleDownDelaySeconds can only be used with traffic routing` | 未配 trafficRouting 时不能显式设该字段，删掉即可（默认行为不变） |
| `AnalysisRun` 创建失败 | AnalysisTemplate 不存在或名字拼错 | `kubectl get analysistemplate` |
| Rollout 显示 Healthy 但流量为 0 | Service selector 与 Pod 标签不匹配 | `kubectl get endpoints <svc>` 看是否为空 |
