package com.novamall.common.lock;

/**
 * 分布式锁类型。
 *
 * <p>SIMPLE：Redis SET key value NX PX 实现，不可重入，一次获取一次释放，够用且轻量。<br>
 * REENTRANT：可重入锁，用 Redis Hash 记录 {线程标识: 重入次数}，
 * 类似 Redisson 的 RedissonLock，但自己实现一遍更能讲清原理。</p>
 *
 * @author NovaMall
 */
public enum LockType {
    /** 简单不可重入锁 */
    SIMPLE,
    /** 可重入锁（Hash + Lua 计数） */
    REENTRANT
}
