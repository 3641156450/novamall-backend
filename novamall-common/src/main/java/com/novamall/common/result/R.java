package com.novamall.common.result;

import lombok.Data;
import java.io.Serializable;

/**
 * 统一响应体。
 *
 * <p>为什么不用 Map 直接返回：Map 可读性差、字段不受控、前端对接全靠口头约定。
 * 固定 code/message/data 三字段后，前端可以统一写拦截器做错误提示，
 * 后端也能在网关层做统一封装。</p>
 *
 * @author NovaMall
 */
@Data
public class R<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 业务状态码：200 成功，其余见 {@link ResultCode} */
    private int code;

    private String message;

    private T data;

    /** 链路追踪 ID，排查问题时用（日志 MDC 中同名的 traceId） */
    private String traceId;

    private long timestamp = System.currentTimeMillis();

    public static <T> R<T> ok() {
        return ok(null);
    }

    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();
        r.setCode(ResultCode.SUCCESS.getCode());
        r.setMessage(ResultCode.SUCCESS.getMessage());
        r.setData(data);
        return r;
    }

    public static <T> R<T> fail(ResultCode resultCode) {
        return fail(resultCode.getCode(), resultCode.getMessage());
    }

    public static <T> R<T> fail(int code, String message) {
        R<T> r = new R<>();
        r.setCode(code);
        r.setMessage(message);
        return r;
    }

    public R<T> traceId(String traceId) {
        this.traceId = traceId;
        return this;
    }
}
