package com.novamall.job.task;

import com.novamall.common.result.R;
import com.novamall.job.client.ProductJobClient;
import com.novamall.job.util.TaskLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 缓存预热任务。
 *
 * <h3>什么是缓存预热？</h3>
 * <p>系统刚启动（或大促开始前）时 Redis 是空的。
 * 此时流量直接打进来，所有请求都会缓存未命中 → 全部打到数据库，
 * 这就是<b>缓存冷启动雪崩</b>。
 * 预热就是提前把热点数据加载到缓存里，让系统一上来就是"热"的。</p>
 *
 * <h3>预热哪些数据？</h3>
 * <ul>
 *   <li>首页/活动页的商品列表（访问量最大的那几十个）</li>
 *   <li>爆款商品详情</li>
 *   <li>布隆过滤器的全部合法 ID</li>
 *   <li>秒杀活动的库存</li>
 * </ul>
 *
 * <h3>三个注意点</h3>
 * <ul>
 *   <li>分页做，一次拉百万行会把内存打满</li>
 *   <li>预热失败不能影响服务启动（本项目 try-catch 降级）</li>
 *   <li>触发时机：容器启动钩子 / 定时任务 / 大促前手动触发</li>
 * </ul>
 *
 * @author NovaMall
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheWarmUpTask {

    private final ProductJobClient productJobClient;
    private final TaskLock taskLock;

    /** 每天凌晨 3 点预热一次（cron：秒 分 时 日 月 周） */
    @Scheduled(cron = "0 0 3 * * ?")
    public void warmUpDaily() {
        taskLock.runOnce("cache-warmup", Duration.ofMinutes(10), this::doWarmUp);
    }

    private void doWarmUp() {
        long start = System.currentTimeMillis();
        try {
            R<String> result = productJobClient.warmUp();
            log.info("[Job] 缓存预热完成 result={} cost={}ms",
                    result == null ? "null" : result.getData(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("[Job] 缓存预热失败", e);
        }
    }

    /**
     * 启动时预热一次。
     *
     * <p>为什么用 ApplicationRunner 而不是 @Scheduled(initialDelay)？
     * 因为要先等 Nacos 注册完成、Feign 能解析到服务地址。
     * ApplicationRunner 在容器就绪后执行，时机更可控。</p>
     */
    @Bean
    public ApplicationRunner warmUpOnStart() {
        return args -> {
            // 延迟 20 秒，等服务注册到 Nacos
            new Thread(() -> {
                try {
                    Thread.sleep(20_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                taskLock.runOnce("cache-warmup", Duration.ofMinutes(10), this::doWarmUp);
            }, "warmup-on-start").start();
        };
    }
}
