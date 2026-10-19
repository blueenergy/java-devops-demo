package io.github.blueenergy.demo.api;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端集成测试：真实 HTTP + 真实 PostgreSQL。
 *
 * <h2>为什么必须用 Testcontainers 而不是 H2</h2>
 * H2 的 SQL 方言、约束语义、事务隔离级别、类型系统都和 PostgreSQL 不同。
 * 用 H2 测出来的「通过」不能说明生产环境能跑 —— 典型翻车点包括：
 * <ul>
 *   <li>Flyway 迁移脚本里的 {@code TIMESTAMPTZ}、{@code GENERATED ... AS IDENTITY} H2 不认</li>
 *   <li>{@code CHECK} 约束、部分索引的行为差异</li>
 *   <li>H2 默认不校验外键，PG 校验</li>
 * </ul>
 * 用真库的代价是每个测试类要等容器启动（本机约 2-4 秒，镜像已缓存），
 * 换来的是「集成测试通过 = 生产也能跑」这个结论成立。
 *
 * <h2>@ServiceConnection 省掉了什么</h2>
 * 没有它，每个集成测试都要写一段 {@code @DynamicPropertySource} 手工把
 * 容器的 host/port/username/password 塞进 Spring Environment。
 * 有了它，Spring Boot 识别出容器类型后自动完成装配，连 JDBC URL 都不用拼。
 * 这是 Boot 3.1 引入的能力，也是「锁 Testcontainers 1.x 不升 2.x」的原因 ——
 * 这套集成是按 1.x 的 API 写的。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DisplayName("订单 API 端到端集成测试")
class OrderApiIT {

    /**
     * 用 postgres:17-alpine 而不是 latest：固定版本号让构建可复现。
     * 用 latest 意味着某天镜像更新后测试突然挂了，而代码一行没改。
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private TestRestTemplate restTemplate;

    // ---------- 辅助方法 ----------

    private ResponseEntity<String> postJson(String path, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
    }

    private ResponseEntity<String> get(String path) {
        return restTemplate.exchange(path, HttpMethod.GET, HttpEntity.EMPTY, String.class);
    }

    private static String orderJson(String symbol, String quantity, String unitPrice) {
        return """
                {"symbol":"%s","quantity":%s,"unitPrice":%s}
                """.formatted(symbol, quantity, unitPrice);
    }

    private long createOrder(String symbol, String quantity, String unitPrice) {
        ResponseEntity<String> response = postJson("/api/v1/orders", orderJson(symbol, quantity, unitPrice));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) JsonPath.read(response.getBody(), "$.id")).longValue();
    }

    // ---------- 用例 ----------

    @Test
    @DisplayName("创建订单 → 落库 → 能按 id 查回来（验证 Flyway 迁移与 JPA 映射）")
    void createThenFetch() {
        long id = createOrder("AAPL", "100", "180.50");

        ResponseEntity<String> fetched = get("/api/v1/orders/" + id);

        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.read(fetched.getBody(), "$.symbol").toString()).isEqualTo("AAPL");
        assertThat(JsonPath.read(fetched.getBody(), "$.status").toString()).isEqualTo("NEW");
        // createdAt 由 @PrePersist 填充，能读到说明实体确实走完了持久化生命周期
        assertThat(JsonPath.read(fetched.getBody(), "$.createdAt").toString()).isNotBlank();
    }

    @Test
    @DisplayName("数量与单价保持精度，不因 JDBC 往返丢失小数位")
    void decimalPrecisionSurvivesRoundTrip() {
        long id = createOrder("MSFT", "3", "0.1");

        ResponseEntity<String> fetched = get("/api/v1/orders/" + id);
        String body = fetched.getBody();
        assertThat(body).isNotNull();

        // 直接断言序列化后的原始 JSON 文本，而不是用 JsonPath 取值再 toString。
        // 原因：JsonPath 会把 JSON 数字反序列化成 Double，3.0000 会变成 3.0，
        // 于是断言「精度是否保留」这件事本身就失真了 —— 测的是 JsonPath 的类型推断，
        // 不是我们的序列化行为。看原始文本才能真正确认 NUMERIC(19,4) 的标度被保留。
        assertThat(body).contains("\"quantity\":3.0000");
        assertThat(body).contains("\"unitPrice\":0.1000");

        // 再补一条数值相等断言：即便将来 JSON 格式调整，数值正确性仍被守住
        assertThat(new BigDecimal(JsonPath.read(body, "$.quantity").toString()))
                .isEqualByComparingTo("3.0000");
    }

    @Test
    @DisplayName("查询不存在的订单返回 404 与 ORDER_NOT_FOUND")
    void fetchMissingOrderReturns404() {
        ResponseEntity<String> response = get("/api/v1/orders/99999999");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(JsonPath.read(response.getBody(), "$.code").toString()).isEqualTo("ORDER_NOT_FOUND");
    }

    @Test
    @DisplayName("撤单成功后状态变为 CANCELLED 并持久化")
    void cancelPersistsStatus() {
        long id = createOrder("TSLA", "10", "250.00");

        ResponseEntity<String> cancelled = postJson("/api/v1/orders/" + id + "/cancel", "");
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.read(cancelled.getBody(), "$.status").toString()).isEqualTo("CANCELLED");

        // 重新查一次，确认状态真的落库了而不只是内存里的对象变了
        ResponseEntity<String> refetched = get("/api/v1/orders/" + id);
        assertThat(JsonPath.read(refetched.getBody(), "$.status").toString()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("重复撤单返回 409（状态机在真实数据库上依然生效）")
    void cancelTwiceReturns409() {
        long id = createOrder("NVDA", "5", "900.00");

        assertThat(postJson("/api/v1/orders/" + id + "/cancel", "").getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<String> second = postJson("/api/v1/orders/" + id + "/cancel", "");
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(JsonPath.read(second.getBody(), "$.code").toString())
                .isEqualTo("ORDER_NOT_CANCELLABLE");
    }

    @Test
    @DisplayName("非法请求返回 400 且带字段明细")
    void invalidRequestReturns400WithDetails() {
        ResponseEntity<String> response = postJson("/api/v1/orders", orderJson("", "-1", "0"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(JsonPath.read(response.getBody(), "$.code").toString()).isEqualTo("VALIDATION_FAILED");
        assertThat(JsonPath.read(response.getBody(), "$.details").toString()).isNotBlank();
    }

    // ---------- 运维接口 ----------

    @Test
    @DisplayName("readiness 探针为 UP —— 它包含数据库检查，k8s 靠它决定是否切流量")
    void readinessProbeIsUp() {
        ResponseEntity<String> response = get("/actuator/health/readiness");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.read(response.getBody(), "$.status").toString()).isEqualTo("UP");
    }

    @Test
    @DisplayName("liveness 探针为 UP，且不包含数据库检查")
    void livenessProbeDoesNotDependOnDatabase() {
        ResponseEntity<String> response = get("/actuator/health/liveness");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.read(response.getBody(), "$.status").toString()).isEqualTo("UP");
        // liveness 组里出现 db 就意味着数据库抖动会导致所有 Pod 被重启
        assertThat(response.getBody()).doesNotContain("\"db\"");
    }

    @Test
    @DisplayName("Prometheus 端点暴露自定义业务指标")
    void prometheusEndpointExposesCustomMetrics() {
        createOrder("GOOG", "7", "140.00");

        ResponseEntity<String> response = get("/actuator/prometheus");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(body).isNotNull();
        // Counter 被 Prometheus registry 自动补上 _total 后缀
        assertThat(body).contains("order_create_total");
        // Timer 被自动补上 _seconds 后缀
        assertThat(body).contains("order_process_seconds");
        // 指标带了 application 公共标签
        assertThat(body).contains("application=\"order-demo-api\"");
    }

    @Test
    @DisplayName("构建信息注入到 /actuator/info —— 用来回答「线上跑的是哪个 commit」")
    void infoEndpointExposesBuildMetadata() {
        ResponseEntity<String> response = get("/actuator/info");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(body).isNotNull();

        // 核心断言：maven-resources-plugin 的 @...@ 过滤确实生效了。
        // 若这里出现字面量 "@project.version@"，说明父 POM 的 <delimiter> 配置丢了 ——
        // 那种情况下构建会静默通过，只有运行期访问 /actuator/info 才会发现。
        assertThat(body).doesNotContain("@project.version@");
        assertThat(body).doesNotContain("@project.name@");
        // name 来自 @project.name@，即 Maven 的 artifactId（demo-api），
        // 不是 spring.application.name（order-demo-api）—— 两者容易混淆。
        assertThat(body).contains("\"name\":\"demo-api\"");
        assertThat(body).contains("1.0.0-SNAPSHOT");
    }
}
