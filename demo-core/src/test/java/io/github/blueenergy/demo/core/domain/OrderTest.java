package io.github.blueenergy.demo.core.domain;

import io.github.blueenergy.demo.common.OrderStatus;
import io.github.blueenergy.demo.common.error.BusinessException;
import io.github.blueenergy.demo.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Order} 的状态机单元测试。
 *
 * <p>纯对象测试，不启动 Spring、不连数据库 —— 整个类跑完在毫秒级。
 * 测试金字塔的底座就应该是这种东西：快、稳定、失败时原因一目了然。
 */
@DisplayName("Order 状态机")
class OrderTest {

    private static final String SYMBOL = "AAPL";
    private static final BigDecimal QUANTITY = new BigDecimal("100");
    private static final BigDecimal UNIT_PRICE = new BigDecimal("180.50");

    private static Order newOrder() {
        return Order.create(SYMBOL, QUANTITY, UNIT_PRICE);
    }

    private static Order persistedOrder(Long id, OrderStatus status) {
        Order order = newOrder();
        ReflectionTestUtils.setField(order, "id", id);
        ReflectionTestUtils.setField(order, "status", status);
        return order;
    }

    @Nested
    @DisplayName("创建")
    class Create {

        @Test
        @DisplayName("新订单状态为 NEW，字段与入参一致")
        void shouldCreateWithNewStatus() {
            Order order = newOrder();

            assertThat(order.getStatus()).isEqualTo(OrderStatus.NEW);
            assertThat(order.getSymbol()).isEqualTo(SYMBOL);
            assertThat(order.getQuantity()).isEqualByComparingTo(QUANTITY);
            assertThat(order.getUnitPrice()).isEqualByComparingTo(UNIT_PRICE);
        }

        @Test
        @DisplayName("金额用 BigDecimal 精确相乘，不引入浮点误差")
        void shouldComputeTotalAmountExactly() {
            Order order = Order.create(SYMBOL, new BigDecimal("3"), new BigDecimal("0.1"));

            // 用 double 算 3 * 0.1 会得到 0.30000000000000004
            assertThat(order.totalAmount()).isEqualByComparingTo(new BigDecimal("0.3"));
        }
    }

    @Nested
    @DisplayName("撤单")
    class Cancel {

        @Test
        @DisplayName("NEW 可以撤单")
        void shouldCancelNewOrder() {
            Order order = persistedOrder(1L, OrderStatus.NEW);

            order.cancel();

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        }

        @Test
        @DisplayName("已撤单的订单不能再撤")
        void shouldRejectCancellingCancelledOrder() {
            Order order = persistedOrder(1L, OrderStatus.CANCELLED);

            assertThatThrownBy(order::cancel)
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE);
        }

        @Test
        @DisplayName("已成交的订单不能撤单")
        void shouldRejectCancellingFilledOrder() {
            Order order = persistedOrder(1L, OrderStatus.FILLED);

            assertThatThrownBy(order::cancel)
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("FILLED");
        }
    }

    @Nested
    @DisplayName("成交")
    class Fill {

        @Test
        @DisplayName("NEW 可以成交")
        void shouldFillNewOrder() {
            Order order = persistedOrder(1L, OrderStatus.NEW);

            order.fill();

            assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
        }

        @Test
        @DisplayName("已撤单的订单不能成交")
        void shouldRejectFillingCancelledOrder() {
            Order order = persistedOrder(1L, OrderStatus.CANCELLED);

            assertThatThrownBy(order::fill).isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("状态语义")
    class StatusSemantics {

        @Test
        @DisplayName("只有 NEW 是非终态")
        void onlyNewIsNonTerminal() {
            assertThat(OrderStatus.NEW.isTerminal()).isFalse();
            assertThat(OrderStatus.FILLED.isTerminal()).isTrue();
            assertThat(OrderStatus.CANCELLED.isTerminal()).isTrue();
        }
    }

    @Nested
    @DisplayName("相等性")
    class Equality {

        @Test
        @DisplayName("同一 id 视为同一订单")
        void shouldBeEqualById() {
            assertThat(persistedOrder(7L, OrderStatus.NEW))
                    .isEqualTo(persistedOrder(7L, OrderStatus.FILLED));
        }

        @Test
        @DisplayName("不同 id 不是同一订单")
        void shouldNotBeEqualForDifferentIds() {
            assertThat(persistedOrder(7L, OrderStatus.NEW))
                    .isNotEqualTo(persistedOrder(8L, OrderStatus.NEW));
        }

        @Test
        @DisplayName("都未持久化（id 为 null）时不相等，避免把两张新单当成同一张")
        void unsavedOrdersAreNotEqual() {
            assertThat(newOrder()).isNotEqualTo(newOrder());
        }
    }
}
