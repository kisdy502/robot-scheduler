package com.zioneer.robotscheduler.common.scheduler.task;

/**
 * 调度请求的内部受理状态（对齐旧栈任务记录 record.status，字段名 rmf_ 前缀为兼容保留）。
 */
public enum DispatchStatus {

    /** 已发布至调度内核，等待回执。 */
    PUBLISHED("published"),

    /** 已收到调度内核回执（含内核任务 ID）。 */
    RMF_RESPONSE_RECEIVED("rmf_response_received"),

    /** 已发出取消请求，等待取消确认。 */
    CANCEL_REQUESTED("cancel_requested");

    private final String wireName;

    DispatchStatus(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
