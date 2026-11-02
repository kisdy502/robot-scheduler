package com.zioneer.robotscheduler.common.core.error;

import lombok.Getter;

/**
 * 业务异常，由全局异常处理器统一转换为标准响应；错误码收敛在 {@link ErrorCode}，
 * 禁止在业务模块散落定义错误码常量（平台规范）。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final int code;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMsg());
        this.errorCode = errorCode;
        this.code = errorCode.getCode();
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.code = errorCode.getCode();
    }

    public BusinessException(int code, String message) {
        super(message);
        this.errorCode = null;
        this.code = code;
    }
}
