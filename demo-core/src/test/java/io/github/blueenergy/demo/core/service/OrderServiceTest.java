package io.github.blueenergy.demo.core.service;

import io.github.blueenergy.demo.common.OrderStatus;
import io.github.blueenergy.demo.common.dto.CreateOrderRequest;
import io.github.blueenergy.demo.common.dto.OrderResponse;
import io.github.blueenergy.demo.common.error.BusinessException;
import io.github.blueenergy.demo.common.error.ErrorCode;
import io.github.blueenergy.demo.core.domain.Order;
import io.github.blueenergy.demo.core.repository.OrderRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link OrderService} 单元测试。
 *
 * <p>仓储被 mock 掉，所以这里验证的是「服务层的编排逻辑」：
 * 该不该抛异常、该不该写库、指标有没有被记录。
 * 真实 SQL 行为由 demo-api 的 Testcontainers 集成测试覆盖。
 *
 * <p>用 {@link SimpleMeterRegistry} 而不是 mock 掉 MeterRegistry：
 * 它是 Micrometer 自带的真实实现，能让我们断言「指标确实被记录了」，
 * 而 mock 只能断言「方法被调用了」——后者在重构后极易变成假阳性。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrderService")
class OrderServiceTest {

    private static final String SYMBOL = "AAPL";
    private static final BigDecimal QUANTITY = new BigDecimal("100");
    private static final BigDecimal UNIT_PRICE = new BigDecimal("180.50");

    @Mock
    private OrderRepository orderRepository;

    private MeterRegistry meterRegistry;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        orderService = new OrderService(orderRepository, meterRegistry);
    }

    /** 模拟 save：JPA 落库后会回填自增主键，这里用反射模拟这个行为。 */
    private static Order withId(Order order, long id) {
        ReflectionTestUtils.setField(order, "id", id);
        return order;
    }

    @Test
    @DisplayName("创建订单：落库并把返回的 id 带进响应")
    void createShouldPersistAndReturnGeneratedId() {
        when(orderRepository.save(any(Order.class)))
                .thenAnswer(inv -> withId(inv.getArgument(0), 42L));

        OrderResponse response = orderService.create(
                new CreateOrderRequest(SYMBOL, QUANTITY, UNIT_PRICE));

        assertThat(response.id()).isEqualTo(42L);
        assertThat(response.symbol()).isEqualTo(SYMBOL);
        assertThat(response.status()).isEqualTo(OrderStatus.NEW);
    }

    @Test
    @DisplayName("创建订单：记录 order.create 计数，标签是结果状态")
    void createShouldRecordCreatedCounter() {
        when(orderRepository.save(any(Order.class)))
                .thenAnswer(inv -> withId(inv.getArgument(0), 1L));

        orderService.create(new CreateOrderRequest(SYMBOL, QUANTITY, UNIT_PRICE));

        double count = meterRegistry.get("order.create")
                .tag("status", "NEW")
                .counter()
                .count();
        assertThat(count).isEqualTo(1.0);
    }

    @Test
    @DisplayName("创建订单：记录耗时，标签是操作名")
    void createShouldRecordProcessTimer() {
        when(orderRepository.save(any(Order.class)))
                .thenAnswer(inv -> withId(inv.getArgument(0), 1L));

        orderService.create(new CreateOrderRequest(SYMBOL, QUANTITY, UNIT_PRICE));

        assertThat(meterRegistry.get("order.process")
                .tag("operation", "create")
                .timer()
                .count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("查询订单：存在时返回映射后的响应")
    void getByIdShouldReturnMappedResponse() {
        Order order = withId(Order.create(SYMBOL, QUANTITY, UNIT_PRICE), 7L);
        when(orderRepository.findById(7L)).thenReturn(Optional.of(order));

        OrderResponse response = orderService.getById(7L);

        assertThat(response.id()).isEqualTo(7L);
        assertThat(response.quantity()).isEqualByComparingTo(QUANTITY);
        assertThat(response.unitPrice()).isEqualByComparingTo(UNIT_PRICE);
    }

    @Test
    @DisplayName("查询订单：不存在时抛 ORDER_NOT_FOUND")
    void getByIdShouldThrowWhenMissing() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.getById(99L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORDER_NOT_FOUND);
    }

    @Test
    @DisplayName("撤单：状态迁移为 CANCELLED 并写回")
    void cancelShouldTransitionAndPersist() {
        Order order = withId(Order.create(SYMBOL, QUANTITY, UNIT_PRICE), 5L);
        when(orderRepository.findById(5L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderResponse response = orderService.cancel(5L);

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("撤单：记录 order.cancel 计数")
    void cancelShouldRecordCancelledCounter() {
        Order order = withId(Order.create(SYMBOL, QUANTITY, UNIT_PRICE), 5L);
        when(orderRepository.findById(5L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        orderService.cancel(5L);

        assertThat(meterRegistry.get("order.cancel")
                .tag("status", "CANCELLED")
                .counter()
                .count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("撤单：订单不存在时抛异常，且不产生任何写操作")
    void cancelShouldNotWriteWhenOrderMissing() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.cancel(99L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORDER_NOT_FOUND);

        // 关键断言：失败路径不能留下副作用
        verify(orderRepository, never()).save(any(Order.class));
        assertThat(meterRegistry.find("order.cancel").counter()).isNull();
    }

    @Test
    @DisplayName("撤单：已成交订单抛 ORDER_NOT_CANCELLABLE，且不写库")
    void cancelShouldRejectFilledOrder() {
        Order order = withId(Order.create(SYMBOL, QUANTITY, UNIT_PRICE), 6L);
        ReflectionTestUtils.setField(order, "status", OrderStatus.FILLED);
        when(orderRepository.findById(6L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.cancel(6L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE);

        verify(orderRepository, never()).save(any(Order.class));
    }
}
