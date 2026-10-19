package io.github.blueenergy.demo.common.error;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BusinessException 与 ErrorCode")
class BusinessExceptionTest {

    @Test
    @DisplayName("不传消息时使用错误码的默认文案")
    void shouldFallBackToDefaultMessage() {
        BusinessException ex = new BusinessException(ErrorCode.ORDER_NOT_FOUND);

        assertThat(ex.getMessage()).isEqualTo(ErrorCode.ORDER_NOT_FOUND.defaultMessage());
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ORDER_NOT_FOUND);
    }

    @Test
    @DisplayName("传消息时覆盖默认文案，但错误码不变")
    void shouldOverrideMessage() {
        BusinessException ex = new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单 42 不存在");

        assertThat(ex.getMessage()).isEqualTo("订单 42 不存在");
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ORDER_NOT_FOUND);
    }

    @Test
    @DisplayName("错误码为 null 时立即失败，而不是等到序列化时才 NPE")
    void shouldRejectNullErrorCode() {
        assertThatThrownBy(() -> new BusinessException(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("errorCode");
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    @DisplayName("每个错误码都有合法的 HTTP 状态、非空的 code 与文案")
    void everyErrorCodeIsWellFormed(ErrorCode code) {
        assertThat(code.httpStatus()).isBetween(400, 599);
        assertThat(code.code()).isNotBlank().isUpperCase();
        assertThat(code.defaultMessage()).isNotBlank();
    }

    @Test
    @DisplayName("客户端错误是 4xx，服务端错误是 5xx")
    void httpStatusCategoriesAreCorrect() {
        assertThat(ErrorCode.ORDER_NOT_FOUND.httpStatus()).isEqualTo(404);
        assertThat(ErrorCode.ORDER_NOT_CANCELLABLE.httpStatus()).isEqualTo(409);
        assertThat(ErrorCode.VALIDATION_FAILED.httpStatus()).isEqualTo(400);
        assertThat(ErrorCode.INTERNAL_ERROR.httpStatus()).isEqualTo(500);
    }
}
