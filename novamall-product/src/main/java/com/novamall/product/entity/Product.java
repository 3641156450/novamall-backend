package com.novamall.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品表。
 *
 * <p>字段设计要点：
 * <ul>
 *   <li>price 用 BigDecimal 而不是 double——金额计算用浮点数会丢精度
 *       （0.1 + 0.2 != 0.3），金融/电商场景必须用 BigDecimal，且要用 String 构造器</li>
 *   <li>version 字段用于乐观锁扣库存，避免超卖</li>
 * </ul>
 *
 * @author NovaMall
 */
@Data
@TableName("t_product")
public class Product {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;

    private String subtitle;

    private Long categoryId;

    /** 主图 URL */
    private String mainImage;

    private BigDecimal price;

    /** 原价，用于展示划线价 */
    private BigDecimal originalPrice;

    private Integer stock;

    /** 销量（冗余字段，避免每次 count 订单表） */
    private Integer sales;

    /** 上架状态：1 上架，0 下架 */
    private Integer status;

    /** 乐观锁版本号 */
    private Integer version;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
