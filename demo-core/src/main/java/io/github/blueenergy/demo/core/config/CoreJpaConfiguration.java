package io.github.blueenergy.demo.core.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * demo-core 的持久化配置。
 *
 * <h2>为什么不把这两个注解写在启动类上</h2>
 * 写在启动类（{@code OrderDemoApplication}）上时，它们会跟着启动类一起被
 * {@code @WebMvcTest} 这类测试切片加载 —— 因为切片始终以 {@code @SpringBootConfiguration}
 * 作为配置源。而 Web 切片里没有 JPA 自动配置，Spring Data 仍会尝试创建仓储 bean，
 * 于是报：
 * <pre>
 *   NoSuchBeanDefinitionException: No bean named 'entityManagerFactory' available
 * </pre>
 * 结果是所有控制器切片测试一起失败，而报错信息完全指向 JPA，与控制器毫无关系，
 * 排查方向会被带偏。
 *
 * <p>放进 demo-core 的 {@code @Configuration} 类后：
 * <ul>
 *   <li>完整应用启动时，{@code scanBasePackages} 会扫到它，JPA 正常装配</li>
 *   <li>测试切片会用 {@code TypeExcludeFilter} 把普通 {@code @Configuration} 排除掉，
 *       持久化配置不会污染 Web 层测试</li>
 * </ul>
 *
 * <p>附带的好处是职责更清晰：仓储和实体在哪个包下，本来就该由拥有它们的模块自己声明，
 * 而不是让启动类去了解其它模块的内部包结构。
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaRepositories(basePackages = "io.github.blueenergy.demo.core.repository")
@EntityScan(basePackages = "io.github.blueenergy.demo.core.domain")
public class CoreJpaConfiguration {
}
