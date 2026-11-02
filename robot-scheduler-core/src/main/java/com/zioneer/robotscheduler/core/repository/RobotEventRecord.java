package com.zioneer.robotscheduler.core.repository;

import lombok.Getter;
import lombok.Setter;

/** 机器人事件记录（K8s Events 风格：reason 稳定码 + 聚合 count）。 */
@Getter
@Setter
public class RobotEventRecord {

    private Long id;
    private String robotCode;
    /** MODE / BATTERY / ONLINE */
    private String eventType;
    /** 稳定码：MODE_CHANGE / BATTERY_DELTA / BATTERY_LOW / BATTERY_CRITICAL / BATTERY_FULL / ONLINE / OFFLINE */
    private String reason;
    /** INFO / WARN / ERROR */
    private String severity;
    private String oldValue;
    private String newValue;
    private String message;
    /** 聚合次数（窗口内重复事件合并计数） */
    private long eventCount = 1;
    private Long firstAtMillis;
    private Long lastAtMillis;
}
