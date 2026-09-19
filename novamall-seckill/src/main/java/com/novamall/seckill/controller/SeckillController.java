package com.novamall.seckill.controller;

import com.novamall.common.result.R;
import com.novamall.seckill.service.SeckillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 秒杀接口。
 *
 * <p>注意：秒杀接口被限流拒绝时返回的不是 500 而是业务码 4029，
 * 前端要专门处理这个码，给用户"手慢了"的友好提示，
 * 而不是弹一个红色报错框。</p>
 *
 * @author NovaMall
 */
@RestController
@RequestMapping("/api/seckill")
@RequiredArgsConstructor
@Tag(name = "秒杀专区")
public class SeckillController {

    private final SeckillService seckillService;

    @PostMapping("/{activityId}/{productId}")
    @Operation(summary = "执行秒杀")
    public R<Map<String, Object>> seckill(@PathVariable Long activityId,
                                          @PathVariable Long productId) {
        return R.ok(seckillService.seckill(activityId, productId));
    }

    @GetMapping("/result")
    @Operation(summary = "轮询秒杀结果")
    public R<Map<String, Object>> result(@RequestParam String token) {
        return R.ok(seckillService.queryResult(token));
    }

    @GetMapping("/stock")
    @Operation(summary = "查询剩余库存")
    public R<Integer> stock(@RequestParam Long activityId, @RequestParam Long productId) {
        return R.ok(seckillService.remaining(activityId, productId));
    }

    /** 管理端接口：预热库存、开启活动（真实项目要加管理员鉴权） */
    @PostMapping("/admin/warmup")
    @Operation(summary = "预热库存（管理端）")
    public R<Boolean> warmUp(@RequestParam Long activityId,
                             @RequestParam Long productId,
                             @RequestParam(defaultValue = "100") int stock) {
        return R.ok(seckillService.warmUp(activityId, productId, stock));
    }

    @PostMapping("/admin/start")
    @Operation(summary = "开启活动（管理端）")
    public R<String> start(@RequestParam Long activityId,
                           @RequestParam(defaultValue = "600") long seconds) {
        seckillService.startActivity(activityId, seconds);
        return R.ok("活动已开启");
    }
}
