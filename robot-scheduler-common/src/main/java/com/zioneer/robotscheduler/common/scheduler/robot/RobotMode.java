package com.zioneer.robotscheduler.common.scheduler.robot;

/**
 * 机器人模式枚举（对齐 RMF RobotMode 0-11 及旧栈归一化规则）。
 */
public enum RobotMode {

    IDLE(0, "idle"),
    CHARGING(1, "charging"),
    MOVING(2, "running"),
    PAUSED(3, "paused"),
    WAITING(4, "waiting"),
    EMERGENCY(5, "error"),
    GOING_HOME(6, "running"),
    DOCKING(7, "running"),
    ADAPTER_ERROR(8, "error"),
    CLEANING(9, "running"),
    PERFORMING_ACTION(10, "running"),
    ACTION_COMPLETED(11, "idle");

    private final int rmfMode;
    private final String normalizedMode;

    RobotMode(int rmfMode, String normalizedMode) {
        this.rmfMode = rmfMode;
        this.normalizedMode = normalizedMode;
    }

    public int rmfMode() {
        return rmfMode;
    }

    /** 归一化后的对外 mode 值。 */
    public String normalizedMode() {
        return normalizedMode;
    }

    /**
     * 按 RMF 数值解析；未识别返回 null（由调用方按 unknown 处理）。
     */
    public static RobotMode fromRmfMode(int mode) {
        for (RobotMode value : values()) {
            if (value.rmfMode == mode) {
                return value;
            }
        }
        return null;
    }
}
