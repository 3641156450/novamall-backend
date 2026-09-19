package com.novamall.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.novamall.order.entity.LocalMessage;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 本地消息表 Mapper。
 *
 * @author NovaMall
 */
public interface LocalMessageMapper extends BaseMapper<LocalMessage> {

    /**
     * 查询待重投的消息。
     *
     * <p>对应索引：(status, next_retry_time)，避免全表扫描。</p>
     */
    @Select("""
            SELECT * FROM t_local_message
             WHERE status = 0
               AND next_retry_time <= #{now}
             ORDER BY create_time ASC
             LIMIT #{limit}
            """)
    List<LocalMessage> selectPending(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
