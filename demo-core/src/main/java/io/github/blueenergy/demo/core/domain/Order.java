package io.github.blueenergy.demo.core.domain;

import io.github.blueenergy.demo.common.OrderStatus;
import io.github.blueenergy.demo.common.error.BusinessException;
import io.github.blueenergy.demo.common.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 订单实体。
 *
 * <p>表名用 {@code orders} 而不是 {@code order}：{@code ORDER} 是 SQL 保留字，
 * 建表时会被解析成 {@code ORDER BY} 子句而报语法错误。
 *
 * <p>状态迁移通过实体方法（{@link #cancel()}）而不是 setter 完成。
 * 这样「哪些迁移合法」这条规则只存在于一个地方，不会被调用方绕过。
 * 暴露 {@code setStatus()} 等于把状态机交给所有调用方维护。
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "symbol", nullable = false, length = 32)
    private String symbol;

    @Column(name = "quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    /**
     * 用 {@code EnumType.STRING} 而不是默认的 {@code ORDINAL}。
     * ORDINAL 存的是枚举下标，将来在枚举中间插入一个值，历史数据的含义就全错位了。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private OrderStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * 乐观锁版本号。
     * 两个请求同时撤同一张单时，后提交的那个会拿到 OptimisticLockingFailureException
     * 而不是静默覆盖前一个的结果——这是并发场景下「不丢更新」的最低成本保障。
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** JPA 规范要求实体必须有公开或受保护的无参构造器。业务代码请用 {@link #create}。 */
    protected Order() {
    }

    private Order(String symbol, BigDecimal quantity, BigDecimal unitPrice) {
        this.symbol = symbol;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.status = OrderStatus.NEW;
    }

    /**
     * 创建一张新订单。
     *
     * @param symbol    标的代码
     * @param quantity  数量，必须为正
     * @param unitPrice 单价，必须为正
     * @return 状态为 {@link OrderStatus#NEW} 的新订单
     */
    public static Order create(String symbol, BigDecimal quantity, BigDecimal unitPrice) {
        return new Order(symbol, quantity, unitPrice);
    }

    /**
     * 撤单。
     *
     * @throws BusinessException 当订单已处于终态（{@code FILLED} 或 {@code CANCELLED}）时
     */
    public void cancel() {
        if (status.isTerminal()) {
            throw new BusinessException(
                    ErrorCode.ORDER_NOT_CANCELLABLE,
                    "订单 %d 当前状态为 %s，不允许撤单".formatted(id, status));
        }
        this.status = OrderStatus.CANCELLED;
    }

    /** 成交。语义与 {@link #cancel()} 对称，留给后续扩展。 */
    public void fill() {
        if (status.isTerminal()) {
            throw new BusinessException(
                    ErrorCode.ORDER_NOT_CANCELLABLE,
                    "订单 %d 当前状态为 %s，不允许成交".formatted(id, status));
        }
        this.status = OrderStatus.FILLED;
    }

    /** 成交金额 = 数量 × 单价。 */
    public BigDecimal totalAmount() {
        return quantity.multiply(unitPrice);
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getSymbol() {
        return symbol;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    /**
     * 基于业务主键（id）判等。
     * 未持久化（id 为 null）时退化为引用相等——两个都还没入库的实例不应被视为同一张单。
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Order other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "Order{id=%d, symbol='%s', quantity=%s, unitPrice=%s, status=%s}"
                .formatted(id, symbol, quantity, unitPrice, status);
    }
}
