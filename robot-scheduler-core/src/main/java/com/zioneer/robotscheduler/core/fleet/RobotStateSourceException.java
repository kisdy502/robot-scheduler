package com.zioneer.robotscheduler.core.fleet;

/**
 * 机器人状态源不可用（网络/超时/5xx），由 FleetManager 记录并保持上一帧镜像。
 */
public class RobotStateSourceException extends RuntimeException {

    public RobotStateSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
