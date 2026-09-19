package com.novamall.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 订单明细（一个订单可以包含多个商品）。
 *
 * <p>为什么要把商品信息（名称、单价）冗余进明细表？
 * 因为商品名称和价格会变。如果只存 product_id，
 * 一年后商家改了商品名和价格，用户看到的"历史订单"就跟着变了——
 * 这在财务和客服场景下是不可接受的（用户会说"我下单时明明是 99 元"）。
 * 所以订单必须<b>快照</b>下单瞬间的商品信息。</p>
 *
 * @author NovaMall
 */
@Data
@TableName("t_order_item")
public class OrderItem {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long orderId;

    private Long productId;

    /** 商品名快照 */
    private String productName;

    /** 商品主图快照 */
    private String productImage;

    /** 下单时的单价快照 */
    private BigDecimal price;

    private Integer quantity;

    /** 小计 = price * quantity */
    private BigDecimal totalAmount;
}
