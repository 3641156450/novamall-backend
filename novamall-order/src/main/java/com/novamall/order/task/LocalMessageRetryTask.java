package com.novamall.order.task;

import com.novamall.order.entity.LocalMessage;
import com.novamall.order.mapper.LocalMessageMapper;
import com.novamall.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 本地消息表重投任务（可靠消息最终一致性的"最后一道保险"）。
 *
 * <p>为什么要这个任务？回顾下单流程：
 * <pre>
 *   事务提交 → 发 MQ → 成功则标记"已发送"
 *                    → 失败则保持"待发送"
 * </pre>
 * 失败的情况包括：MQ 宕机、网络抖动、应用重启……消息还躺在数据库里，
 * 但没有人再去管它，库存就永远扣不掉了。
 * 所以需要定时任务不断扫描"待发送"的消息重投，直到成功或超过重试上限。</p>
 *
 * <p>重试策略用<b>指数退避</b>：第 n 次失败后等 2^n 秒再试。
 * 如果 MQ 只是短暂抖动，第一次重试就能成功；
 * 如果 MQ 真的挂了，指数退避能避免每秒几千次无效重试把系统拖垮。</p>
 *
 * @author NovaMall
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalMessageRetryTask {

    private final LocalMessageMapper localMessageMapper;
    private final OrderService orderService;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String LOCK_KEY = "lock:task:local-message";
    private static final Duration LOCK_TTL = Duration.ofMinutes(2);
    private static final int BATCH_SIZE = 100;

    /** 每 30 秒扫一次 */
    @Scheduled(fixedDelay = 30_000, initialDelay = 15_000)
    public void retryPendingMessages() {
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(LOCK_KEY, "1", LOCK_TTL);
        if (!Boolean.TRUE.equals(acquired)) {
            return;
        }
        try {
            List<LocalMessage> pending = localMessageMapper.selectPending(LocalDateTime.now(), BATCH_SIZE);
            for (LocalMessage message : pending) {
                log.warn("[Task] 重投消息 bizKey={} 第 {} 次", message.getBizKey(), message.getRetryCount());
                orderService.doSend(message);
            }
        } finally {
            redisTemplate.delete(LOCK_KEY);
        }
    }
}
