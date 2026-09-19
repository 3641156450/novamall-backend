package com.novamall.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 认证服务启动类。
 *
 * <p>@SpringBootApplication 里显式指定 scanBasePackages 是必须的：
 * 默认的扫描范围是启动类所在的 com.novamall.auth 包，
 * 而公共切面、异常处理器在 com.novamall.common 包下，不扫就全都不生效。
 * 这是多模块工程最常见的一个坑。</p>
 *
 * @author NovaMall
 */
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.novamall")
@MapperScan("com.novamall.auth.mapper")
@SpringBootApplication(scanBasePackages = "com.novamall")
public class AuthApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthApplication.class, args);
    }
}
