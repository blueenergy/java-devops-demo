package io.github.blueenergy.demo.core.repository;

import io.github.blueenergy.demo.core.domain.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

/**
 * 订单仓储。
 *
 * <p>继承 {@code JpaRepository} 直接获得 CRUD 与分页能力，不写实现类。
 */
public interface OrderRepository extends JpaRepository<Order, Long> {

    /** 按标的查询，最近创建的在前。 */
    List<Order> findBySymbolOrderByCreatedAtDesc(String symbol);

    /**
     * 统计某标的下金额超过阈值的订单数。
     *
     * <p>用 {@code @Query} 而不是派生查询名：方法名派生（{@code countBySymbolAndQuantityMultiplyUnitPriceGreaterThan}）
     * 在这个复杂度下已经不可读了，而且金额是算出来的，派生查询表达不了。
     */
    @Query("""
            SELECT COUNT(o) FROM Order o
            WHERE o.symbol = :symbol
              AND o.quantity * o.unitPrice > :threshold
            """)
    long countLargeOrders(@Param("symbol") String symbol, @Param("threshold") BigDecimal threshold);
}
