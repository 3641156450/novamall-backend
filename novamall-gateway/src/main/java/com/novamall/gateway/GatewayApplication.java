package com.novamall.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 网关服务启动类。
 *
 * <p>面试常问：<b>Spring Cloud Gateway 和 Zuul 有什么区别？</b>
 * <ul>
 *   <li>Gateway 基于 WebFlux + Netty，是异步非阻塞的；Zuul 1.x 是同步阻塞（一个请求一个线程），
 *       在高并发下线程数会暴涨。</li>
 *   <li>Zuul 2.x 改成了异步但一直没进 Spring Cloud 官方主线，社区基本放弃。</li>
 *   <li>Gateway 的过滤器模型更清晰：pre（路由前）/ post（路由后），支持全局和路由两种粒度。</li>
 * </ul>
 *
 * <p>Gateway 的核心概念：Route（路由）/ Predicate（断言，决定请求走不走这条路由）/ Filter（过滤器）。</p>
 *
 * @author NovaMall
 */
@EnableDiscoveryClient
@SpringBootApplication(scanBasePackages = "com.novamall")
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
