package io.github.blueenergy.demo.core.service;

import io.github.blueenergy.demo.common.dto.CreateOrderRequest;
import io.github.blueenergy.demo.common.dto.OrderResponse;
import io.github.blueenergy.demo.common.error.BusinessException;
import io.github.blueenergy.demo.common.error.ErrorCode;
import io.github.blueenergy.demo.core.domain.Order;
import io.github.blueenergy.demo.core.repository.OrderRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

/**
 * 订单业务服务。
 *
 * <p>这一层负责三件事：事务边界、状态机驱动、指标埋点。
 * 它不认识 HTTP —— 没有 {@code ResponseEntity}、没有 {@code HttpServletRequest}，
 * 这样同一份逻辑可以被 REST 接口、定时任务、消息消费者复用。
 *
 * <h2>关于指标埋点的取舍</h2>
 * 指标名用点号分隔（{@code order.created}），Prometheus registry 会自动转成
 * {@code order_created_total} 并给 Counter 补上 {@code _total} 后缀。
 *
 * <p>刻意<b>不</b>把 {@code symbol} 作为标签：标的代码是高基数维度，
 * 每个不同的 symbol 都会在 Prometheus 里生成一条独立时间序列，
 * 几千个标的就能把内存和查询性能拖垮。高基数维度应该进日志或链路追踪，不是指标。
 * 这里用 {@code status}（只有 3 个取值）作标签，是安全的选择。
 */
@Service
public class OrderService {

    /**
     * 指标名刻意用动词形式 {@code order.create} 而不是 {@code order.created}。
     *
     * <p>原因：{@code _created} 是 Prometheus 文本暴露格式的<b>保留后缀</b>。
     * 在 Prometheus 里 {@code foo_created} 表示「计数器 foo 的创建时间戳」，
     * 是 {@code foo_total} 的附属元数据指标。所以 {@code order.created} 会被
     * 解释成「计数器 order 的 created 元数据」，真正的计数器名塌缩成 {@code order_total} ——
     * {@code created} 这个词整个消失了，且不会有任何报错。
     *
     * <p>后果：Grafana 面板与告警规则里写的 {@code order_created_total} 永远查不到数据，
     * 而查询返回空集是不报错的，故障因此完全静默。
     * 命名映射由 {@code PrometheusNamingConventionTest} 固定，防止再次踩到。
     */
    private static final String METRIC_CREATE = "order.create";

    private static final String METRIC_CANCEL = "order.cancel";

    private static final String METRIC_PROCESS = "order.process";

    private final OrderRepository orderRepository;
    private final MeterRegistry meterRegistry;

    public OrderService(OrderRepository orderRepository, MeterRegistry meterRegistry) {
        this.orderRepository = orderRepository;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 创建订单。
     *
     * @throws BusinessException 不会抛出——参数校验由接入层的 Bean Validation 负责
     */
    @Transactional
    public OrderResponse create(CreateOrderRequest request) {
        return timed("create", () -> {
            Order order = Order.create(request.symbol(), request.quantity(), request.unitPrice());
            Order saved = orderRepository.save(order);

            Counter.builder(METRIC_CREATE)
                    .description("成功创建的订单数")
                    .tag("status", saved.getStatus().name())
                    .register(meterRegistry)
                    .increment();

            return toResponse(saved);
        });
    }

    /**
     * 按 ID 查询订单。
     *
     * @throws BusinessException {@link ErrorCode#ORDER_NOT_FOUND} 订单不存在
     */
    @Transactional(readOnly = true)
    public OrderResponse getById(Long id) {
        return timed("get", () -> toResponse(loadOrThrow(id)));
    }

    /**
     * 撤单。
     *
     * @throws BusinessException {@link ErrorCode#ORDER_NOT_FOUND} 订单不存在；
     *                           {@link ErrorCode#ORDER_NOT_CANCELLABLE} 订单已是终态
     */
    @Transactional
    public OrderResponse cancel(Long id) {
        return timed("cancel", () -> {
            Order order = loadOrThrow(id);
            order.cancel();

            Counter.builder(METRIC_CANCEL)
                    .description("成功撤销的订单数")
                    .tag("status", order.getStatus().name())
                    .register(meterRegistry)
                    .increment();

            // 显式 save 而非依赖脏检查：语义更清楚，也不受 flush 时机影响
            return toResponse(orderRepository.save(order));
        });
    }

    private Order loadOrThrow(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.ORDER_NOT_FOUND, "订单 %d 不存在".formatted(id)));
    }

    /**
     * 记录操作耗时。
     *
     * <p>把计时收敛到一个方法里，避免每个公开方法都写一遍 Timer 样板代码，
     * 也保证所有操作的指标名与标签结构一致。
     */
    private <T> T timed(String operation, Supplier<T> action) {
        return Timer.builder(METRIC_PROCESS)
                .description("订单操作耗时")
                .tag("operation", operation)
                .register(meterRegistry)
                .record(action);
    }

    /** 实体 → 对外 DTO。实体不直接出网关，避免持久化模型泄漏成 API 契约。 */
    private static OrderResponse toResponse(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getSymbol(),
                order.getQuantity(),
                order.getUnitPrice(),
                order.getStatus(),
                order.getCreatedAt(),
                order.getUpdatedAt());
    }
}
