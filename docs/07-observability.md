# 07 可观测性：指标名一旦发布就是对外契约，改名会让监控静默失效

> 一句话结论：可观测性里最危险的不是「没埋点」，而是**查询返回空集却不报错** —— 指标名写错、直方图桶没打开、Prometheus 导出没启用，三者都会让面板全空、告警永不触发，而服务看起来完全健康。

## Actuator 端点暴露策略

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
```

**显式列出，而不是依赖默认值。** 原因不是默认值不安全，而是**显式列表在 code review 时是可见的** —— 有人为了排障把 `include` 放宽时，diff 里能看见。

端点一旦放宽，泄漏面是这样的：

| 端点 | 泄漏什么 | 危害 |
|---|---|---|
| `env` | 配置属性与环境变量 | 数据库密码、第三方 token（`show-values` 打开时是明文） |
| `configprops` | 所有 `@ConfigurationProperties` 的绑定值 | 同上，且更结构化、更好读 |
| `beans` | 完整 Bean 列表与依赖图 | 暴露内部结构，给攻击者画地图 |
| `heapdump` | JVM 堆快照 | **可以直接从中捞出内存里的密钥**，是这几个里最严重的 |
| `threaddump` | 线程栈 | 泄漏代码路径与调用关系 |

`health` 的详情也有同样的考量：

```yaml
management:
  endpoint:
    health:
      show-details: always      # 演示用；生产建议 when-authorized
```

`always` 方便本地直接看到 `db` 等组件的状态，但生产环境下健康详情本身就是信息泄漏面（数据库地址、磁盘路径、组件版本）。

## Prometheus 导出必须显式打开

```yaml
management:
  prometheus:
    metrics:
      export:
        enabled: true
```

这一行**不能省**。虽然 Boot 的配置元数据里 `management.prometheus.metrics.export.enabled` 标称默认 `true`，但实际运行时 `PrometheusMetricsExportAutoConfiguration` 的条件判定会失败（`--debug` 的条件评估报告里能看到 `@ConditionalOnEnabledMetricsExport management.defaults.metrics.export.enabled is considered false`）。

后果非常隐蔽：

| 看起来 | 实际 |
|---|---|
| 应用启动无任何报错 | `/actuator/prometheus` **根本没注册** |
| `/actuator/health` 正常 | 请求落到静态资源处理器，返回 500 |
| 服务「健康」 | 监控面板全空、告警规则永不触发 |

排查路径：启动时加 `--debug`，在 CONDITIONS EVALUATION REPORT 里搜 `PrometheusMetricsExportAutoConfiguration`。

## 指标设计：`status` 而不是 `symbol`

```java
Counter.builder("order.create")
        .tag("status", saved.getStatus().name())   // 只有 3 个取值
        .register(meterRegistry)
        .increment();
```

**刻意不把 `symbol` 作为标签**，原因是基数（cardinality）：

| 维度 | 取值数 | 作为标签的后果 |
|---|---|---|
| `status` | 3（`NEW` / `CANCELLED` / …） | 3 条时间序列，安全 |
| `symbol` | 几千到上万 | **每个不同的 symbol 生成一条独立时间序列**，内存与查询性能被拖垮 |
| `operation` | 3（`create` / `get` / `cancel`） | 安全 |

Prometheus 的时间序列 = 指标名 + 全部标签值的笛卡尔积。基数爆炸的代价是双重的：内存里要维护每条序列的索引，查询时要扫描的序列数也线性上升。一个高基数标签就能把一套本来运行良好的 Prometheus 拖到查询超时。

**高基数维度应该进日志或链路追踪，不是指标。** 需要「按 symbol 排查」时，用日志里的 `traceId` 去链路追踪系统查，而不是在指标上打标签。

本仓库实际埋的四个业务指标：

| 指标名（Micrometer） | 类型 | 标签 | 含义 |
|---|---|---|---|
| `order.create` | counter | `status` | 成功创建的订单数 |
| `order.cancel` | counter | `status` | 成功撤销的订单数 |
| `order.process` | timer | `operation` | 订单操作耗时 |
| `fault.injected` | counter | `type` | 注入的故障次数（仅演示） |

## 最危险的一类故障：`_created` 是保留后缀

这是本项目真实踩过的坑，也是整篇文档里最值得记住的一条。

### 现象

```java
Counter.builder("order.created").register(meterRegistry).increment();
```

抓出来的**不是** `order_created_total`，而是 `order_total` —— **`created` 这个词整个消失了，且不会有任何报错**。

### 机制

在 Prometheus 的文本暴露格式里，`foo_created` 是一个**保留后缀**，表示「计数器 `foo` 的创建时间戳」，是 `foo_total` 的附属元数据指标。

于是 `order_created` 被解析成「计数器 `order` 的 `created` 元数据」，计数器名因此塌缩成 `order_total`：

```
Micrometer 名        order.created
   ↓ 点号转下划线      order_created
   ↓ 识别 _created     基础名 = order，这是它的 created 元数据
   ↓ 补 _total         真正的计数器名 = order_total
```

转换发生在 **Micrometer 生成 Prometheus 文本格式的那一步**（`PrometheusMeterRegistry.scrape()`），而不是 Prometheus 服务端解析时 —— `PrometheusNamingConventionTest` 直接对 `scrape()` 的输出做断言，就能证明这一点。

```java
// 抓取后：order_created_total 不存在，只有 order_total
assertThat(scrape).contains("order_total");
assertThat(scrape).doesNotContain("order_created_total");
```

### 为什么这是最危险的一类故障

| 普通故障 | `_created` 塌缩 |
|---|---|
| 报错、抛异常、日志里有栈 | **没有任何错误**，应用启动正常、抓取正常 |
| Grafana 面板报错 | 查询 `order_created_total` **返回空集**，面板显示「No data」 |
| 告警能测出来 | 告警规则的条件永远为假，**永不触发** |
| 排查有线索 | 只能靠人发现「这个面板怎么一直是空的」 |

**查询返回空集不是错误，是合法结果。** 这就是它难发现的根本原因：整条链路上没有任何一个环节会告诉你「这个指标不存在」。一个没人看的空面板可以这样躺几个月。

修法：指标名用**动词形式**避开这个后缀 —— `order.create` 而不是 `order.created`。

### 命名映射规则，用测试固定下来

`PrometheusNamingConventionTest` 把「我以为的」变成了「CI 保证的」：

| Micrometer 名 | 类型 | Prometheus 抓取结果 |
|---|---|---|
| `order.cancelled` | counter | `order_cancelled_total`（点号转下划线 + 补 `_total`） |
| `orders.total` | counter | `orders_total`（**不会**重复追加成 `orders_total_total`） |
| `order.create` | counter | `order_create_total` |
| `order.created` | counter | **`order_total`**（`created` 被吞掉） |
| `order.process` | timer | `order_process_seconds_count` / `_sum` / `_max` |
| `order.process` + 直方图 | timer | 额外产出 `order_process_seconds_bucket` |

所有断言都带 `.as(scrape)`，失败时直接打印完整的抓取内容 —— 省掉一轮「加日志再跑一遍」的往返。

**为什么值得为它写一个测试类**：指标名一旦发布就是对外契约。Grafana 面板、告警规则、SLO 计算全都依赖它。改名会让监控静默失效，而静默失效意味着你失去的不是一个面板，而是**对系统状态的判断能力**。

## 直方图：不打开就没有 `_bucket`，分位数告警会静默失效

```yaml
management:
  metrics:
    distribution:
      percentiles-histogram:
        http.server.requests: true
        order.process: true
```

| 配置 | 抓取产出 | 能算什么 |
|---|---|---|
| 默认 | `..._seconds_count`、`..._seconds_sum`、`..._seconds_max` | 只能算**平均值** |
| 打开直方图 | 额外产出 `..._seconds_bucket{le="..."}` | `histogram_quantile()` 能算 p95 / p99 |

`histogram_quantile()` 的输入必须是 `_bucket` 序列。没有桶，函数返回空 —— **而空结果不会让告警规则失败，只会让它永远不触发**。这和 `_created` 是同一类故障：没有错误，只有沉默。

测试里同样有一条断言守住它：

```java
assertThat(scrape).doesNotContain("plain_timer_seconds_bucket");   // 默认没有桶
assertThat(scrape).contains("histogram_timer_seconds_bucket");     // 显式打开后才有
```

平均值能掩盖的问题：一个接口 99% 的请求 10ms、1% 的请求 10s，平均值看起来完全正常（约 110ms），而用户体验到的正是那 1% 的超时。**只有分位数能看见长尾。**

## 告警规则设计

### 两条原则

| 原则 | 落地方式 |
|---|---|
| **每条告警必须对应一个「人会采取的行动」** | 没有行动的告警就是噪音；噪音会让真正的告警被忽略 —— 这是告警体系失效最常见的原因 |
| **所有规则都带 `for` 子句** | 瞬时尖峰不值得半夜叫人。`for` 要求异常**持续**一段时间，滤掉抖动 |

第二条有一个隐含前提：`for` 的时长要和 `scrape_interval` 对得上。`prometheus.yml` 里 `scrape_interval: 15s`、`evaluation_interval: 15s`，所以 `for: 1m` 意味着大约连续 4 次抓取都异常才告警 —— 单次抓取超时不会把人叫起来。

### 七条规则逐条说明

**`alert-rules.yml` 里实际是 7 条规则**（README 里写的「6 条」是早期版本的计数，以文件为准），分三组。

#### 组一：可用性（`interval: 15s`）

| 规则 | PromQL | `for` | 级别 |
|---|---|---|---|
| `OrderDemoApiDown` | `up{job="order-demo-api"} == 0` | 1m | critical |

`up` 是 Prometheus **自己生成**的指标（1 = 抓取成功，0 = 失败），不需要应用配合。这使它成为**最可靠的第一道告警** —— 应用彻底挂掉、端口不通、Pod 没起来，这些场景下应用自己的指标一个都拿不到，只有 `up` 还能报警。

| 规则 | PromQL | `for` | 级别 |
|---|---|---|---|
| `OrderDemoHighErrorRate` | `sum(rate(...{status=~"5.."}[5m])) / sum(rate(...[5m])) > 0.05` | 2m | critical |

分母用**总量**而不是「成功数」，避免分母为 0 时除零（低流量时段这个场景很常见）。窗口取 5m 是为了让 `rate()` 有足够样本；阈值 5% 是「明显不正常但还没到全挂」的位置；`for: 2m` 滤掉发布瞬间的正常抖动。

| 规则 | PromQL | `for` | 级别 |
|---|---|---|---|
| `OrderDemoHighLatencyP99` | `histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket{...}[5m])) by (le)) > 0.5` | 5m | warning |

这条规则**完全依赖 `percentiles-histogram` 打开**，否则 `_bucket` 不存在，函数返回空，规则永不触发。阈值 500ms 对应「用户可感知的卡顿」；`for: 5m` 比错误率更宽松，因为延迟抖动比错误率更常见，太敏感会产生噪音。

#### 组二：资源（`interval: 30s`）

| 规则 | PromQL | `for` | 级别 |
|---|---|---|---|
| `OrderDemoJvmHeapPressure` | `sum(jvm_memory_used_bytes{area="heap"}) / sum(jvm_memory_max_bytes{area="heap"}) > 0.9` | 10m | warning |

**用 `max` 而不是 `committed` 作分母**：`committed` 会随 JVM 扩堆变化，用它做分母会在堆已经吃紧但还没扩到上限时显示出一个「健康」的比例，掩盖真实压力。持续 10 分钟高于 90% 意味着 GC 开销显著上升 —— 此时人该做的是查内存泄漏或调堆大小，所以 10 分钟的窗口是合适的（瞬时 90% 是正常的，尤其是刚做完一次 Full GC 之后）。

| 规则 | PromQL | `for` | 级别 |
|---|---|---|---|
| `OrderDemoDbConnectionPoolSaturated` | `hikaricp_connections_pending{...} > 0` | 5m | warning |

`hikaricp_connections_pending` 是**正在等待连接的线程数**。持续有线程在等，说明池子太小、或者有慢查询占着连接不放。阈值是 `> 0` 而不是某个比例 —— 因为任何持续的排队都值得看一眼，而 `for: 5m` 已经滤掉了瞬时排队。行动是明确的：检查 `DB_POOL_MAX_SIZE` 与数据库侧的连接上限。

#### 组三：业务（`interval: 30s`）

| 规则 | PromQL | `for` | 级别 |
|---|---|---|---|
| `OrderDemoFaultInjectionActive` | `sum(increase(fault_injected_total{...}[5m])) > 0` | **0m** | warning |

演示用的**安全网**：故障注入忘了关，会让这个「演示」变成真实事故。生产环境不应该存在这个指标 —— **它的存在本身就是配置错误的信号**。

`for: 0m` 是这里唯一一条不带延迟的规则，而且是刻意的：其它规则要滤抖动，这条要**立刻知道**。一旦有人在生产打开故障注入，多等一分钟就是多一分钟的真实错误。

| 规则 | PromQL | `for` | 级别 |
|---|---|---|---|
| `OrderDemoNoTraffic` | `sum(rate(http_server_requests_seconds_count{...}[10m])) == 0 and up{job="order-demo-api"} == 1` | 10m | warning |

「实例可抓取但完全没有请求」，通常意味着**上游路由配错了**（Service selector 改了标签没同步、Ingress 规则写错）。`and up == 1` 是关键：如果没有这个条件，应用整体挂掉时这条也会触发，与 `OrderDemoApiDown` 重复且噪音更大。10 分钟的窗口足够长，正常业务不会十分钟一个请求都没有。

### 一个待补的环节

每条规则的 `annotations` 里都有 `runbook_url: "docs/runbook.md#..."`。**这个文件目前不在仓库里** —— 它应该写「收到这条告警后第一步做什么」。这恰好是「每条告警必须对应一个行动」这条原则的落地位置：告警负责把人叫醒，runbook 负责告诉他人该做什么。缺了它，告警只是把人叫醒而已。

## 日志格式：预留 `traceId` / `spanId` 占位

```yaml
logging:
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [${spring.application.name:},%X{traceId:-},%X{spanId:-}] %logger{36} - %msg%n"
```

实际输出形如：

```
2026-10-19 10:06:32.875 INFO  [order-demo-api,,] c.b.demo.api.controller.OrderController - ...
```

`%X{traceId:-}` 从 MDC 取值，`:-` 表示「取不到时输出空」。**当前没有引入链路追踪，所以这两个位置是空的** —— 这是刻意的：

| 现在 | 接入 Micrometer Tracing（或 OpenTelemetry）之后 |
|---|---|
| 占位为空，格式里多两个逗号 | 自动填充，**不需要改任何配置** |
| 日志聚合系统已经能按这个格式解析 | 直接可以按 `traceId` 关联全链路 |

`[应用名,traceId,spanId]` 这个结构是 Spring Cloud Sleuth 沿用下来的事实标准，绝大多数日志聚合（ELK / Loki / 阿里云 SLS）都有现成的解析规则。**先占位、后接入**，避免了「接入追踪时才发现所有日志格式要重写、历史日志无法关联」这个常见的返工。

日志级别由环境变量驱动，方便按环境调整而不用改代码：

```yaml
logging:
  level:
    io.github.blueenergy.demo: ${LOG_LEVEL:INFO}
```

## 采集侧：两套配置，两种场景

| 场景 | 配置文件 | 目标发现方式 |
|---|---|---|
| 本地 `docker compose` | `observability/prometheus/prometheus.yml` | `static_configs` 写死 `app:8080`（容器名固定） |
| k8s | `k8s/base/servicemonitor.yaml` | `ServiceMonitor` + 标签选择器（Pod IP 随重建变化，必须靠服务发现） |

`prometheus.yml` 里两个间隔都设成 15s：

| 参数 | 含义 | 关系 |
|---|---|---|
| `scrape_interval: 15s` | 抓取频率，决定指标的时间分辨率 | 也决定了告警的最快响应速度 |
| `evaluation_interval: 15s` | 告警规则求值频率 | **通常等于或大于** `scrape_interval` —— 比它更快没有意义，数据还没更新 |

`ServiceMonitor` 那条路径有一个高频踩坑点：`kube-prometheus-stack` 默认 `serviceMonitorSelectorNilUsesHelmValues=true`，只会选中带 helm release 标签的 `ServiceMonitor`。本对象没有那个标签，于是 **Prometheus 根本不会加载它 —— 指标抓不到，但 `ServiceMonitor` 对象看起来一切正常**。排查方法是 `kubectl get prometheus -o yaml` 看 `serviceMonitorSelector`，以及 Prometheus 的 `/targets` 页面是否为空。文件里已经标注了需要补的标签位置。

`k8s/base/kustomization.yaml` 里 `servicemonitor.yaml` 默认被注释掉：它依赖 Prometheus Operator 的 CRD，集群里没装 Operator 时 `apply` 会报 `no matches for kind ServiceMonitor`。需要时在 overlay 里按需启用，而不是让 base 强制依赖。

## 本地验证

```bash
curl -s localhost:8080/actuator/prometheus | grep -E 'order_|fault_'   # 自定义指标
curl -s localhost:8080/actuator/health | jq .                          # 各组件状态
open http://localhost:9091/targets                                     # 抓取目标是否 UP
open http://localhost:9091/rules                                       # 7 条规则是否加载
open http://localhost:9091/alerts                                      # 哪些规则正在触发
```

`sleep 60` 之后先看 `/rules` 再下结论：`for` 子句没走完之前，规则状态是 `pending` 而不是 `firing`。

`docker-compose.yml` 里挂载了 `./observability/grafana/provisioning` 与 `./observability/grafana/dashboards`，但**这两个目录目前不在仓库里** —— compose 会自动创建空目录，Grafana 能启动，只是没有任何预置面板。要看图需要手工建，或者把面板 JSON 补进那个目录（这是待补的一环，与 runbook 同理）。
