package io.github.blueenergy.demo.common.error;

import java.io.Serial;
import java.util.Objects;

/**
 * 业务异常：可预期、有明确错误码、应当被翻译成具体 HTTP 状态返回给调用方。
 *
 * <p>与 {@link RuntimeException} 的区别在于「可预期性」：
 * 抛出本异常表示「这是我知道会发生、并且知道该怎么告诉调用方的情况」。
 * 其它未捕获异常一律走 {@code INTERNAL_ERROR} 分支，不泄漏堆栈给客户端。
 *
 * <p>继承 RuntimeException 而非受检异常：业务校验失败在绝大多数场景下
 * 无法在调用点做有意义的恢复，强制 try-catch 只会污染签名。
 */
public class BusinessException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage());
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode 不能为空");
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
