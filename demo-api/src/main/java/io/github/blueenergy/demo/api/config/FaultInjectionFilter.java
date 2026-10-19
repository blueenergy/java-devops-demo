package io.github.blueenergy.demo.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.blueenergy.demo.common.dto.ApiErrorResponse;
import io.github.blueenergy.demo.common.error.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 故障注入过滤器：按配置概率给 API 请求返回 500，或增加固定延迟。
 *
 * <h2>为什么必须绕开 /actuator/**</h2>
 * 如果注入的故障也影响 {@code /actuator/health/readiness}，Pod 会一直处于
 * NotReady，Kubernetes 根本不会把流量切给它 —— 金丝雀分析还没开始跑，
 * 发布就卡在「等待副本就绪」上了。
 *
 * <p>那演示的就不再是「金丝雀分析发现错误率超标并自动回滚」，
 * 而是「新版本启动失败」。两者的故障定位路径、回滚触发机制完全不同，
 * 面试时讲混了会显得没真正跑过。
 *
 * <p>所以这里只对 {@code /api/**} 生效，让 Pod 正常就绪、正常接流量，
 * 再由 AnalysisTemplate 通过业务接口探测到错误 —— 这才是真实的金丝雀语义。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class FaultInjectionFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FaultInjectionFilter.class);

    private static final String API_PATH_PREFIX = "/api/";
    private static final String METRIC_INJECTED = "fault.injected";

    private final FaultInjectionProperties properties;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    public FaultInjectionFilter(FaultInjectionProperties properties,
                                MeterRegistry meterRegistry,
                                ObjectMapper objectMapper) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;

        if (properties.isActive()) {
            log.warn("故障注入已启用：errorRate={} latencyMs={} —— 这不应出现在生产环境",
                    properties.errorRate(), properties.latencyMs());
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.isActive()
                || !request.getRequestURI().startsWith(API_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        if (properties.latencyMs() > 0) {
            try {
                Thread.sleep(properties.latencyMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // 中断意味着应用正在关闭，直接放行让优雅停机流程继续
                filterChain.doFilter(request, response);
                return;
            }
        }

        if (properties.errorRate() > 0.0
                && ThreadLocalRandom.current().nextDouble() < properties.errorRate()) {
            recordInjection("error");
            writeInjectedError(request, response);
            return;
        }

        if (properties.latencyMs() > 0) {
            recordInjection("latency");
        }

        filterChain.doFilter(request, response);
    }

    private void writeInjectedError(HttpServletRequest request,
                                    HttpServletResponse response) throws IOException {
        ErrorCode code = ErrorCode.INJECTED_FAULT;
        log.warn("注入故障 path={} method={}", request.getRequestURI(), request.getMethod());

        response.setStatus(code.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(
                response.getOutputStream(),
                ApiErrorResponse.of(code.code(), code.defaultMessage(), request.getRequestURI()));
    }

    /**
     * 记录注入次数。
     *
     * <p>这个指标在演示里很有用：Grafana 上看到 {@code fault_injected_total} 抬头，
     * 就能确认「金丝雀失败确实是因为故障注入生效了」，
     * 而不是因为其它原因（镜像拉取失败、资源不足）导致的假阳性回滚。
     */
    private void recordInjection(String type) {
        Counter.builder(METRIC_INJECTED)
                .description("注入的故障次数（仅演示用）")
                .tag("type", type)
                .register(meterRegistry)
                .increment();
    }
}
