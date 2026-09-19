package com.novamall.seckill;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 秒杀服务启动类。
 *
 * @author NovaMall
 */
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.novamall")
@SpringBootApplication(scanBasePackages = "com.novamall")
public class SeckillApplication {

    public static void main(String[] args) {
        SpringApplication.run(SeckillApplication.class, args);
    }
}
