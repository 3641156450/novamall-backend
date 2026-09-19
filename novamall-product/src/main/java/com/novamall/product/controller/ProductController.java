package com.novamall.product.controller;

import com.novamall.common.result.PageResult;
import com.novamall.common.result.R;
import com.novamall.product.entity.Product;
import com.novamall.product.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 商品接口。
 *
 * <p>注意：商品详情接口是公开的（在网关白名单里），不需要登录，
 * 因为它要被搜索引擎抓取、被分享链接直接打开。
 * 但详情页里的"加购物车""立即购买"按钮必须登录——这是权限设计的边界。</p>
 *
 * @author NovaMall
 */
@RestController
@RequestMapping("/api/product")
@RequiredArgsConstructor
@Tag(name = "商品中心")
@Slf4j
public class ProductController {

    private final ProductService productService;

    @GetMapping("/{id}")
    @Operation(summary = "商品详情（三级缓存）")
    public R<Product> detail(@PathVariable Long id) {
        return R.ok(productService.getById(id));
    }

    @GetMapping("/list")
    @Operation(summary = "商品分页列表")
    public R<PageResult<Product>> list(@RequestParam(required = false) Long categoryId,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(defaultValue = "1") long pageNo,
                                       @RequestParam(defaultValue = "10") long pageSize) {
        // 防御：pageSize 不限制的话，前端传个 100000 就能把数据库拖垮
        long size = Math.min(pageSize, 100);
        return R.ok(productService.page(categoryId, keyword, pageNo, size));
    }

    @GetMapping("/batch")
    @Operation(summary = "批量查询（内部服务调用）")
    public R<List<Product>> batch(@RequestParam List<Long> ids) {
        return R.ok(productService.listByIds(ids));
    }

    @PostMapping("/{id}/stock/deduct")
    @Operation(summary = "扣减库存（内部服务调用，需内网隔离）")
    public R<Integer> deduct(@PathVariable Long id,
                             @RequestParam(defaultValue = "1") Integer num,
                             @RequestParam(required = false) Integer version) {
        int rows = productService.deductStock(id, num, version);
        return R.ok(rows);
    }

    @PostMapping("/cache/warmup")
    @Operation(summary = "预热布隆过滤器")
    public R<String> warmUp() {
        productService.warmUpBloomFilter();
        return R.ok("预热完成");
    }
}
