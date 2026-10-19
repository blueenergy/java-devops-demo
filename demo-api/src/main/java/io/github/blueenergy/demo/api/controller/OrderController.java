package io.github.blueenergy.demo.api.controller;

import io.github.blueenergy.demo.common.dto.CreateOrderRequest;
import io.github.blueenergy.demo.common.dto.OrderResponse;
import io.github.blueenergy.demo.core.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单接口。
 *
 * <p>控制器只做三件事：路由、参数绑定与校验、把结果交给框架序列化。
 * 任何 if/else 的业务判断都不应该出现在这一层 —— 它不可复用、也难测。
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * 创建订单。
     *
     * <p>{@code @Valid} 触发 demo-common 里 DTO 上的 Bean Validation 约束；
     * 校验失败会抛 {@code MethodArgumentNotValidException}，
     * 由 {@code GlobalExceptionHandler} 统一转成 400 + 字段明细。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse create(@Valid @RequestBody CreateOrderRequest request) {
        return orderService.create(request);
    }

    /** 按 ID 查询订单。 */
    @GetMapping("/{id}")
    public OrderResponse getById(@PathVariable Long id) {
        return orderService.getById(id);
    }

    /**
     * 撤单。
     *
     * <p>用 POST 而不是 DELETE：撤单不是删除资源，而是把订单迁移到 CANCELLED 状态，
     * 订单记录必须保留以供审计。用 DELETE 会误导调用方以为数据没了。
     */
    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(@PathVariable Long id) {
        return orderService.cancel(id);
    }
}
