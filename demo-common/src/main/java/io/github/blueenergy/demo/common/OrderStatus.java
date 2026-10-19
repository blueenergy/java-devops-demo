package io.github.blueenergy.demo.common;

/**
 * 订单状态。
 *
 * <p>状态机很简单，只有两条合法迁移：
 * <pre>
 *   NEW ──→ FILLED     （成交）
 *   NEW ──→ CANCELLED  （撤单）
 * </pre>
 * FILLED 与 CANCELLED 都是终态，不可再迁移。
 *
 * <p>放在 demo-common 而不是 demo-core：它既出现在 API 响应里（对外契约），
 * 又要被 demo-core 的 JPA 实体使用。放 common 让 core 单向依赖，不会反向。
 */
public enum OrderStatus {

    /** 已创建，等待成交或撤单。 */
    NEW,

    /** 已成交，终态。 */
    FILLED,

    /** 已撤销，终态。 */
    CANCELLED;

    /** 是否为终态（不可再迁移）。 */
    public boolean isTerminal() {
        return this != NEW;
    }
}
