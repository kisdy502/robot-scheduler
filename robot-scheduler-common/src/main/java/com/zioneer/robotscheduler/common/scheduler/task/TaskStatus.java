package com.zioneer.robotscheduler.common.scheduler.task;

import java.util.Map;
import java.util.Set;

/**
 * 任务状态契约与归一化规则（对齐旧栈 normalize_task_state_status / resolve_polled_task_status）。
 *
 * <p>本枚举即对外 API 的 task_status 字段取值；raw 值集合来自内核与历史 RMF 兼容值。</p>
 */
public enum TaskStatus {

    /** 已受理排队中。 */
    QUEUED("queued"),

    /** 执行中。 */
    RUNNING("running"),

    /** 已完成（终态）。 */
    COMPLETED("completed"),

    /** 已失败（终态）。 */
    FAILED("failed"),

    /** 已取消（终态）。 */
    CANCELED("canceled"),

    /** 派生态：取消请求已发出、尚未确认。 */
    CANCEL_REQUESTED("cancel_requested"),

    /** 派生态：任务状态镜像超过 TTL 未更新。 */
    STALE("stale"),

    /** 派生态：无任何可用状态来源。 */
    UNKNOWN("unknown");

    /** 终态集合：execution_final=true 的判定依据，不可变更。 */
    public static final Set<TaskStatus> TERMINAL_STATUSES =
            Set.of(COMPLETED, FAILED, CANCELED);

    /** RMF 原始状态 → 归一化状态映射（保持与旧栈完全一致）。 */
    private static final Map<String, TaskStatus> RAW_MAPPING = Map.ofEntries(
            Map.entry("queued", QUEUED),
            Map.entry("selected", QUEUED),
            Map.entry("dispatching", QUEUED),
            Map.entry("dispatched", QUEUED),
            Map.entry("active", RUNNING),
            Map.entry("running", RUNNING),
            Map.entry("underway", RUNNING),
            Map.entry("executing", RUNNING),
            Map.entry("completed", COMPLETED),
            Map.entry("success", COMPLETED),
            Map.entry("succeeded", COMPLETED),
            Map.entry("finished", COMPLETED),
            Map.entry("done", COMPLETED),
            Map.entry("failed", FAILED),
            Map.entry("failed_to_assign", FAILED),
            Map.entry("error", FAILED),
            Map.entry("aborted", FAILED),
            Map.entry("canceled", CANCELED),
            Map.entry("cancelled", CANCELED),
            Map.entry("canceled_in_flight", CANCELED),
            Map.entry("killed", CANCELED),
            Map.entry("stopped", CANCELED));

    private final String wireName;

    TaskStatus(String wireName) {
        this.wireName = wireName;
    }

    /** 对外 JSON 值。 */
    public String wireName() {
        return wireName;
    }

    public boolean isTerminal() {
        return TERMINAL_STATUSES.contains(this);
    }

    /**
     * 归一化原始状态字符串；未识别的值返回 {@link #UNKNOWN}。
     */
    public static TaskStatus fromRaw(String raw) {
        if (raw == null) {
            return UNKNOWN;
        }
        return RAW_MAPPING.getOrDefault(raw.toLowerCase(), UNKNOWN);
    }

    /**
     * 按对外 JSON 值反查。
     */
    public static TaskStatus fromWireName(String name) {
        for (TaskStatus status : values()) {
            if (status.wireName.equals(name)) {
                return status;
            }
        }
        return UNKNOWN;
    }
}
