package com.novamall.order.client.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 商品快照 DTO（防腐层）。
 *
 * <p>为什么不直接依赖 novamall-product 模块的 Product 实体？
 * 因为那会把两个微服务在<b>编译期</b>耦合在一起：
 * 商品服务改了字段，订单服务就得跟着改、跟着重新发版，
 * 微服务"独立部署"的意义就没了。</p>
 *
 * <p>正确做法是各服务定义自己需要的那份视图（DTO / 防腐层 ACL），
 * 契约变更通过接口版本（/v1 /v2）来兼容。</p>
 *
 * @author NovaMall
 */
@Data
public class ProductDTO {

    private Long id;
    private String name;
    private String subtitle;
    private String mainImage;
    private BigDecimal price;
    private Integer stock;
    private Integer status;
}
