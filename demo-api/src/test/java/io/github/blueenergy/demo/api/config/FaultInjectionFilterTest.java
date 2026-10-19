package io.github.blueenergy.demo.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FaultInjectionFilter} 单元测试。
 *
 * <p>刻意不启动 Spring 上下文：这个过滤器的行为完全由构造参数决定，
 * 用 {@code MockHttpServletRequest} 直接驱动即可。为测一个过滤器而多起一次
 * Testcontainers 容器，会让整个测试套件的反馈时间翻倍，收益却为零。
 *
 * <p>错误率固定用 1.0 或 0.0 而不是中间值 —— 概率性的断言会偶发失败，
 * 是测试套件里最讨厌的一类不稳定因素。
 */
@DisplayName("FaultInjectionFilter")
class FaultInjectionFilterTest {

    private MeterRegistry meterRegistry;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        // 用 Jackson2ObjectMapperBuilder 而不是裸 new ObjectMapper()：
        // 后者不注册 JavaTimeModule，序列化 ApiErrorResponse 的 Instant 字段会抛
        // InvalidDefinitionException。生产环境注入的是 Spring Boot 自动配置的 ObjectMapper，
        // 它已经注册了全部可用模块——测试里必须构造出等价的对象，否则测的不是真实行为。
        objectMapper = Jackson2ObjectMapperBuilder.json().build();
    }

    private FaultInjectionFilter filter(boolean enabled, double errorRate, long latencyMs) {
        return new FaultInjectionFilter(
                new FaultInjectionProperties(enabled, errorRate, latencyMs),
                meterRegistry,
                objectMapper);
    }

    private static MockHttpServletRequest apiRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orders/1");
        request.setRequestURI("/api/v1/orders/1");
        return request;
    }

    @Test
    @DisplayName("关闭时完全放行，不干预请求")
    void shouldPassThroughWhenDisabled() throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter(false, 0.5, 0).doFilter(apiRequest(), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(meterRegistry.find("fault.injected").counter()).isNull();
    }

    @Test
    @DisplayName("启用且错误率 100% 时返回 500，且不再往下传递")
    void shouldInjectErrorAtFullRate() throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter(true, 1.0, 0).doFilter(apiRequest(), response, chain);

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()).contains("INJECTED_FAULT");
        // 关键：链未被继续执行，说明请求根本没到 controller
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("记录 fault.injected 指标，用于确认回滚确实由注入触发")
    void shouldRecordInjectedMetric() throws ServletException, IOException {
        filter(true, 1.0, 0).doFilter(
                apiRequest(), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(meterRegistry.get("fault.injected")
                .tag("type", "error")
                .counter()
                .count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("错误率 0% 时不注入错误，只记录延迟指标")
    void shouldNotInjectErrorAtZeroRate() throws ServletException, IOException {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(true, 0.0, 1).doFilter(apiRequest(), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(meterRegistry.get("fault.injected")
                .tag("type", "latency")
                .counter()
                .count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("actuator 路径永不注入故障——否则 Pod 不 Ready，金丝雀语义就变了")
    void shouldNeverInjectOnActuatorPaths() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health/readiness");
        request.setRequestURI("/actuator/health/readiness");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        // 即便错误率 100%，健康检查也必须正常通过
        filter(true, 1.0, 0).doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("非 /api 路径不注入故障")
    void shouldNotInjectOnNonApiPaths() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/index.html");
        request.setRequestURI("/index.html");
        MockFilterChain chain = new MockFilterChain();

        filter(true, 1.0, 0).doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("延迟注入：请求仍成功，但耗时不低于配置值")
    void shouldApplyConfiguredLatency() throws ServletException, IOException {
        MockFilterChain chain = new MockFilterChain();
        long start = System.nanoTime();

        filter(true, 0.0, 120).doFilter(apiRequest(), new MockHttpServletResponse(), chain);

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(chain.getRequest()).isNotNull();
        assertThat(elapsedMs).isGreaterThanOrEqualTo(100);
    }
}
