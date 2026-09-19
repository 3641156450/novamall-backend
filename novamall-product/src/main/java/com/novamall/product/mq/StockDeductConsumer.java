package com.novamall.product.mq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novamall.product.service.ProductService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 扣减库存消息消费者。
 *
 * <h3>为什么必须手动 ACK？</h3>
 * <p>默认的 auto ACK 模式下，RabbitMQ 一把消息推给消费者就认为它处理完了、直接删除。
 * 如果此时消费者刚拿到消息就宕机，消息就永久丢失了。
 * 手动 ACK 是"业务真的做完了"才告诉 Broker 可以删。</p>
 *
 * <h3>为什么消费端还要做幂等？</h3>
 * <p>即使生产端做了本地消息表，消费端仍可能收到重复消息：
 * <ul>
 *   <li>消费者处理成功但 ACK 时网络断了 → Broker 以为没消费，重新投递</li>
 *   <li>消费者处理成功但还没 ACK 就重启了 → 同上</li>
 * </ul>
 * 所以：<b>发送端可靠 + 消费端幂等</b>，两者缺一不可。
 * 本项目用 Redis 记录已处理过的 messageId（这里用订单号）来做去重。</p>
 *
 * <h3>失败怎么处理？</h3>
 * <p>不 requeue，直接 nack 让消息进入死信队列，由补偿任务或人工处理。
 * 如果无脑 requeue，一条永远处理不了的消息会把整个队列堵死。</p>
 *
 * @author NovaMall
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockDeductConsumer {

    private final ProductService productService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String CONSUMED_KEY = "mq:consumed:";

    @RabbitListener(queues = "novamall.stock.deduct.queue", ackMode = "MANUAL")
    public void onMessage(Message message,
                          Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            JsonNode json = objectMapper.readTree(body);
            String orderNo = json.path("orderNo").asText();

            // ---------- 幂等去重 ----------
            String consumedKey = CONSUMED_KEY + orderNo;
            Boolean firstTime = redisTemplate.opsForValue()
                    .setIfAbsent(consumedKey, "1", Duration.ofDays(7));
            if (!Boolean.TRUE.equals(firstTime)) {
                log.info("[Stock] 重复消息已忽略 orderNo={}", orderNo);
                channel.basicAck(deliveryTag, false);
                return;
            }

            // ---------- 扣库存 ----------
            JsonNode items = json.path("items");
            for (JsonNode item : items) {
                long productId = item.path("productId").asLong();
                int quantity = item.path("quantity").asInt();

                int rows = productService.deductStock(productId, quantity, null);
                if (rows == 0) {
                    // 库存不足：把幂等标记删掉，让消息可以重投（也许之后补货了）
                    redisTemplate.delete(consumedKey);
                    log.error("[Stock] 扣减失败，库存不足 productId={} qty={} orderNo={}",
                            productId, quantity, orderNo);
                    // requeue=false → 进死信队列，等待人工/补偿处理
                    channel.basicNack(deliveryTag, false, false);
                    return;
                }
                log.info("[Stock] 扣减成功 productId={} qty={} orderNo={}", productId, quantity, orderNo);
            }

            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("[Stock] 消费异常，消息转入死信队列 body={}", body, e);
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
