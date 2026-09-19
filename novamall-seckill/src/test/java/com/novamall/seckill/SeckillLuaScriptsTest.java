package com.novamall.seckill;

import com.novamall.seckill.service.SeckillLuaScripts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 秒杀 Lua 脚本逻辑测试。
 *
 * <p>Lua 脚本本身没法在单测里跑（要连 Redis），
 * 所以这里测两个东西：
 * <ol>
 *   <li>脚本里声明的 KEYS 顺序和 Java 调用方的传参顺序是否一致（这是最容易犯的错）</li>
 *   <li>各个返回码对应的业务分支是否正确</li>
 * </ol>
 *
 * <p>Redis 相关的完整验证放在集成测试里（需要真实 Redis），
 * 或者用 Embedded Redis 做，但那种依赖比较重，小项目一般不做。</p>
 *
 * @author NovaMall
 */
@DisplayName("秒杀脚本逻辑")
class SeckillLuaScriptsTest {

    @Test
    @DisplayName("KEYS 顺序必须是 [库存key, 已购集合key]")
    void keyOrderShouldMatchLuaScript() {
        List<String> keys = SeckillLuaScripts.keyOrder();
        assertEquals(2, keys.size());
        assertEquals("stockKey", keys.get(0), "第一个 key 必须是库存 key");
        assertEquals("boughtKey", keys.get(1), "第二个 key 必须是已购用户集合 key");
    }

    @Test
    @DisplayName("返回码语义：1成功 / 0售罄 / -2重复购买")
    void returnCodeSemantics() {
        // 有库存且没买过 → 成功
        assertEquals(1, SeckillLuaScripts.simulate(10, false));
        // 只剩 1 件 → 还能成功
        assertEquals(1, SeckillLuaScripts.simulate(1, false));
        // 没库存 → 售罄
        assertEquals(0, SeckillLuaScripts.simulate(0, false));
        // 负数库存（异常数据）→ 售罄
        assertEquals(0, SeckillLuaScripts.simulate(-5, false));
        // 买过 → 限购
        assertEquals(-2, SeckillLuaScripts.simulate(10, true));
    }

    @Test
    @DisplayName("脚本里必须包含防超卖的库存判断")
    void scriptShouldContainStockCheck() {
        String script = SeckillLuaScripts.SECKILL_SCRIPT;
        assertTrue(script.contains("stock <= 0"), "脚本必须判断库存是否大于 0，否则会超卖");
        assertTrue(script.contains("decrby"), "脚本必须扣减库存");
        assertTrue(script.contains("sismember"), "脚本必须判断用户是否重复购买");
        assertTrue(script.contains("sadd"), "扣减成功后必须把用户加入已购集合");
    }

    @Test
    @DisplayName("回补脚本必须先校验用户确实买过，防止重复回补")
    void restoreScriptShouldCheckMembership() {
        String script = SeckillLuaScripts.RESTORE_SCRIPT;
        assertTrue(script.contains("sismember"), "回补前必须确认用户买过，否则会被恶意刷库存");
        assertTrue(script.contains("incrby"), "回补要增加库存");
        assertTrue(script.contains("srem"), "回补后要把用户移出已购集合，否则用户再也买不了");
    }
}
