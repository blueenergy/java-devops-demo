package io.github.blueenergy.demo.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 创建订单的请求体。
 *
 * <p>用 record 而不是 class：DTO 没有行为，只需要承载数据。
 * record 自带不可变性与 equals/hashCode/toString，少写几十行样板代码。
 *
 * <p>金额与数量用 {@link BigDecimal} 而不是 double —— 浮点数表示不了 0.1，
 * 金融场景下累加会产生误差。这是面试里被问到「为什么不用 double」时的标准答案。
 *
 * @param symbol    标的代码，非空、最长 32 字符
 * @param quantity  数量，必须为正
 * @param unitPrice 单价，必须为正
 */
public record CreateOrderRequest(

        @NotBlank(message = "symbol 不能为空")
        @Size(max = 32, message = "symbol 长度不能超过 32")
        String symbol,

        @NotNull(message = "quantity 不能为空")
        @Positive(message = "quantity 必须为正数")
        BigDecimal quantity,

        @NotNull(message = "unitPrice 不能为空")
        @Positive(message = "unitPrice 必须为正数")
        BigDecimal unitPrice
) {
}
