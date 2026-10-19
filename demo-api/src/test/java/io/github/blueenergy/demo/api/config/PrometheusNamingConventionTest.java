package io.github.blueenergy.demo.api.config;

import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 记录 Micrometer 指标名到 Prometheus 指标名的映射规则。
 *
 * <p>这个测试本身就是一次踩坑的产物：按直觉写了
 * {@code Counter.builder("order.created")}，以为抓取出来是
 * {@code order_created_total}，实际不是 —— 集成测试的断言失败后才用这个
 * 隔离测试把真实规则固定下来。
 *
 * <p>把映射规则写成断言的价值在于：它把「我以为的」变成「CI 保证的」。
 * 指标名一旦发布就是对外契约，Grafana 面板、告警规则、SLO 计算全都依赖它。
 * 改名会让监控静默失效 —— 查询返回空而不是报错，这是最难发现的一类故障。
 *
 * <p>所有断言都带 {@code .as(scrape)}，失败时直接打印完整的抓取内容，
 * 省掉一轮「加日志再跑一遍」的往返。
 */
@DisplayName("Micrometer → Prometheus 指标命名映射")
class PrometheusNamingConventionTest {

    /** 注册指标后抓取，返回 Prometheus 文本格式。 */
    private static String scrape(Consumer<PrometheusMeterRegistry> registration) {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        registration.accept(registry);
        return registry.scrape();
    }

    @Test
    @DisplayName("计数器：点号转下划线，并补 _total 后缀")
    void counterGetsTotalSuffix() {
        String scrape = scrape(r -> r.counter("order.cancelled", "status", "CANCELLED").increment());

        assertThat(scrape).as(scrape).contains("order_cancelled_total");
    }

    @Test
    @DisplayName("计数器：名字以 total 结尾时不会重复追加成 _total_total")
    void counterDoesNotDoubleTotal() {
        String scrape = scrape(r -> r.counter("orders.total", "status", "NEW").increment());

        assertThat(scrape).as(scrape).contains("orders_total");
        assertThat(scrape).as(scrape).doesNotContain("orders_total_total");
    }

    @Test
    @DisplayName("计时器：点号转下划线，补 _seconds 后缀，并产出 count/sum/max")
    void timerGetsSecondsSuffix() {
        String scrape = scrape(r -> r.timer("order.process", "operation", "create")
                .record(Duration.ofMillis(5)));

        assertThat(scrape).as(scrape).contains("order_process_seconds_count");
        assertThat(scrape).as(scrape).contains("order_process_seconds_sum");
        assertThat(scrape).as(scrape).contains("order_process_seconds_max");
    }

    @Test
    @DisplayName("计时器：默认不产出 _bucket，因此算不出 p95/p99")
    void timerWithoutHistogramHasNoBuckets() {
        String scrape = scrape(r -> r.timer("plain.timer", "operation", "x")
                .record(Duration.ofMillis(5)));

        assertThat(scrape).as(scrape).contains("plain_timer_seconds_count");
        // 关键：没有直方图桶，histogram_quantile() 就无从计算分位数。
        // 这就是 application.yml 里必须打开
        // management.metrics.distribution.percentiles-histogram 的原因 ——
        // 不打开的话 P99 延迟告警规则永远不触发，且不会有任何报错。
        assertThat(scrape).as(scrape).doesNotContain("plain_timer_seconds_bucket");
    }

    @Test
    @DisplayName("计时器：显式打开直方图后才产出 _bucket")
    void timerWithHistogramHasBuckets() {
        String scrape = scrape(r -> Timer.builder("histogram.timer")
                .tag("operation", "x")
                .publishPercentileHistogram()
                .register(r)
                .record(Duration.ofMillis(5)));

        assertThat(scrape).as(scrape).contains("histogram_timer_seconds_bucket");
    }

    @Test
    @DisplayName("陷阱：_created 是保留后缀，以它结尾的计数器名会被吞掉")
    void createdIsAReservedSuffix() {
        String scrape = scrape(r -> {
            r.counter("order.created", "status", "NEW").increment();
            r.counter("orders.created", "status", "NEW").increment();
        });

        // 这是本项目真实踩过的坑：order.created 抓出来不是 order_created_total。
        //
        // 在 Prometheus 文本暴露格式里，foo_created 表示「计数器 foo 的创建时间戳」，
        // 是 foo_total 的附属元数据指标。于是 order_created 被解析成
        // 「计数器 order 的 created 元数据」，真正的计数器名塌缩为 order_total ——
        // created 这个词整个消失，而且不报任何错。
        //
        // 危险之处在于故障是静默的：Grafana 查询 order_created_total 返回空集
        // 而不是报错，面板显示「无数据」，告警规则永不触发。
        // 所以指标名要用动词形式（order.create）避开这个保留后缀。
        assertThat(scrape).as(scrape).contains("order_total");
        assertThat(scrape).as(scrape).contains("orders_total");
        assertThat(scrape).as(scrape).doesNotContain("order_created_total");
        assertThat(scrape).as(scrape).doesNotContain("orders_created_total");
    }
}
