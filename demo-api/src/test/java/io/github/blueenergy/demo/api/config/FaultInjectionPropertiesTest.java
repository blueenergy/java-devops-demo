package io.github.blueenergy.demo.api.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link FaultInjectionProperties} 的构造期校验。
 *
 * <p>配置错误应该在启动时立刻失败，而不是等到某个请求进来才发现。
 * 一个写错的 {@code error-rate: 50}（想表达 50%）如果被静默接受，
 * 会让 100% 的请求失败 —— 这种故障排查起来极其耗时。
 */
@DisplayName("FaultInjectionProperties")
class FaultInjectionPropertiesTest {

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 0.5, 1.0})
    @DisplayName("合法错误率被接受")
    void shouldAcceptValidErrorRate(double rate) {
        assertThat(new FaultInjectionProperties(true, rate, 0).errorRate()).isEqualTo(rate);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1.1, 50.0})
    @DisplayName("越界错误率在构造时即抛异常")
    void shouldRejectOutOfRangeErrorRate(double rate) {
        assertThatThrownBy(() -> new FaultInjectionProperties(true, rate, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("error-rate");
    }

    @Test
    @DisplayName("负数延迟被拒绝")
    void shouldRejectNegativeLatency() {
        assertThatThrownBy(() -> new FaultInjectionProperties(true, 0.0, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("latency-ms");
    }

    @Test
    @DisplayName("开关关闭时不激活，即便错误率非零")
    void shouldNotBeActiveWhenDisabled() {
        assertThat(new FaultInjectionProperties(false, 1.0, 100).isActive()).isFalse();
    }

    @Test
    @DisplayName("开关打开但参数全零时也不激活——避免无谓的过滤开销")
    void shouldNotBeActiveWhenNoEffectConfigured() {
        assertThat(new FaultInjectionProperties(true, 0.0, 0).isActive()).isFalse();
    }

    @Test
    @DisplayName("开关打开且有任一效果时激活")
    void shouldBeActiveWhenEffectConfigured() {
        assertThat(new FaultInjectionProperties(true, 0.1, 0).isActive()).isTrue();
        assertThat(new FaultInjectionProperties(true, 0.0, 50).isActive()).isTrue();
    }
}
