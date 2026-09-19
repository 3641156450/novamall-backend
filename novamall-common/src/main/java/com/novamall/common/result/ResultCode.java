package com.novamall.common.result;

import lombok.Getter;

/**
 * 业务状态码枚举。
 *
 * <p>与 HTTP 状态码的区别：HTTP 状态码表达"请求是否被服务器接收处理"，
 * 业务码表达"这次业务操作的语义结果"。比如下单库存不足，HTTP 应该返回 200，
 * 业务码返回 5001，前端才能统一走"提示框"分支而不是"网络错误"分支。</p>
 *
 * @author NovaMall
 */
@Getter
public enum ResultCode {

    SUCCESS(200, "操作成功"),

    /* 客户端类 4xxxx */
    PARAM_ERROR(4000, "参数校验失败"),
    UNAUTHORIZED(4001, "未登录或登录已过期"),
    FORBIDDEN(4003, "没有访问权限"),
    NOT_FOUND(4004, "资源不存在"),
    REQUEST_TOO_FREQUENT(4029, "操作过于频繁，请稍后再试"),
    REPEAT_SUBMIT(4030, "请勿重复提交"),

    /* 业务类 5xxxx */
    STOCK_NOT_ENOUGH(5001, "库存不足"),
    SECKILL_NOT_START(5002, "秒杀活动未开始"),
    SECKILL_SOLD_OUT(5003, "手慢了，已售罄"),
    ORDER_CREATE_FAILED(5004, "下单失败，请重试"),

    /* 系统类 9xxxx */
    SYSTEM_ERROR(9000, "系统繁忙，请稍后再试"),
    REMOTE_CALL_FAILED(9001, "远程服务调用失败");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
