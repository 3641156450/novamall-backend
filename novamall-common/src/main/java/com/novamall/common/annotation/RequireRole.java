package com.novamall.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 角色校验注解（RBAC 授权）。
 *
 * <p>用法：{@code @RequireRole("ADMIN")} 或 {@code @RequireRole(value = {"ADMIN","OPERATOR"}, mode = Mode.ANY)}</p>
 *
 * <p>RBAC 模型简述（面试常问"权限系统怎么设计"）：
 * <pre>
 *   用户(User) --多对多--> 角色(Role) --多对多--> 权限(Permission) --属于--> 资源(菜单/接口)
 * </pre>
 * 引入"角色"这一层的好处：新来一个运营同学，只需给他"运营"角色，
 * 不用给他逐条勾选 200 个权限；离职回收也只删一个关联。</p>
 *
 * <p>本项目做了简化：用户表直接冗余一个 role_code 字段（单角色），
 * 因为商城 C 端场景一个用户只会是买家或卖家。
 * 如果要做后台管理系统（多角色、数据权限），就要拆成 5 张表，
 * README 里写了完整设计。</p>
 *
 * @author NovaMall
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {

    String[] value();

    /** 多个角色之间的匹配模式 */
    MatchMode mode() default MatchMode.ANY;

    enum MatchMode {
        /** 拥有其中任意一个角色即可 */
        ANY,
        /** 必须同时拥有全部角色 */
        ALL
    }
}
