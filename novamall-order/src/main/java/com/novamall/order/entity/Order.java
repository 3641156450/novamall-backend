package com.novamall.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单主表。
 *
 * <p>订单号 order_no 建了唯一索引，这是防重复下单的最后一道防线。
 * 哪怕幂等 token 失效了，数据库也不会出现两笔一样的订单。</p>
 *
 * @author NovaMall
 */
@Data
@TableName("t_order")
public class Order {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 业务订单号（对外暴露，不用自增 ID） */
    private String orderNo;

    private Long userId;

    /** 订单总金额 */
    private BigDecimal totalAmount;

    /** 实付金额（扣优惠券后） */
    private BigDecimal payAmount;

    /**
     * 订单状态：0-待付款 1-已付款 2-已发货 3-已完成 4-已关闭
     * 状态机只允许单向流转，见 OrderStatusEnum
     */
    private Integer status;

    /** 支付时间 */
    private LocalDateTime payTime;

    /** 关闭时间（超时未支付 / 用户取消） */
    private LocalDateTime closeTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
