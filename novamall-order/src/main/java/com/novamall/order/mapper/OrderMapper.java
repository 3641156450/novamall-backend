package com.novamall.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.novamall.order.entity.Order;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单主表 Mapper。
 *
 * @author NovaMall
 */
public interface OrderMapper extends BaseMapper<Order> {

    @Select("SELECT * FROM t_order WHERE order_no = #{orderNo} LIMIT 1")
    Order selectByOrderNo(@Param("orderNo") String orderNo);

    /**
     * 查询超时未支付的订单。
     *
     * <p>两个细节：
     * <ol>
     *   <li>时间阈值从参数传进来，不用 {@code NOW() - INTERVAL 30 MINUTE}。
     *       一是应用与数据库时钟可能不一致，二是把列包在函数里会导致索引失效。</li>
     *   <li>必须 LIMIT。万一积压几十万条，一次全捞出来会直接 OOM。</li>
     * </ol>
     */
    @Select("""
            SELECT * FROM t_order
             WHERE status = 0
               AND create_time < #{deadline}
             ORDER BY create_time ASC
             LIMIT #{limit}
            """)
    List<Order> selectTimeoutOrders(@Param("deadline") LocalDateTime deadline, @Param("limit") int limit);

    /**
     * 关闭订单（WHERE 里带状态判断，天然幂等）。
     *
     * @return 影响行数，0 说明已被别的线程处理过（多实例部署时会遇到）
     */
    @Update("UPDATE t_order SET status = 4, close_time = NOW() WHERE id = #{id} AND status = 0")
    int closeIfWaitPay(@Param("id") Long id);

    /**
     * 支付成功（同样靠状态判断防重复）。
     * 支付回调被第三方重复通知时，第二次会返回 0，直接忽略即可。
     */
    @Update("UPDATE t_order SET status = 1, pay_time = NOW() WHERE id = #{id} AND status = 0")
    int payIfWaitPay(@Param("id") Long id);
}
