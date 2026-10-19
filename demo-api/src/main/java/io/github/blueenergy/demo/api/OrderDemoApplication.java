package io.github.blueenergy.demo.api;

import io.github.blueenergy.demo.api.config.FaultInjectionProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 应用入口。
 *
 * <h2>为什么要显式写 scanBasePackages</h2>
 * 本类位于 {@code io.github.blueenergy.demo.api}，而 {@code @SpringBootApplication}
 * 默认只扫描「本类所在包及其子包」。demo-core 的服务与持久化配置都在
 * {@code io.github.blueenergy.demo.core} 下，默认扫描范围够不着 ——
 * 症状是启动时报 {@code No qualifying bean of type OrderService}。
 *
 * <p>多模块项目里这是最高频的踩坑点。三种解法：
 * <ol>
 *   <li>把启动类上移到公共父包 {@code io.github.blueenergy.demo}（简单，但两个模块的类混在同一包下）</li>
 *   <li>显式声明扫描范围（本类采用，意图最清楚，也能防止误扫到第三方包）</li>
 *   <li>用 {@code @AutoConfiguration} 把各模块的配置类导进来（适合做 starter，本场景过重）</li>
 * </ol>
 *
 * <h2>这里刻意「不写」什么</h2>
 * 没有 {@code @EnableJpaRepositories} 与 {@code @EntityScan}。
 * 这两个注解由 demo-core 的 {@code CoreJpaConfiguration} 声明 ——
 * 写在启动类上会让持久化配置泄漏进 {@code @WebMvcTest} 切片，
 * 导致控制器测试因为「找不到 entityManagerFactory」而整体失败。
 * 详见该类的注释。
 *
 * <h2>为什么用 @EnableConfigurationProperties 而不是 @ConfigurationPropertiesScan</h2>
 * 两者在完整应用里都能工作，但在测试切片下行为不同：
 * {@code @ConfigurationPropertiesScan} 走类路径扫描，切片注册的
 * {@code TypeExcludeFilter} 会把不属于本切片的配置类排除掉，
 * 于是 {@code FaultInjectionFilter} 拿不到它的配置对象、上下文启动失败。
 * {@code @EnableConfigurationProperties} 按类引用直接注册 bean 定义，不经过扫描。
 * 配置类数量少且明确时，显式列出比扫描更可预测。
 */
@SpringBootApplication(scanBasePackages = "io.github.blueenergy.demo")
@EnableConfigurationProperties(FaultInjectionProperties.class)
public class OrderDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderDemoApplication.class, args);
    }
}
