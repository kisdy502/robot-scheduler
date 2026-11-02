package com.zioneer.robotscheduler.rms.client.model;

import lombok.Getter;

import java.util.Map;

/**
 * RMS 命令响应结果（对齐 RobotAPIResult 三态语义 + 附加数据，如 start-activity 返回的 path）。
 */
@Getter
public class RmsCommandResult {

    /** 成功送达并被接受。 */
    public static final int ACCEPTED = 0;

    /** 暂时失败可重试。 */
    public static final int RETRYABLE = 1;

    /** 永久失败。 */
    public static final int IMPOSSIBLE = 2;

    private final int code;
    private final boolean retriable;
    private final String message;
    private final Map<String, Object> data;

    private RmsCommandResult(int code, boolean retriable, String message, Map<String, Object> data) {
        this.code = code;
        this.retriable = retriable;
        this.message = message;
        this.data = data == null ? Map.of() : data;
    }

    public static RmsCommandResult accepted(Map<String, Object> data) {
        return new RmsCommandResult(ACCEPTED, false, null, data);
    }

    public static RmsCommandResult retryable(String message) {
        return new RmsCommandResult(RETRYABLE, true, message, Map.of());
    }

    public static RmsCommandResult impossible(String message) {
        return new RmsCommandResult(IMPOSSIBLE, false, message, Map.of());
    }

    public boolean isAccepted() {
        return code == ACCEPTED;
    }

    public boolean isRetryable() {
        return code == RETRYABLE || retriable;
    }

    public boolean isImpossible() {
        return code == IMPOSSIBLE;
    }
}
