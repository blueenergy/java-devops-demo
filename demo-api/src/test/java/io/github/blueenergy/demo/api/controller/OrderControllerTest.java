package io.github.blueenergy.demo.api.controller;

import io.github.blueenergy.demo.common.OrderStatus;
import io.github.blueenergy.demo.common.dto.CreateOrderRequest;
import io.github.blueenergy.demo.common.dto.OrderResponse;
import io.github.blueenergy.demo.common.error.BusinessException;
import io.github.blueenergy.demo.common.error.ErrorCode;
import io.github.blueenergy.demo.core.service.OrderService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link OrderController} 切片测试。
 *
 * <p>{@code @WebMvcTest} 只装配 Web 层：控制器、消息转换器、{@code @RestControllerAdvice}、
 * 过滤器。Service 用 {@link MockitoBean} 替换掉，数据库、连接池、Flyway 一概不加载。
 *
 * <p>这层测试回答「HTTP 契约对不对」：状态码、JSON 结构、参数校验、异常映射。
 * 业务规则由 demo-core 的单测覆盖，真实 SQL 由 {@code OrderApiIT} 覆盖 ——
 * 三层各管一段，互不重复。
 *
 * <h2>为什么这个类没有用 @Nested 分组</h2>
 * 曾经按接口分了 {@code @Nested} 内部类，结果踩了一个很隐蔽的坑：
 * {@code @MockitoBean} 的自动重置（{@code MockReset.AFTER}）由 Spring 的
 * {@code MockitoTestExecutionListener} 在测试方法结束后触发，而它与
 * {@code @Nested} 内部类的执行生命周期对不上 —— stubbing 会跨测试方法泄漏。
 *
 * <p>泄漏的后果很反直觉：{@code when(orderService.getById(99L))} 这个写法
 * <b>会先真实调用一次方法</b>，如果上一个测试给同一参数注册过 {@code thenThrow}，
 * 那么 {@code when(...)} 还没执行完就抛异常了，报错位置指向一行看起来毫无问题的
 * stubbing 代码。排查时极易怀疑到生产代码上去。
 *
 * <p>扁平化之后走的是标准生命周期，重置可靠。分组带来的可读性收益，
 * 不值得用这种隐蔽的不确定性去换。
 */
@WebMvcTest(controllers = OrderController.class)
@Import(OrderControllerTest.SliceInfrastructureConfig.class)
@DisplayName("OrderController HTTP 契约")
class OrderControllerTest {

    private static final String SYMBOL = "AAPL";
    private static final BigDecimal QUANTITY = new BigDecimal("100");
    private static final BigDecimal UNIT_PRICE = new BigDecimal("180.50");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    /**
     * 切片测试的基础设施补丁。
     *
     * <p>{@code @WebMvcTest} 的切片范围包含 {@code Filter} bean，所以
     * {@code FaultInjectionFilter} 会被加载；但它依赖 {@code MeterRegistry}，
     * 而指标自动配置不在 Web 切片里 —— 症状是
     * {@code No qualifying bean of type 'MeterRegistry' available}，
     * 整个上下文启动失败，所有测试一起报错。
     *
     * <p>另一种解法是加 {@code @AutoConfigureMetrics} 引入完整的指标自动配置，
     * 但那会连带拉起一堆切片用不到的基础设施。切片测试的原则是
     * 「只加载被测层真正需要的东西」。
     */
    @TestConfiguration
    static class SliceInfrastructureConfig {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    // ---------- 辅助方法 ----------

    private static OrderResponse sampleResponse(Long id, OrderStatus status) {
        Instant now = Instant.parse("2026-01-15T08:30:00Z");
        return new OrderResponse(id, SYMBOL, QUANTITY, UNIT_PRICE, status, now, now);
    }

    private static String requestJson(String symbol, String quantity, String unitPrice) {
        return """
                {"symbol":"%s","quantity":%s,"unitPrice":%s}
                """.formatted(symbol, quantity, unitPrice);
    }

    // ---------- POST /api/v1/orders ----------

    @Test
    @DisplayName("创建订单：合法请求返回 201 与订单体")
    void createReturns201() throws Exception {
        when(orderService.create(any(CreateOrderRequest.class)))
                .thenReturn(sampleResponse(1L, OrderStatus.NEW));

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(SYMBOL, "100", "180.50")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.symbol").value(SYMBOL))
                .andExpect(jsonPath("$.status").value("NEW"));
    }

    @Test
    @DisplayName("创建订单：symbol 为空返回 400 并给出字段级明细")
    void createReturns400WhenSymbolBlank() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("", "100", "180.50")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[0].field").value("symbol"));
    }

    @Test
    @DisplayName("创建订单：数量为负返回 400")
    void createReturns400WhenQuantityNegative() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(SYMBOL, "-5", "180.50")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("quantity"));
    }

    @Test
    @DisplayName("创建订单：数量为 0 返回 400（@Positive 不接受 0）")
    void createReturns400WhenQuantityZero() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(SYMBOL, "0", "180.50")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("创建订单：请求体不是合法 JSON 返回 400 而不是 500")
    void createReturns400WhenMalformedJson() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ this is not json "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---------- GET /api/v1/orders/{id} ----------

    @Test
    @DisplayName("查询订单：存在时返回 200")
    void getByIdReturns200() throws Exception {
        when(orderService.getById(1L)).thenReturn(sampleResponse(1L, OrderStatus.NEW));

        mockMvc.perform(get("/api/v1/orders/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.unitPrice").value(180.50));
    }

    @Test
    @DisplayName("查询订单：不存在时返回 404 与 ORDER_NOT_FOUND")
    void getByIdReturns404WhenMissing() throws Exception {
        when(orderService.getById(99L))
                .thenThrow(new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单 99 不存在"));

        mockMvc.perform(get("/api/v1/orders/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/v1/orders/99"));
    }

    @Test
    @DisplayName("查询订单：id 不是数字返回 400 而不是 500")
    void getByIdReturns400WhenIdNotNumeric() throws Exception {
        // 这条用例抓到过一个真实缺陷：MethodArgumentTypeMismatchException 曾经落到
        // 兜底分支被当成 500 返回。后果是调用方拼错 URL 会污染 5xx 错误率指标，
        // 可能触发错误率告警，甚至让金丝雀分析误判而回滚一个正常的版本。
        mockMvc.perform(get("/api/v1/orders/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[0].field").value("id"));
    }

    // ---------- POST /api/v1/orders/{id}/cancel ----------

    @Test
    @DisplayName("撤单：成功返回 200 与 CANCELLED")
    void cancelReturns200() throws Exception {
        when(orderService.cancel(1L)).thenReturn(sampleResponse(1L, OrderStatus.CANCELLED));

        mockMvc.perform(post("/api/v1/orders/1/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("撤单：重复撤单返回 409 而不是 500——并发冲突不是服务端故障")
    void cancelReturns409WhenNotCancellable() throws Exception {
        when(orderService.cancel(eq(1L)))
                .thenThrow(new BusinessException(ErrorCode.ORDER_NOT_CANCELLABLE));

        mockMvc.perform(post("/api/v1/orders/1/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_CANCELLABLE"));
    }

    // ---------- 错误响应结构 ----------

    @Test
    @DisplayName("错误响应：结构稳定，带 code / message / path / timestamp")
    void errorResponseShapeIsStable() throws Exception {
        when(orderService.getById(404L))
                .thenThrow(new BusinessException(ErrorCode.ORDER_NOT_FOUND));

        mockMvc.perform(get("/api/v1/orders/404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").exists())
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.path").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("错误响应：非校验类错误不输出空的 details 数组")
    void errorDetailsOmittedWhenEmpty() throws Exception {
        when(orderService.getById(405L))
                .thenThrow(new BusinessException(ErrorCode.ORDER_NOT_FOUND));

        mockMvc.perform(get("/api/v1/orders/405"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    @DisplayName("错误响应：未预期异常返回 500，且不泄漏内部异常消息")
    void unexpectedExceptionDoesNotLeakInternals() throws Exception {
        when(orderService.getById(500L))
                .thenThrow(new IllegalStateException("jdbc:connection pool exhausted at 10.0.0.5:5432"));

        mockMvc.perform(get("/api/v1/orders/500"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("服务端内部错误"));
    }
}
