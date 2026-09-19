package com.novamall.product.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.BitSet;
import java.util.List;

/**
 * 基于 Redis Bitmap 的布隆过滤器（解决缓存穿透）。
 *
 * <h3>什么是缓存穿透？</h3>
 * <p>查询一个根本不存在的数据（比如恶意用 id = -1 或者随机大 id 刷接口），
 * Redis 一定查不到，请求全部落到数据库。如果被脚本持续刷，数据库会被打垮。
 * 注意区分：<b>穿透=查不存在的key</b>，<b>击穿=热点key恰好过期</b>，<b>雪崩=大批key同时过期</b>。</p>
 *
 * <h3>两种解法</h3>
 * <ol>
 *   <li><b>缓存空值</b>：DB 查不到也写进 Redis，值为特殊标记（如 "NULL"），TTL 设短一点（60s）。
 *       简单有效，缺点是恶意换着 id 刷时会占用大量 Redis 内存。</li>
 *   <li><b>布隆过滤器</b>：把所有合法 id 提前灌进过滤器，请求来了先过一遍，
 *       过滤器说"不存在"就直接返回，连 Redis 都不用查。内存占用极小（1 亿数据约 100MB）。</li>
 * </ol>
 * 本项目两种都实现了，互为兜底。</p>
 *
 * <h3>布隆过滤器原理（面试必考）</h3>
 * <ul>
 *   <li>一个很长的 bit 数组 + k 个不同的哈希函数</li>
 *   <li>加入元素：对元素做 k 次哈希，把得到的 k 个位置都置为 1</li>
 *   <li>查询元素：k 个位置全是 1 则<b>可能存在</b>，有一个是 0 则<b>一定不存在</b></li>
 *   <li>所以它有<b>假阳性</b>（误判存在），但<b>不会假阴性</b>（漏判）——对防穿透来说这正好够用，
 *       因为多放过去几个请求不至于压垮 DB。</li>
 *   <li>不能删除元素（删一个 bit 可能影响其他元素），要删除得用 Counting Bloom Filter。</li>
 * </ul>
 *
 * <p><b>生产建议：</b>直接用 Redisson 的 RBloomFilter，它把位数组长度和哈希函数个数
 * 都按误判率算好了。这里手写是为了讲清原理。</p>
 *
 * @author NovaMall
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BloomFilterService {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String KEY_PREFIX = "bloom:product:";

    /** 位数组长度：约 1 亿 bit ≈ 12MB。越大误判率越低 */
    private static final long BIT_SIZE = 1L << 27;

    /** 哈希函数个数。公式 k = (m/n) * ln2，取 8 是工程常见值 */
    private static final int HASH_COUNT = 8;

    /** 加元素 */
    public void add(Long id) {
        long[] offsets = hashOffsets(id);
        redisTemplate.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
            for (long offset : offsets) {
                connection.setBit((KEY_PREFIX + "bitmap").getBytes(StandardCharsets.UTF_8), offset, true);
            }
            return null;
        });
    }

    /** 批量初始化（项目启动时预热） */
    public void addAll(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        log.info("[BloomFilter] 开始预热商品 ID，共 {} 个", ids.size());
        redisTemplate.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
            byte[] keyBytes = (KEY_PREFIX + "bitmap").getBytes(StandardCharsets.UTF_8);
            for (Long id : ids) {
                for (long offset : hashOffsets(id)) {
                    connection.setBit(keyBytes, offset, true);
                }
            }
            return null;
        });
        log.info("[BloomFilter] 预热完成");
    }

    /**
     * 判断元素是否可能存在。
     *
     * @return true 可能存在（继续查），false 一定不存在（直接拒绝）
     */
    public boolean mightContain(Long id) {
        long[] offsets = hashOffsets(id);
        List<Object> results = redisTemplate.executePipelined(
                (org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                    for (long offset : offsets) {
                        connection.getBit((KEY_PREFIX + "bitmap").getBytes(StandardCharsets.UTF_8), offset);
                    }
                    return null;
                });
        if (results == null || results.isEmpty()) {
            // Redis 不可用时选择放行，不能因为过滤器故障导致正常请求被拦
            return true;
        }
        for (Object r : results) {
            if (!Boolean.TRUE.equals(r)) {
                return false;
            }
        }
        return true;
    }

    /** 用两个不同种子的哈希函数模拟 k 个哈希（double hashing 技巧） */
    private long[] hashOffsets(Long id) {
        long[] offsets = new long[HASH_COUNT];
        int h1 = Math.abs(murmur3(id.toString()));
        int h2 = Math.abs(murmur3(id.toString() + "#salt"));
        for (int i = 0; i < HASH_COUNT; i++) {
            long combined = (long) h1 + (long) i * h2;
            offsets[i] = Math.floorMod(combined, BIT_SIZE);
        }
        return offsets;
    }

    /** 简化版 MurmurHash3（生产可直接用 Guava 的 Hashing.murmur3_128） */
    private int murmur3(String input) {
        byte[] data = input.getBytes(StandardCharsets.UTF_8);
        int h = 0x1b873593;
        for (byte b : data) {
            h ^= b;
            h = Integer.rotateLeft(h, 13) * 0x5bd1e995;
            h ^= h >>> 15;
        }
        return h;
    }

    /** 理论误判率：(1 - e^(-kn/m))^k */
    public double expectedFalsePositiveRate(long expectedInsertions) {
        double p = Math.pow(1 - Math.exp(-(double) HASH_COUNT * expectedInsertions / BIT_SIZE), HASH_COUNT);
        return p;
    }

    /** 演示用：本地 BitSet 版本的布隆过滤器单测辅助 */
    public static BitSet localBitSet() {
        return new BitSet((int) Math.min(BIT_SIZE, Integer.MAX_VALUE));
    }
}
