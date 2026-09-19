package com.novamall.order.controller;

import com.novamall.common.result.R;
import com.novamall.order.dto.CreateOrderRequest;
import com.novamall.order.entity.Order;
import com.novamall.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 订单接口。
 *
 * @author NovaMall
 */
@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
@Tag(name = "订单中心")
public class OrderController {

    private final OrderService orderService;

    /**
     * 获取幂等令牌。
     *
     * <p>流程：前端在进入结算页时调这个接口拿到 token，
     * 提交订单时放在请求里，服务端消费一次即失效。
     * 这样即使用户连点 10 次，也只会成功 1 次。</p>
     */
    @GetMapping("/token")
    @Operation(summary = "获取下单幂等令牌")
    public R<String> token() {
        return R.ok(orderService.generateToken());
    }

    @PostMapping("/create")
    @Operation(summary = "创建订单")
    public R<String> create(@Valid @RequestBody CreateOrderRequest request) {
        return R.ok(orderService.createOrder(request));
    }

    @GetMapping("/{orderNo}")
    @Operation(summary = "订单详情")
    public R<Order> detail(@PathVariable String orderNo) {
        return R.ok(orderService.detail(orderNo));
    }

    @PostMapping("/{orderNo}/pay")
    @Operation(summary = "支付回调（模拟，实际由支付平台回调）")
    public R<Boolean> pay(@PathVariable String orderNo) {
        return R.ok(orderService.payCallback(orderNo));
    }

    @PostMapping("/{orderNo}/cancel")
    @Operation(summary = "取消订单并回补库存")
    public R<Boolean> cancel(@PathVariable String orderNo) {
        return R.ok(orderService.cancelOrder(
                com.novamall.common.context.UserContext.getUserId(), orderNo));
    }
}
