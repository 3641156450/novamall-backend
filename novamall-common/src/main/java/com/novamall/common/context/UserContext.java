package com.novamall.common.context;

/**
 * 用户上下文：基于 ThreadLocal 传递当前登录用户。
 *
 * <p>面试必问的两个坑：
 * <ol>
 *   <li><b>线程池下会串号</b>：ThreadLocal 绑定的是线程，线程复用后如果不 remove，
 *       下一个任务会读到上一个用户的 ID。所以必须在 finally 里 clear()。
 *       更彻底的做法是用 Alibaba TTL（TransmittableThreadLocal）做线程池传递。</li>
 *   <li><b>异步方法拿不到</b>：@Async / CompletableFuture 里的线程不是请求线程，
 *       需要显式传参或用 TTL 包装线程池。</li>
 * </ol>
 *
 * @author NovaMall
 */
public final class UserContext {

    private static final ThreadLocal<UserInfo> LOCAL = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(UserInfo userInfo) {
        LOCAL.set(userInfo);
    }

    public static UserInfo get() {
        return LOCAL.get();
    }

    public static Long getUserId() {
        UserInfo userInfo = LOCAL.get();
        // 注意：record 的访问器方法名就是字段名本身（userId()），不是 getUserId()
        return userInfo == null ? null : userInfo.userId();
    }

    /** 必须在请求结束时调用（网关 Filter / 拦截器 finally 块） */
    public static void clear() {
        LOCAL.remove();
    }

    public record UserInfo(Long userId, String username, String roleCode) {
    }
}
