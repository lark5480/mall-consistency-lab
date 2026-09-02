package com.mall.common.exception;

import com.mall.common.result.Result;
import com.mall.common.result.ResultCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import lombok.extern.slf4j.Slf4j;

/**
 * servlet 栈的全局异常处理。必须限定 SERVLET：网关（WebFlux）组件扫描 com.mall 时
 * 若把本类注册进响应式环境，@ExceptionHandler(Exception.class) 兜底会覆盖
 * 网关 ResponseStatusException 的语义（无路由 404、找不到实例 503 被统一转成 500）。
 */
@Slf4j
@RestControllerAdvice
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class GlobalExceptionHandler {
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBizException(BizException exception) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        if (exception.getCode() == ResultCode.UNAUTHORIZED.getCode()) {
            status = HttpStatus.UNAUTHORIZED;
        } else if (exception.getCode() == ResultCode.NOT_FOUND.getCode()) {
            status = HttpStatus.NOT_FOUND;
        } else if (exception.getCode() == ResultCode.CONFLICT.getCode()
                || exception.getCode() == ResultCode.INSUFFICIENT_STOCK.getCode()
                || exception.getCode() == ResultCode.ORDER_STATE_ERROR.getCode()) {
            status = HttpStatus.CONFLICT;
        } else if (exception.getCode() == ResultCode.FORBIDDEN.getCode()) {
            status = HttpStatus.FORBIDDEN;
        }
        return ResponseEntity.status(status).body(Result.error(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse(ResultCode.BAD_REQUEST.getMessage());
        return Result.error(ResultCode.BAD_REQUEST.getCode(), message);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception exception) {
        log.error("Unhandled exception", exception);
        return Result.error(ResultCode.SYSTEM_ERROR);
    }
}
