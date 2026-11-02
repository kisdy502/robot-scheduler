package com.zioneer.robotscheduler.rms.client;

/**
 * RMS 服务不可用（网络/超时/5xx），由轮询层与命令执行器降级处理。
 */
public class RmsUnavailableException extends RuntimeException {

    public RmsUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
