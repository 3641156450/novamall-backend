package com.novamall.common.exception;

import com.novamall.common.result.ResultCode;
import lombok.Getter;

/**
 * 业务异常：可预期、需要把明确原因告诉用户的异常。
 *
 * <p>设计约定：只有 BizException 的 message 会透传给前端，
 * 其他任何异常一律转成"系统繁忙"，避免把 SQL 报错、堆栈信息泄露出去。</p>
 *
 * @author NovaMall
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(String message) {
        super(message);
        this.code = ResultCode.SYSTEM_ERROR.getCode();
    }
}
