package com.novamall.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.novamall.product.entity.Product;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 商品 Mapper。
 *
 * @author NovaMall
 */
public interface ProductMapper extends BaseMapper<Product> {

    /**
     * 乐观锁扣库存。
     *
     * <p>为什么把判断条件写进 WHERE 而不是"先查再判断"？
     * 因为"查"和"改"之间有间隙，两个并发请求可能都查到 stock=1，然后都去扣，结果超卖。
     * 把 stock >= num 写进 UPDATE 的 WHERE 里，由 MySQL 的行锁保证原子性，
     * 影响行数为 0 就说明库存不足。这是最简单可靠的防超卖写法。</p>
     *
     * @param expectedVersion 期望的版本号，传 null 表示不校验版本（纯靠 stock 条件）
     * @return 影响行数
     */
    @Update("""
            UPDATE t_product
               SET stock = stock - #{num},
                   version = version + 1
             WHERE id = #{id}
               AND stock >= #{num}
               AND (#{expectedVersion,jdbcType=INTEGER} IS NULL OR version = #{expectedVersion,jdbcType=INTEGER})
            """)
    int deductStock(@Param("id") Long id,
                    @Param("num") Integer num,
                    @Param("expectedVersion") Integer expectedVersion);

    /** 回补库存 */
    @Update("""
            UPDATE t_product
               SET stock = stock + #{num},
                   version = version + 1
             WHERE id = #{id}
            """)
    int restoreStock(@Param("id") Long id, @Param("num") Integer num);

    /** 增加销量 */
    @Update("UPDATE t_product SET sales = sales + #{num} WHERE id = #{id}")
    int incrSales(@Param("id") Long id, @Param("num") Integer num);
}
