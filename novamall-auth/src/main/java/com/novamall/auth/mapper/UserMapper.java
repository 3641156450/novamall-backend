package com.novamall.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.novamall.auth.entity.User;
import org.apache.ibatis.annotations.Select;

/**
 * 用户 Mapper。
 *
 * <p>继承 BaseMapper 后就有了 insert / selectById / updateById / deleteById 等单表 CRUD，
 * 不用写 XML。复杂的多表联查才需要自己写 @Select 或 XML。</p>
 *
 * @author NovaMall
 */
public interface UserMapper extends BaseMapper<User> {

    /**
     * 按用户名查。注意 MP 的逻辑删除会自动追加 AND deleted = 0。
     *
     * <p>这里为什么不用 QueryWrapper？因为这是登录热路径，
     * 手写 SQL 更直观，也方便在 username 上加索引时确认走没走索引。</p>
     */
    @Select("SELECT * FROM t_user WHERE username = #{username} AND deleted = 0 LIMIT 1")
    User selectByUsername(String username);

    @Select("SELECT COUNT(1) FROM t_user WHERE username = #{username} AND deleted = 0")
    long countByUsername(String username);
}
