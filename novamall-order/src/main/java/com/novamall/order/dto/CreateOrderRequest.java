package com.novamall.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 下单请求。
 *
 * @author NovaMall
 */
@Data
public class CreateOrderRequest {

    /** 幂等令牌：下单前先调 /api/order/token 获取 */
    @NotEmpty(message = "缺少幂等令牌")
    private String token;

    @NotNull(message = "收货地址不能为空")
    private Long addressId;

    @Valid
    @NotEmpty(message = "商品列表不能为空")
    private List<Item> items;

    /** 备注 */
    private String remark;

    @Data
    public static class Item {
        @NotNull(message = "商品ID不能为空")
        private Long productId;

        @NotNull(message = "数量不能为空")
        @Min(value = 1, message = "数量至少为 1")
        private Integer quantity;
    }
}
