package io.github.blueenergy.demo.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * 统一错误响应体。
 *
 * <p>所有非 2xx 响应都是这个结构，客户端只需实现一套解析逻辑。
 *
 * <p>{@code details} 只在参数校验失败时出现，所以标了
 * {@link JsonInclude.Include#NON_EMPTY} 避免在正常错误里输出 {@code "details": []}。
 *
 * @param code      机器可读的错误码（对应 {@code ErrorCode.code()}），客户端应依赖它做分支
 * @param message   人类可读的描述，仅用于展示与排查，不要用于分支判断
 * @param path      出错的请求路径
 * @param timestamp 出错时间
 * @param details   字段级校验错误明细，仅校验失败时有值
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiErrorResponse(
        String code,
        String message,
        String path,
        Instant timestamp,
        List<FieldViolation> details
) {

    /**
     * 单个字段的校验失败明细。
     *
     * @param field   字段名
     * @param message 该字段的校验失败原因
     */
    public record FieldViolation(String field, String message) {
    }

    /** 不含字段明细的常规错误。 */
    public static ApiErrorResponse of(String code, String message, String path) {
        return new ApiErrorResponse(code, message, path, Instant.now(), List.of());
    }

    /** 带字段明细的校验错误。 */
    public static ApiErrorResponse ofValidation(String code, String message, String path,
                                                List<FieldViolation> details) {
        return new ApiErrorResponse(code, message, path, Instant.now(), details);
    }
}
