package com.novamall.common.exception;

import com.novamall.common.result.R;
import com.novamall.common.result.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器。
 *
 * <p>面试常问点：为什么 @RestControllerAdvice 能拦到异常？
 * 因为 Spring MVC 在 DispatcherServlet 里解析完异常后会交给 HandlerExceptionResolver，
 * @ExceptionHandler 会被 ExceptionHandlerExceptionResolver 扫描并匹配最具体的异常类型。</p>
 *
 * @author NovaMall
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：直接把语义返回给用户 */
    @ExceptionHandler(BizException.class)
    public R<Void> handleBizException(BizException e, HttpServletRequest request) {
        log.warn("[BizException] uri={} msg={}", request.getRequestURI(), e.getMessage());
        return R.<Void>fail(e.getCode(), e.getMessage()).traceId(MDC.get("traceId"));
    }

    /** @Valid 作用在 @RequestBody 上 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleValidException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse(ResultCode.PARAM_ERROR.getMessage());
        log.warn("[ParamInvalid] {}", message);
        return R.<Void>fail(ResultCode.PARAM_ERROR.getCode(), message).traceId(MDC.get("traceId"));
    }

    /** 表单绑定校验 */
    @ExceptionHandler(BindException.class)
    public R<Void> handleBindException(BindException e) {
        String message = e.getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse(ResultCode.PARAM_ERROR.getMessage());
        return R.<Void>fail(ResultCode.PARAM_ERROR.getCode(), message).traceId(MDC.get("traceId"));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public R<Void> handleMissingParam(MissingServletRequestParameterException e) {
        return R.<Void>fail(ResultCode.PARAM_ERROR.getCode(), "缺少必要参数：" + e.getParameterName())
                .traceId(MDC.get("traceId"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public R<Void> handleNotReadable(HttpMessageNotReadableException e) {
        return R.<Void>fail(ResultCode.PARAM_ERROR).traceId(MDC.get("traceId"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public R<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return R.<Void>fail(ResultCode.PARAM_ERROR.getCode(), "请求方式不支持：" + e.getMethod())
                .traceId(MDC.get("traceId"));
    }

    /**
     * 兜底：所有没预料到的异常。
     * 注意这里打印完整堆栈（便于排查），但只给用户返回一个不含内部信息的提示。
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Void> handleException(Exception e, HttpServletRequest request) {
        log.error("[SystemError] uri={} traceId={}", request.getRequestURI(), MDC.get("traceId"), e);
        return R.<Void>fail(ResultCode.SYSTEM_ERROR).traceId(MDC.get("traceId"));
    }
}
