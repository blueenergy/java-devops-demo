package io.github.blueenergy.demo.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 故障注入配置。
 *
 * <p>这是演示金丝雀回滚的「道具」，不是业务功能。生产代码里不应该有这种东西 ——
 * 真实系统用混沌工程平台（Chaos Mesh / Litmus）在基础设施层注入故障，
 * 而不是把开关编进业务进程。放在这里是为了让回滚场景可复现：
 * 部署一个新版本并把 error-rate 调到 0.5，金丝雀分析必然失败，回滚必然触发。
 *
 * <p>默认全关。通过 ConfigMap 或环境变量打开，见 {@code k8s/base/configmap.yaml}。
 *
 * @param enabled   总开关，false 时本机制完全不生效
 * @param errorRate 每个 API 请求返回 500 的概率，取值 [0.0, 1.0]
 * @param latencyMs 每个 API 请求额外增加的延迟（毫秒），用于制造慢响应
 */
@ConfigurationProperties(prefix = "app.fault")
public record FaultInjectionProperties(
        boolean enabled,
        double errorRate,
        long latencyMs
) {

    public FaultInjectionProperties {
        if (errorRate < 0.0 || errorRate > 1.0) {
            throw new IllegalArgumentException(
                    "app.fault.error-rate 必须在 [0.0, 1.0] 区间内，当前为 " + errorRate);
        }
        if (latencyMs < 0) {
            throw new IllegalArgumentException(
                    "app.fault.latency-ms 不能为负数，当前为 " + latencyMs);
        }
    }

    /** 是否真的需要做点什么。全关时过滤器直接放行，零开销。 */
    public boolean isActive() {
        return enabled && (errorRate > 0.0 || latencyMs > 0);
    }
}
