package com.novamall.order.entity;

import lombok.Getter;

/**
 * 订单状态机。
 *
 * <p>状态流转只允许单向，禁止回退。这是防重复操作的重要手段：
 * 比如"支付回调"来了两次，第二次发现订单已经是"已付款"状态，直接忽略即可。</p>
 *
 * <pre>
 *   待付款(0) ──支付──> 已付款(1) ──发货──> 已发货(2) ──确认收货──> 已完成(3)
 *      │                   │
 *      └──超时/取消──> 已关闭(4) <──退款──┘
 * </pre>
 *
 * @author NovaMall
 */
@Getter
public enum OrderStatusEnum {

    WAIT_PAY(0, "待付款"),
    PAID(1, "已付款"),
    SHIPPED(2, "已发货"),
    FINISHED(3, "已完成"),
    CLOSED(4, "已关闭");

    private final int code;
    private final String desc;

    OrderStatusEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /** 判断是否可以从 from 流转到 to */
    public static boolean canTransfer(int from, int to) {
        if (from == to) {
            return false;
        }
        return switch (from) {
            case 0 -> to == 1 || to == 4;   // 待付款 → 已付款 / 已关闭
            case 1 -> to == 2 || to == 4;   // 已付款 → 已发货 / 已关闭(退款)
            case 2 -> to == 3;              // 已发货 → 已完成
            default -> false;               // 已完成、已关闭是终态
        };
    }
}
