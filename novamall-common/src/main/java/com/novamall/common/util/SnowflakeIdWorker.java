package com.novamall.common.util;

/**
 * 雪花算法（Snowflake）分布式 ID 生成器。
 *
 * <p>结构（共 64 bit，首位不用）：
 * <pre>
 *  0 | 0000000000 0000000000 0000000000 0000000000 0 | 00000 | 00000 | 000000000000
 *  ^                      41bit 时间戳(ms)              ^5bit机房 ^5bit机器 ^12bit序列号
 * </pre>
 *
 * <p>面试高频追问整理：
 * <ol>
 *   <li><b>为什么不用数据库自增 ID？</b> 分库分表后各表自增会撞号；且 ID 连续容易被竞对爬数据量。</li>
 *   <li><b>为什么不用 UUID？</b> UUID 无序，作为 MySQL InnoDB 主键会导致页分裂、索引膨胀，写入性能差；且 128bit 太长。</li>
 *   <li><b>时钟回拨怎么办？</b> 本实现在检测到回拨时，若回拨幅度小于 100ms 就自旋等待追平；
 *       超过阈值直接抛异常交给上层（更严谨的生产做法是接入历史时钟缓存或换用美团 Leaf / 百度 UidGenerator）。</li>
 *   <li><b>单机每毫秒能生成多少？</b> 序列号 12 bit = 4096 个/ms，即理论 409.6 万 QPS，远超业务需要。</li>
 * </ol>
 *
 * @author NovaMall
 */
public class SnowflakeIdWorker {

    /** 起始时间戳：2024-01-01 00:00:00，69 年内不会用完 41 bit */
    private static final long EPOCH = 1704038400000L;

    private static final long DATA_CENTER_ID_BITS = 5L;
    private static final long WORKER_ID_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;

    private static final long MAX_DATA_CENTER_ID = ~(-1L << DATA_CENTER_ID_BITS); // 31
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);           // 31
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);            // 4095

    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;                        // 12
    private static final long DATA_CENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;  // 17
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATA_CENTER_ID_BITS; // 22

    /** 允许的最大时钟回拨毫秒数 */
    private static final long MAX_BACKWARD_MS = 100L;

    private final long dataCenterId;
    private final long workerId;

    private long sequence = 0L;
    private long lastTimestamp = -1L;

    public SnowflakeIdWorker(long dataCenterId, long workerId) {
        if (dataCenterId > MAX_DATA_CENTER_ID || dataCenterId < 0) {
            throw new IllegalArgumentException("dataCenterId 必须在 0 ~ " + MAX_DATA_CENTER_ID + " 之间");
        }
        if (workerId > MAX_WORKER_ID || workerId < 0) {
            throw new IllegalArgumentException("workerId 必须在 0 ~ " + MAX_WORKER_ID + " 之间");
        }
        this.dataCenterId = dataCenterId;
        this.workerId = workerId;
    }

    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();

        if (timestamp < lastTimestamp) {
            long offset = lastTimestamp - timestamp;
            if (offset <= MAX_BACKWARD_MS) {
                // 小幅回拨：自旋等待时钟追平，避免抛错影响链路
                try {
                    wait(offset << 1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("时钟回拨等待被中断", e);
                }
                timestamp = System.currentTimeMillis();
                if (timestamp < lastTimestamp) {
                    throw new IllegalStateException("时钟回拨超过 " + MAX_BACKWARD_MS + "ms，拒绝生成 ID");
                }
            } else {
                throw new IllegalStateException("严重时钟回拨：" + offset + "ms，拒绝生成 ID");
            }
        }

        if (timestamp == lastTimestamp) {
            // 同一毫秒内自增序列号，超出则阻塞到下一毫秒
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0L) {
                timestamp = tilNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                | (dataCenterId << DATA_CENTER_ID_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    /** 阻塞直到拿到一个比 lastTimestamp 大的时间戳 */
    private long tilNextMillis(long lastTimestamp) {
        long timestamp = System.currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }

    /** 从 ID 反解出生成时间，排查问题时很有用 */
    public static long parseTimestamp(long id) {
        return (id >> TIMESTAMP_SHIFT) + EPOCH;
    }
}
