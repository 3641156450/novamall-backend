package com.novamall.job;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 任务调度服务启动类。
 *
 * @author NovaMall
 */
@EnableScheduling
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.novamall")
@SpringBootApplication(scanBasePackages = "com.novamall")
public class JobApplication {

    public static void main(String[] args) {
        SpringApplication.run(JobApplication.class, args);
    }
}
