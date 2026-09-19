package com.novamall.order.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * RabbitMQ 交换机/队列/死信队列声明。
 *
 * <h3>为什么要用死信队列（DLX）？</h3>
 * <p>消息消费失败时，如果一直 requeue 重投，会把整个队列堵死（后面的正常消息也出不来）。
 * 正确做法：失败 N 次后投递到死信交换机，落到死信队列，
 * 由人工或专门的补偿任务处理。这样既保证消息不丢，又不阻塞正常流量。</p>
 *
 * <h3>RabbitMQ 保证消息不丢的三个环节</h3>
 * <ol>
 *   <li><b>生产者 → Broker</b>：开启 publisher-confirms（confirm 模式），
 *       消息真正落盘后 Broker 才回 ACK；配合本地消息表做兜底重投。</li>
 *   <li><b>Broker 自身</b>：交换机、队列、消息三者都要设 durable=true 持久化，
 *       否则 Broker 重启消息就没了。</li>
 *   <li><b>Broker → 消费者</b>：关闭自动 ACK，改成手动 ACK，
 *       业务处理成功才 basicAck，失败则 basicNack。</li>
 * </ol>
 *
 * @author NovaMall
 */
@Configuration
public class RabbitMqConfig {

    /* ==================== 业务交换机 / 队列 ==================== */

    public static final String ORDER_EXCHANGE = "novamall.order.exchange";
    public static final String STOCK_QUEUE = "novamall.stock.deduct.queue";
    public static final String STOCK_ROUTING_KEY = "stock.deduct";

    /* ==================== 死信 ==================== */

    public static final String DLX_EXCHANGE = "novamall.dlx.exchange";
    public static final String DLX_QUEUE = "novamall.dlx.queue";
    public static final String DLX_ROUTING_KEY = "dlx";

    @Bean
    public DirectExchange orderExchange() {
        // durable=true, autoDelete=false
        return ExchangeBuilder.directExchange(ORDER_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue stockQueue() {
        Map<String, Object> args = new HashMap<>();
        // 消息变成死信后，转发到哪个交换机
        args.put("x-dead-letter-exchange", DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", DLX_ROUTING_KEY);
        // 队列长度上限，防止消费端全挂时消息无限堆积把磁盘写满
        args.put("x-max-length", 100_000);
        return QueueBuilder.durable(STOCK_QUEUE).withArguments(args).build();
    }

    @Bean
    public Binding stockBinding() {
        return BindingBuilder.bind(stockQueue()).to(orderExchange()).with(STOCK_ROUTING_KEY);
    }

    @Bean
    public DirectExchange dlxExchange() {
        return ExchangeBuilder.directExchange(DLX_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue dlxQueue() {
        return QueueBuilder.durable(DLX_QUEUE).build();
    }

    @Bean
    public Binding dlxBinding() {
        return BindingBuilder.bind(dlxQueue()).to(dlxExchange()).with(DLX_ROUTING_KEY);
    }
}
