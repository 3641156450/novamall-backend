package com.novamall.common;

import com.novamall.common.util.SnowflakeIdWorker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 雪花算法单元测试。
 *
 * <p>为什么给一个"抄来的"算法写测试？
 * 因为面试官问"你怎么保证它不重复"时，
 * 光说"我用了雪花算法"没用，能拿出并发测试证明它不重复才有说服力。</p>
 *
 * @author NovaMall
 */
@DisplayName("雪花算法 ID 生成器")
class SnowflakeIdWorkerTest {

    @Test
    @DisplayName("单线程生成 10 万个 ID 不重复")
    void shouldGenerateUniqueIds() {
        SnowflakeIdWorker worker = new SnowflakeIdWorker(1, 1);
        Set<Long> ids = new HashSet<>();

        for (int i = 0; i < 100_000; i++) {
            ids.add(worker.nextId());
        }

        assertEquals(100_000, ids.size(), "生成的 ID 出现重复");
    }

    @Test
    @DisplayName("多线程并发生成 20 万个 ID 不重复")
    void shouldBeThreadSafe() throws InterruptedException {
        SnowflakeIdWorker worker = new SnowflakeIdWorker(1, 1);
        int threadCount = 8;
        int perThread = 25_000;

        Set<Long> allIds = ConcurrentSet.newSet();
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        for (int t = 0; t < threadCount; t++) {
            pool.submit(() -> {
                try {
                    Set<Long> local = new HashSet<>();
                    for (int i = 0; i < perThread; i++) {
                        local.add(worker.nextId());
                    }
                    synchronized (allIds) {
                        allIds.addAll(local);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        pool.shutdown();

        assertEquals(0, errors.get(), "生成过程中出现异常");
        assertEquals(threadCount * perThread, allIds.size(), "并发场景下出现重复 ID");
    }

    @Test
    @DisplayName("ID 是递增的（趋势递增，利于索引）")
    void shouldBeIncreasing() {
        SnowflakeIdWorker worker = new SnowflakeIdWorker(1, 1);
        long prev = worker.nextId();
        for (int i = 0; i < 1000; i++) {
            long current = worker.nextId();
            assertTrue(current > prev, "ID 必须趋势递增");
            prev = current;
        }
    }

    @Test
    @DisplayName("可以从 ID 反解出生成时间")
    void shouldParseTimestamp() {
        SnowflakeIdWorker worker = new SnowflakeIdWorker(1, 1);
        long before = System.currentTimeMillis();
        long id = worker.nextId();
        long after = System.currentTimeMillis();

        long parsed = SnowflakeIdWorker.parseTimestamp(id);
        assertTrue(parsed >= before - 5 && parsed <= after + 5,
                "反解出的时间应在生成时间附近，实际：" + parsed);
    }

    @Test
    @DisplayName("非法的数据中心ID/机器ID 应该被拒绝")
    void shouldRejectInvalidWorkerId() {
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdWorker(32, 1));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdWorker(1, 32));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdWorker(-1, 1));
        assertDoesNotThrow(() -> new SnowflakeIdWorker(31, 31));
    }

    /** 简单包装，避免并发集合在测试里写得太啰嗦 */
    private static class ConcurrentSet {
        static Set<Long> newSet() {
            return java.util.Collections.synchronizedSet(new HashSet<>());
        }
    }
}
