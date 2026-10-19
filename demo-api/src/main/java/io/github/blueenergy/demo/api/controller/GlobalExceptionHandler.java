package io.github.blueenergy.demo.api.controller;

import io.github.blueenergy.demo.common.dto.ApiErrorResponse;
import io.github.blueenergy.demo.common.error.BusinessException;
import io.github.blueenergy.demo.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

/**
 * 全局异常处理：把各类异常翻译成统一结构的 HTTP 响应。
 *
 * <p>集中处理而不是在每个 controller 里 try-catch，好处是错误响应结构必然一致，
 * 且新增接口不会因为忘记处理而泄漏堆栈。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 可预期的业务异常：按错误码自带的状态返回。 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiErrorResponse> handleBusiness(BusinessException ex,
                                                           HttpServletRequest request) {
        ErrorCode code = ex.getErrorCode();
        // 4xx 是调用方的问题，用 WARN 且不打堆栈；5xx 才需要堆栈来排查
        if (code.httpStatus() >= 500) {
            log.error("业务异常 code={} path={}", code.code(), request.getRequestURI(), ex);
        } else {
            log.warn("业务异常 code={} path={} message={}",
                    code.code(), request.getRequestURI(), ex.getMessage());
        }
        return ResponseEntity.status(code.httpStatus())
                .body(ApiErrorResponse.of(code.code(), ex.getMessage(), request.getRequestURI()));
    }

    /** Bean Validation 失败：把字段级明细一并返回，方便调用方定位。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex,
                                                             HttpServletRequest request) {
        List<ApiErrorResponse.FieldViolation> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiErrorResponse.FieldViolation(fe.getField(), fe.getDefaultMessage()))
                .toList();

        log.warn("参数校验失败 path={} violations={}", request.getRequestURI(), details);

        return ResponseEntity.badRequest().body(ApiErrorResponse.ofValidation(
                ErrorCode.VALIDATION_FAILED.code(),
                ErrorCode.VALIDATION_FAILED.defaultMessage(),
                request.getRequestURI(),
                details));
    }

    /** 请求体不是合法 JSON。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadable(HttpMessageNotReadableException ex,
                                                             HttpServletRequest request) {
        log.warn("请求体解析失败 path={}", request.getRequestURI());
        return ResponseEntity.badRequest().body(ApiErrorResponse.of(
                ErrorCode.VALIDATION_FAILED.code(),
                "请求体不是合法的 JSON",
                request.getRequestURI()));
    }

    /**
     * 路径变量或查询参数类型不匹配，例如 {@code GET /api/v1/orders/abc}。
     *
     * <p>没有这个处理器时，{@code MethodArgumentTypeMismatchException} 会落到下面的
     * 兜底分支，被当成「未预期的服务端错误」返回 500。后果不只是状态码不对：
     * 一次调用方拼错 URL 会污染 5xx 错误率指标，可能触发「错误率超标」告警，
     * 甚至让金丝雀分析把一次无害的探测判成失败而回滚一个完全正常的版本。
     *
     * <p>判断标准很简单：这是调用方传错了参数，不是服务端出了故障，所以是 4xx。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                               HttpServletRequest request) {
        String detail = "参数 '%s' 的值 '%s' 类型不正确".formatted(ex.getName(), ex.getValue());
        log.warn("参数类型不匹配 path={} parameter={} value={}",
                request.getRequestURI(), ex.getName(), ex.getValue());

        return ResponseEntity.badRequest().body(ApiErrorResponse.ofValidation(
                ErrorCode.VALIDATION_FAILED.code(),
                detail,
                request.getRequestURI(),
                List.of(new ApiErrorResponse.FieldViolation(String.valueOf(ex.getName()), detail))));
    }

    /**
     * 乐观锁冲突：并发修改同一订单。
     *
     * <p>返回 409 而不是 500 —— 这是并发下的正常结果，调用方重试即可，
     * 不是服务端故障。把它算进 5xx 会污染错误率指标、误导告警。
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> handleOptimisticLock(OptimisticLockingFailureException ex,
                                                                 HttpServletRequest request) {
        log.warn("乐观锁冲突 path={}", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiErrorResponse.of(
                ErrorCode.ORDER_NOT_CANCELLABLE.code(),
                "订单已被其他请求修改，请重试",
                request.getRequestURI()));
    }

    /**
     * 兜底。
     *
     * <p>刻意不把 {@code ex.getMessage()} 返回给调用方：内部异常消息可能包含
     * 表名、SQL 片段、文件路径等敏感信息。对外只给一个可追踪的通用消息，
     * 细节留在服务端日志里。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex,
                                                             HttpServletRequest request) {
        log.error("未预期异常 path={}", request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiErrorResponse.of(
                ErrorCode.INTERNAL_ERROR.code(),
                ErrorCode.INTERNAL_ERROR.defaultMessage(),
                request.getRequestURI()));
    }
}
