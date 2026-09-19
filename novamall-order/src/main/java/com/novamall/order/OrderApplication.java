package com.novamall.order;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 订单服务启动类。
 *
 * @author NovaMall
 */
@MapperScan("com.novamall.order.mapper")
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.novamall")
@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.novamall")
public class OrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    /**
     * 开启生产者确认（publisher confirm）。
     *
     * <p>不开这个，消息发出去就石沉大海——Broker 有没有收到你完全不知道。
     * 开了之后 Broker 会异步回一个 ACK/NACK，我们配合本地消息表做重投。</p>
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack && correlationData != null) {
                // 这里不直接重投——本地消息表里状态还是"待发送"，定时任务会捞出来重发
                org.slf4j.LoggerFactory.getLogger(OrderApplication.class)
                        .error("[MQ] 消息未到达 Broker id={} cause={}", correlationData.getId(), cause);
            }
        });
        return template;
    }
}
