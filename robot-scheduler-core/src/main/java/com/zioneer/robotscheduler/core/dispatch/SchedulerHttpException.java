package com.zioneer.robotscheduler.core.dispatch;

import lombok.Getter;

/**
 * 携带 HTTP 语义的调度业务异常。
 *
 * <p>存在原因：旧 REST API 的错误是 FastAPI 风格（状态码 + {"detail": msg}），
 * 兼容层需要从内核表达 400/404/409/501 等语义；api 模块的全局异常处理器负责落成响应。</p>
 */
@Getter
public class SchedulerHttpException extends RuntimeException {

    private final int httpStatus;

    public SchedulerHttpException(int httpStatus, String message) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public static SchedulerHttpException badRequest(String message) {
        return new SchedulerHttpException(400, message);
    }

    public static SchedulerHttpException notFound(String message) {
        return new SchedulerHttpException(404, message);
    }

    public static SchedulerHttpException conflict(String message) {
        return new SchedulerHttpException(409, message);
    }
}
