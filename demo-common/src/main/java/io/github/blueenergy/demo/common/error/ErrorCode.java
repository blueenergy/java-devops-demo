package io.github.blueenergy.demo.common.error;

/**
 * 业务错误码。
 *
 * <p>每个错误码自带 HTTP 状态与一个稳定的机器可读 code。
 * 客户端应当依赖 {@code code} 做分支，而不是解析 message —— message 会随文案调整而变。
 *
 * <p>这里刻意用 int 而不是 Spring 的 {@code HttpStatus}：
 * demo-common 要保持框架无关，这样它可以被非 Web 的调用方（批处理、消息消费者）复用。
 */
public enum ErrorCode {

    /** 订单不存在。 */
    ORDER_NOT_FOUND(404, "ORDER_NOT_FOUND", "订单不存在"),

    /** 订单当前状态不允许撤单（例如已成交）。 */
    ORDER_NOT_CANCELLABLE(409, "ORDER_NOT_CANCELLABLE", "订单当前状态不允许撤单"),

    /** 请求参数校验失败。 */
    VALIDATION_FAILED(400, "VALIDATION_FAILED", "请求参数校验失败"),

    /** 故障注入（仅用于演示金丝雀回滚，见 FaultInjectionFilter）。 */
    INJECTED_FAULT(500, "INJECTED_FAULT", "服务端注入的模拟故障"),

    /** 未预期的服务端错误。 */
    INTERNAL_ERROR(500, "INTERNAL_ERROR", "服务端内部错误");

    private final int httpStatus;
    private final String code;
    private final String defaultMessage;

    ErrorCode(int httpStatus, String code, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
