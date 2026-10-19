# 运维 Runbook

> 告警触发时该做什么。每条告警对应一个「人会采取的行动」——
> 没有行动的告警是噪音，而噪音会让真正的告警被忽略。
>
> 告警定义见 `observability/prometheus/alert-rules.yml`。

---

## API 不可达

**对应告警**：`OrderDemoApiDown`（`up{job="order-demo-api"} == 0`，持续 1 分钟）

### 第一步：确认影响面

```bash
# 服务整体是否还有可用副本
kubectl -n order-demo get pods -l app.kubernetes.io/name=order-demo-api -o wide
kubectl -n order-demo get endpoints order-demo-api-stable
```

- Endpoints 为空 → **全部不可用**，按「严重」处理
- 仍有 Ready 副本 → 单实例问题，先摘除故障实例再排查

### 第二步：区分「进程死了」与「探针失败」

```bash
kubectl -n order-demo describe pod <pod> | grep -A5 -E 'Last State|Events'
```

| 现象 | 含义 | 动作 |
|---|---|---|
| `Last State: Terminated, Reason: OOMKilled` | 内存超限被杀 | 调大 `resources.limits.memory` 或查内存泄漏 |
| `Liveness probe failed` | 进程卡死 | 看线程 dump，确认是否死锁/长 GC |
| `Readiness probe failed` 但 Pod Running | 依赖不可用（DB 等） | 跳到「数据库不可用」 |
| `ImagePullBackOff` | 镜像拉不到 | 检查镜像 tag/digest 与拉取凭证 |

### 第三步：回滚（如果与最近发布相关）

```bash
# 确认最近是否有发布
kubectl argo rollouts history rollout order-demo-api -n order-demo

# 回滚到上一个版本
kubectl argo rollouts undo rollout order-demo-api -n order-demo

# 观察恢复
kubectl argo rollouts status order-demo-api -n order-demo --timeout 120s
```

**判断标准**：告警开始时间与最近一次发布时间的间隔。小于 30 分钟就先回滚再查根因——
恢复优先于定位。

---

## 错误率升高

**对应告警**：`OrderDemoHighErrorRate`（5xx 占比 > 5%，持续 2 分钟）

### 第一步：确认错误类型

```bash
# 按状态码分布
curl -s localhost:8080/actuator/prometheus | grep 'http_server_requests_seconds_count' | grep 'status="5'

# 最近的错误日志
kubectl -n order-demo logs -l app.kubernetes.io/name=order-demo-api --tail=200 | grep -E 'ERROR|未预期异常'
```

### 第二步：按错误码分流

| 错误码 | 常见根因 | 动作 |
|---|---|---|
| `INJECTED_FAULT` | **故障注入没关** | 检查 ConfigMap `FAULT_ENABLED`，立即置 `false` |
| `INTERNAL_ERROR` | 未预期异常 | 看日志堆栈；常见是 DB 连接问题或空指针 |
| `ORDER_NOT_FOUND` 激增 | 上游传错 id / 数据被清理 | 属 4xx，不应计入 5xx 告警——检查是否有客户端 bug |
| 429 / 503 | 限流或上游不可用 | 查 Ingress 与应用限流配置 |

**`INJECTED_FAULT` 出现即说明故障注入开着**，这在生产是配置事故，
优先级高于其他排查。

### 第三步：判断是否需要回滚

```bash
# 金丝雀是否正在放量
kubectl argo rollouts get rollout order-demo-api -n order-demo

# 若正在金丝雀且错误集中在 canary 版本 -> 直接中止
kubectl argo rollouts abort rollout order-demo-api -n order-demo
```

---

## P99 延迟升高

**对应告警**：`OrderDemoHighLatencyP99`（> 500ms，持续 5 分钟）

按以下顺序排查，**从最可能且最便宜的开始**：

1. **数据库慢查询**
   ```sql
   SELECT pid, now() - query_start AS duration, query
   FROM pg_stat_activity
   WHERE state = 'active' AND now() - query_start > interval '1 second'
   ORDER BY duration DESC;
   ```
2. **连接池排队**：看 `hikaricp_connections_pending` 是否 > 0
3. **GC 停顿**：看 `jvm_gc_pause_seconds_max`，若单次 > 200ms 需查堆配置
4. **下游依赖**：本服务目前无外部 HTTP 依赖，跳过
5. **流量突增**：对比 `http_server_requests_seconds_count` 的 rate 与基线

---

## 数据库不可用

**现象**：`/actuator/health/readiness` 返回 DOWN（`db` 组件），Pod 从 Endpoints 摘除。

**注意**：`liveness` 仍为 UP，所以 **Pod 不会被重启** —— 这是刻意设计。
数据库故障时重启应用毫无意义，只会放大抖动。

```bash
kubectl -n order-demo get pods -l app.kubernetes.io/name=order-demo-postgres
kubectl -n order-demo logs deployment/order-demo-postgres --tail=100

# 从应用侧验证连通性
kubectl -n order-demo exec -it <app-pod> -- sh -c 'nc -zv order-demo-postgres 5432'
```

**演示环境的数据库是单副本无备份**。生产环境应使用托管数据库，
故障处置依赖云厂商的自动主备切换，不适用本 runbook。

---

## 金丝雀分析失败

**现象**：`kubectl get analysisrun` 显示 `Failed`，Rollout 转 `Degraded`。

```bash
# 看失败的具体指标
kubectl -n order-demo describe analysisrun <name> | tail -20

# 看探测任务的实际输出
kubectl -n order-demo logs job/<analysis-run-name>
```

| 失败信息 | 含义 |
|---|---|
| `failed: 创建订单未返回 id, 响应={"code":"INJECTED_FAULT"}` | 新版本带有故障注入配置（演示场景的预期结果） |
| `curl: (7) Failed to connect` | 探测 Pod 连不上 canary Service —— 查 NetworkPolicy 与 Service selector |
| `AnalysisRun` 长时间 `Running` | 探测任务卡住，查 `activeDeadlineSeconds` 与 Job 状态 |
| `Inconclusive` | 指标查询返回空集 —— Prometheus 版分析模板才会出现，见 `docs/06` |

---

## 通用原则

1. **先恢复，后定位。** 用户受影响时，回滚是正确答案；根因分析可以在恢复后做。
2. **每个动作都要有可观测的确认。** 回滚后必须实际打接口验证，
   而不是只看 Rollout 状态是 Healthy。
3. **记录时间线。** 从告警触发到恢复的每一步都记下来，
   这是事后 RCA 与 DORA 指标（MTTR）的数据来源。
