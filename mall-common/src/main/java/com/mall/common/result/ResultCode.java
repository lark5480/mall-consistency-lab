package com.mall.common.result;

public enum ResultCode {
    SUCCESS(0, "success"),
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未登录或登录已过期"),
    NOT_FOUND(404, "资源不存在"),
    CONFLICT(409, "业务状态冲突"),
    INSUFFICIENT_STOCK(40001, "库存不足"),
    ORDER_STATE_ERROR(40002, "订单状态不允许该操作"),
    FORBIDDEN(40301, "无权限访问"),
    SYSTEM_ERROR(500, "系统繁忙");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
