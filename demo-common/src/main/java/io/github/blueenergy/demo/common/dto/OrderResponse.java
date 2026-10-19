package io.github.blueenergy.demo.common.dto;

import io.github.blueenergy.demo.common.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 订单的对外表示。
 *
 * <p>刻意与 demo-core 的 JPA 实体分开：实体是持久化模型，会随表结构演进而变；
 * 本 record 是对外契约，一旦发布就要保持兼容。
 * 直接返回实体（很多人图省事这么做）会让一次内部重构变成一次破坏性 API 变更，
 * 还会把懒加载代理、内部字段等泄漏出去。
 *
 * @param id        订单 ID
 * @param symbol    标的代码
 * @param quantity  数量
 * @param unitPrice 单价
 * @param status    当前状态
 * @param createdAt 创建时间
 * @param updatedAt 最后更新时间
 */
public record OrderResponse(
        Long id,
        String symbol,
        BigDecimal quantity,
        BigDecimal unitPrice,
        OrderStatus status,
        Instant createdAt,
        Instant updatedAt
) {
}
