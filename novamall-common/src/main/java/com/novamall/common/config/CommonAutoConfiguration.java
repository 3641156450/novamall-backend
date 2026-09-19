package com.novamall.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * 公共 Bean 配置。
 *
 * <p>RedisTemplate 为什么必须手动配置序列化器？
 * 默认的 JdkSerializationRedisSerializer 会把对象序列化成二进制，
 * 在 redis-cli 里看到的是乱码，排查问题时非常痛苦；
 * 而且序列化后的字节里带类名，类一改名就反序列化失败。
 * 改成 JSON 序列化后，可以直接在 Redis 里看到可读的值。</p>
 *
 * @author NovaMall
 */
@Configuration
public class CommonAutoConfiguration {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();

        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }
}
