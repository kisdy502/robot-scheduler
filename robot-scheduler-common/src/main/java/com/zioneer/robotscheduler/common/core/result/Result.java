package com.zioneer.robotscheduler.common.core.result;

import com.zioneer.robotscheduler.common.core.error.BusinessException;
import com.zioneer.robotscheduler.common.core.error.ErrorCode;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

/**
 * 内部管理接口统一响应模型（语义对齐 robot-common-core 的 Result）。
 *
 * <p>注意：对外 18 个调度兼容端点不使用本类，而是使用 api 模块的
 * {@code ApiEnvelope}（多 timestamp 字段、固定文案），以保持与旧
 * robot-scheduler-rest-api 0.2.0 响应字段一模一样。</p>
 *
 * @param <T> response data type
 */
@Getter
@Setter
public class Result<T> implements Serializable {

    /**
     * 0 means success.
     */
    public static final int SUCCESS_STATUS = 0;

    private int status;
    private String msg;
    private T data;

    public Result() {
    }

    public Result(int status, String msg, T data) {
        this.status = status;
        this.msg = msg;
        this.data = data;
    }

    public static <T> Result<T> ok() {
        return new Result<>(SUCCESS_STATUS, "success", null);
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(SUCCESS_STATUS, "success", data);
    }

    public static <T> Result<T> fail(int status, String msg) {
        return new Result<>(status, msg, null);
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMsg(), null);
    }

    public static <T> Result<T> fail(ErrorCode errorCode, String msg) {
        return new Result<>(errorCode.getCode(), msg, null);
    }

    public static <T> Result<T> fail(BusinessException e) {
        return new Result<>(e.getCode(), e.getMessage(), null);
    }
}
