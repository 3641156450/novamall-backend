package com.novamall.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.benmanes.caffeine.cache.Cache;
import com.novamall.common.lock.RedisLockHelper;
import com.novamall.common.result.PageResult;
import com.novamall.common.result.ResultCode;
import com.novamall.common.exception.BizException;
import com.novamall.product.config.BloomFilterService;
import com.novamall.product.entity.Product;
import com.novamall.product.mapper.ProductMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * 商品服务：本项目缓存设计的核心。
 *
 * <h3>查询三级缓存流程</h3>
 * <pre>
 *   请求 → L1 Caffeine(JVM内存) → 命中返回(微秒级)
 *            ↓ 未命中
 *          L2 Redis → 命中返回(毫秒级)，并回填 L1
 *            ↓ 未命中
 *          L3 MySQL → 命中后回填 L2 + L1
 * </pre>
 *
 * <h3>三个经典问题及本项目的解法</h3>
 * <table>
 *   <tr><th>问题</th><th>现象</th><th>本项目的解法</th></tr>
 *   <tr><td>缓存穿透</td><td>查不存在的 key，每次都打到 DB</td>
 *       <td>① 布隆过滤器前置拦截 ② DB 查不到也缓存空值（TTL 60s）</td></tr>
 *   <tr><td>缓存击穿</td><td>某个爆款 key 过期瞬间，成千上万请求同时打到 DB</td>
 *       <td>Redis 分布式锁 + 双重检查：只有拿到锁的线程去查 DB，其余自旋等待后读缓存</td></tr>
 *   <tr><td>缓存雪崩</td><td>大批 key 在同一秒集体过期</td>
 *       <td>基础 TTL + 随机抖动（±20%），把过期时间打散</td></tr>
 * </table>
 *
 * <h3>双写一致性：先改 DB 还是先删缓存？</h3>
 * <p>结论：<b>先更新数据库，再删除缓存</b>（Cache Aside Pattern）。
 * 反过来"先删缓存再更新DB"会有更大的不一致窗口：
 * 删完缓存后、DB 还没更新完，另一个读请求会把<b>旧值</b>重新写回缓存，
 * 之后就一直是脏数据，直到下次 TTL 过期。</p>
 *
 * <p>那"先更新 DB 再删缓存"就完美了吗？也不是，存在极小概率：
 * 缓存刚好过期 → 读请求查到旧值 → 写请求更新 DB 并删缓存 → 读请求把旧值写回缓存。
 * 这个窗口要求"读请求在写请求之后才完成写缓存"，而通常读比写快得多，概率很低。
 * 要进一步兜底就做<b>延迟双删</b>（删一次，sleep 一小会儿再删一次），本项目实现了。</p>
 *
 * @author NovaMall
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductMapper productMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisLockHelper redisLockHelper;
    private final BloomFilterService bloomFilterService;
    private final Cache<Long, Product> localCache;

    private static final String PRODUCT_KEY = "product:detail:";
    /** 缓存空值用的标记，避免和真实 null 混淆 */
    private static final String NULL_MARK = "__NULL__";
    /** 基础过期时间（秒） */
    private static final long BASE_TTL = 30 * 60;
    private static final Random RANDOM = new Random();

    /**
     * 查询商品详情（三级缓存 + 三防）。
     */
    public Product getById(Long id) {
        if (id == null || id <= 0) {
            throw new BizException(ResultCode.PARAM_ERROR);
        }

        // ---------- 防穿透第一道：布隆过滤器 ----------
        if (!bloomFilterService.mightContain(id)) {
            log.debug("[Product] 布隆过滤器拦截不存在的商品 id={}", id);
            throw new BizException(ResultCode.NOT_FOUND.getCode(), "商品不存在");
        }

        // ---------- L1：本地缓存 ----------
        Product local = localCache.getIfPresent(id);
        if (local != null) {
            return local;
        }

        // ---------- L2：Redis ----------
        String key = PRODUCT_KEY + id;
        Object cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            // 防穿透第二道：命中空值标记
            if (NULL_MARK.equals(cached)) {
                throw new BizException(ResultCode.NOT_FOUND.getCode(), "商品不存在");
            }
            Product product = (Product) cached;
            localCache.put(id, product);
            return product;
        }

        // ---------- L3：击穿防护 + 查库 ----------
        Product product = loadFromDbWithMutex(id, key);
        return product;
    }

    /**
     * 缓存击穿防护：分布式锁 + 双重检查（Double Check）。
     *
     * <p>为什么用互斥锁而不是"逻辑过期"？
     * 互斥锁方案简单、强一致，代价是拿不到锁的线程要等一下（本项目最多等 3 秒）。
     * "逻辑过期"方案（缓存永不过期，value 里存一个业务过期时间，过期了就起异步线程刷新）
     * 能做到"永远不阻塞用户"，但实现复杂、且会有一段时间返回旧数据。
     * 商城商品详情属于"可以短暂等待但不要脏数据"的场景，所以用互斥锁。</p>
     */
    private Product loadFromDbWithMutex(Long id, String key) {
        String lockKey = "lock:product:" + id;
        String lockValue = redisLockHelper.tryLock(lockKey, 3000, 10000);

        if (lockValue == null) {
            // 拿不到锁：说明已经有线程在查库了，等一会儿直接读缓存
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached != null && !NULL_MARK.equals(cached)) {
                return (Product) cached;
            }
            // 还是没有，就直接查库（降级，总比报错强）
            return queryDb(id);
        }

        try {
            // 双重检查：拿到锁之后再查一次缓存，可能前面的线程已经写好了
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                if (NULL_MARK.equals(cached)) {
                    throw new BizException(ResultCode.NOT_FOUND.getCode(), "商品不存在");
                }
                return (Product) cached;
            }

            Product product = queryDb(id);

            if (product == null) {
                // 防穿透第二道：缓存空值，TTL 短一些，避免恶意刷不同 id 占满内存
                redisTemplate.opsForValue().set(key, NULL_MARK, 60, TimeUnit.SECONDS);
                throw new BizException(ResultCode.NOT_FOUND.getCode(), "商品不存在");
            }

            // 防雪崩：TTL 加随机抖动，把过期时间打散
            redisTemplate.opsForValue().set(key, product, randomTtl(), TimeUnit.SECONDS);
            localCache.put(id, product);
            return product;
        } finally {
            redisLockHelper.unlock(lockKey, lockValue);
        }
    }

    private Product queryDb(Long id) {
        return productMapper.selectOne(new LambdaQueryWrapper<Product>()
                .eq(Product::getId, id)
                .eq(Product::getStatus, 1));
    }

    /**
     * 更新商品：先更新 DB，再删除缓存（+ 延迟双删）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void update(Product product) {
        int rows = productMapper.updateById(product);
        if (rows == 0) {
            throw new BizException(ResultCode.PARAM_ERROR.getCode(), "商品不存在或已被修改");
        }
        evictCache(product.getId());
    }

    /**
     * 删除缓存 + 延迟双删。
     *
     * <p>"延迟"的时长怎么定？一般取"一次主从同步延迟 + 一次读请求的耗时"，
     * 经验值 500ms ~ 1s。生产环境更优雅的做法是：
     * 把删除缓存这件事做成 MQ 消息（或监听 MySQL binlog，用 Canal / Flink CDC），
     * 失败自动重试，而不是靠 sleep。</p>
     */
    public void evictCache(Long id) {
        String key = PRODUCT_KEY + id;
        redisTemplate.delete(key);
        localCache.invalidate(id);

        // 延迟双删：异步执行，不阻塞本次请求
        new Thread(() -> {
            try {
                Thread.sleep(500);
                redisTemplate.delete(key);
                localCache.invalidate(id);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("[Product] 延迟双删失败 id={}", id, e);
            }
        }, "cache-evict-" + id).start();
    }

    /** 防雪崩：基础 TTL + 最多 ±20% 的随机抖动 */
    private long randomTtl() {
        return BASE_TTL + RANDOM.nextInt((int) (BASE_TTL * 0.2));
    }

    /** 分页查询（列表页，不做本地缓存，只走 Redis 短期缓存） */
    public PageResult<Product> page(Long categoryId, String keyword, long pageNo, long pageSize) {
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<Product>()
                .eq(categoryId != null, Product::getCategoryId, categoryId)
                .like(keyword != null && !keyword.isBlank(), Product::getName, keyword)
                .eq(Product::getStatus, 1)
                .orderByDesc(Product::getSales);

        Page<Product> page = productMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
        return PageResult.of(page.getTotal(), pageNo, pageSize, page.getRecords());
    }

    /**
     * 扣减库存（悲观/乐观锁二选一，这里演示乐观锁）。
     *
     * <p>SQL 形态：{@code UPDATE t_product SET stock = stock - #{num}, version = version + 1
     * WHERE id = #{id} AND stock >= #{num} AND version = #{version}}</p>
     *
     * <p>乐观锁 vs 悲观锁怎么选？
     * <ul>
     *   <li>并发量不高、冲突少 → 乐观锁（无锁，靠 version 自旋重试），吞吐量高</li>
     *   <li>秒杀这种极高并发 → 乐观锁会大量重试导致 CPU 空转，改用 Redis 原子扣减（见秒杀模块）</li>
     * </ul>
     *
     * @return 影响行数，0 表示库存不足或版本号冲突
     */
    @Transactional(rollbackFor = Exception.class)
    public int deductStock(Long productId, Integer num, Integer expectedVersion) {
        return productMapper.deductStock(productId, num, expectedVersion);
    }

    /** 回补库存（订单取消 / 超时未支付时调用） */
    @Transactional(rollbackFor = Exception.class)
    public int restoreStock(Long productId, Integer num) {
        return productMapper.restoreStock(productId, num);
    }

    /** 预热：项目启动时把所有上架商品 ID 灌进布隆过滤器 */
    public void warmUpBloomFilter() {
        List<Long> ids = productMapper.selectList(new LambdaQueryWrapper<Product>()
                        .eq(Product::getStatus, 1)
                        .select(Product::getId))
                .stream()
                .map(Product::getId)
                .toList();
        bloomFilterService.addAll(ids);
    }

    /** 批量查（给订单服务 Feign 用） */
    public List<Product> listByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return productMapper.selectBatchIds(ids);
    }

    /** 计算折后价：演示 BigDecimal 的正确用法 */
    public BigDecimal discountPrice(BigDecimal price, BigDecimal discountRate) {
        // 必须指定精度和舍入模式，否则 divide 遇到无限小数会抛 ArithmeticException
        return price.multiply(discountRate).setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
