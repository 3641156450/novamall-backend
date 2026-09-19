package com.novamall.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 本地消息表（可靠消息最终一致性的核心）。
 *
 * <h3>它解决什么问题？</h3>
 * <p>下单要同时做两件事：写订单表（本地 MySQL）、发消息通知库存服务扣库存（RabbitMQ）。
 * 这两个操作<b>无法放在同一个本地事务里</b>——数据库事务管不了 MQ。
 * 于是就有个致命窗口：
 * <ul>
 *   <li>先写 DB 再发 MQ：DB 成功、MQ 挂了 → 库存永远没扣，超卖</li>
 *   <li>先发 MQ 再写 DB：MQ 成功、DB 回滚了 → 库存被多扣，少卖</li>
 * </ul>
 *
 * <h3>解法：本地消息表</h3>
 * <pre>
 * 1. 开启本地事务
 * 2.   插入订单（状态：处理中）
 * 3.   插入本地消息表记录（状态：待发送）   ← 和订单在同一个事务里，同生共死
 * 4. 提交事务
 * 5. 发送消息到 MQ
 * 6. 发送成功 → 消息表状态改为"已发送"
 *    发送失败 → 不管它，定时任务扫描"待发送"的记录重投
 * </pre>
 * 因为第 2、3 步在同一个事务里，"订单存在但消息没记录"这种情况不可能发生。
 * 只要消息落地了，后面靠重试一定能发出去——这就是<b>最终一致性</b>。</p>
 *
 * <h3>和 Seata 怎么选？</h3>
 * <ul>
 *   <li><b>Seata AT</b>：强一致（其实是最终一致的加强版），对业务零侵入，
 *       但要额外部署 TC 服务，且全程持有数据库行锁，高并发下性能损耗明显。</li>
 *   <li><b>本地消息表</b>：最终一致，有短暂延迟，但吞吐高、无额外组件、逻辑可控。
 *       电商"下单扣库存"这种场景，用户能接受几百毫秒的延迟，所以用它。</li>
 *   <li><b>TCC</b>：强一致、性能好，但要为每个操作写 Try/Confirm/Cancel 三个方法，
 *       开发成本高，一般只用在资金相关的核心链路。</li>
 * </ul>
 *
 * @author NovaMall
 */
@Data
@TableName("t_local_message")
public class LocalMessage {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 业务唯一标识（如订单号），用于消费端做幂等 */
    private String bizKey;

    /** 消息主题/类型 */
    private String topic;

    /** 消息体 JSON */
    private String body;

    /** 状态：0-待发送 1-已发送 2-发送失败（超过重试上限） */
    private Integer status;

    /** 已重试次数 */
    private Integer retryCount;

    /** 最大重试次数 */
    private Integer maxRetry;

    /** 下次重试时间（指数退避） */
    private LocalDateTime nextRetryTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
