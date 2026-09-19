package com.novamall.product.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.novamall.product.entity.Product;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * 本地缓存 Caffeine 配置。
 *
 * <h3>为什么需要"本地缓存 + Redis"两级？</h3>
 * <p>Redis 再快也是一次网络往返（同机房约 0.2~0.5ms）。
 * 对于首页爆款商品这种 QPS 几万的热点数据，每次都走 Redis 会：
 * (1) 占用大量 Redis 连接；(2) 网络耗时成为主要瓶颈。
 * 把最热的 key 再放一份到 JVM 内存里，命中率能到 90%+，RT 从毫秒级降到微秒级。</p>
 *
 * <h3>代价是什么？</h3>
 * <p>本地缓存是每个 JVM 一份，数据更新时无法像 Redis 那样"删一处全集群生效"，
 * 会出现短时间的不一致（几十秒）。所以：
 * <ul>
 *   <li>本地缓存 TTL 要短（本项目 60s），并且接受短暂脏读</li>
 *   <li>真正需要强一致的场景（库存）不放本地缓存</li>
 *   <li>变更频繁时可以接 Redis Pub/Sub 广播失效消息，让各节点主动清除</li>
 * </ul>
 * </p>
 *
 * <h3>为什么用 Caffeine 不用 Guava Cache？</h3>
 * <p>Caffeine 用 Window-TinyLFU 淘汰算法，命中率显著高于 Guava 的 LRU；
 * 而且它是 Spring Boot 3 默认支持的（Guava Cache 支持已被标记为过时）。</p>
 *
 * @author NovaMall
 */
@Configuration
@EnableCaching
public class CaffeineCacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCaffeine(Caffeine.newBuilder()
                // 写入后 60 秒过期（write 比 access 更可控，避免冷数据长期驻留）
                .expireAfterWrite(60, TimeUnit.SECONDS)
                // 最多缓存 10000 个 key，超出按 TinyLFU 淘汰
                .maximumSize(10_000)
                // 初始容量，避免扩容时的 rehash
                .initialCapacity(1000)
                // 记录命中率统计，可通过 Actuator 观察
                .recordStats());
        return cacheManager;
    }

    /**
     * 直接暴露一个 Caffeine 原生 Cache 给业务代码用。
     *
     * <p>为什么不全用 @Cacheable 注解？
     * 注解方式写起来快，但我们要做"缓存空值防穿透""互斥锁防击穿"这些定制逻辑，
     * 注解就力不从心了（它无法表达"查不到时写 NULL 并设 60s TTL"这种分支）。
     * 所以热点详情用手工 API，简单的字典数据再用注解，按需选择。</p>
     */
    @Bean
    public com.github.benmanes.caffeine.cache.Cache<Long, Product> productLocalCache() {
        return Caffeine.newBuilder()
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .maximumSize(10_000)
                .recordStats()
                .build();
    }
}
