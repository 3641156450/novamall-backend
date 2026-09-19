package com.novamall.product;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.novamall.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;

/**
 * 商品服务启动类。
 *
 * @author NovaMall
 */
@Slf4j
@EnableCaching
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.novamall")
@MapperScan("com.novamall.product.mapper")
@SpringBootApplication(scanBasePackages = "com.novamall")
public class ProductApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductApplication.class, args);
    }

    /**
     * MyBatis-Plus 分页插件。
     *
     * <p>不配置这个插件的话，selectPage 返回的分页对象是错的：
     * 它会把所有数据查出来然后在内存里切（其实是 total 永远是 0、limit 不生效）。
     * 原理是拦截器在 SQL 执行前改写语句，拼上 LIMIT 并先查一次 COUNT。</p>
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    /**
     * 启动预热：把商品 ID 灌进布隆过滤器。
     *
     * <p>生产环境一般放到独立的预热任务里（配合 K8s 的 postStart 钩子），
     * 避免拖慢启动速度；数据量很大时还要分页灌，防止一次拉几百万行把内存打满。</p>
     */
    @Bean
    public ApplicationRunner bloomFilterWarmer(ProductService productService) {
        return args -> {
            try {
                productService.warmUpBloomFilter();
            } catch (Exception e) {
                // 预热失败不影响启动，降级为"只靠空值缓存防穿透"
                log.warn("[Product] 布隆过滤器预热失败，已降级为空值缓存方案", e);
            }
        };
    }
}
