package com.novamall.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户表实体。
 *
 * <p>几个字段的设计说明：
 * <ul>
 *   <li>id 用雪花算法生成（Long），不用自增——见 SnowflakeIdWorker 注释</li>
 *   <li>password 存 BCrypt 哈希，绝不存明文；BCrypt 每次生成的哈希不同（盐随机），
 *       所以不能用 SQL 的 "=" 比对，必须用 matches() 方法</li>
 *   <li>deleted 用 @TableLogic 做逻辑删除，所有自动生成的 SQL 都会带上 deleted=0 条件</li>
 * </ul>
 *
 * @author NovaMall
 */
@Data
@TableName("t_user")
public class User {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String username;

    private String password;

    private String phone;

    private String email;

    /** 角色编码：USER / ADMIN / OPERATOR */
    private String roleCode;

    /** 状态：1 正常，0 禁用 */
    private Integer status;

    /** 逻辑删除：0 未删除，1 已删除 */
    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
