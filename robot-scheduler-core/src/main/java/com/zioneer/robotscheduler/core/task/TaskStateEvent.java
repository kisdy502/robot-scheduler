package com.zioneer.robotscheduler.core.task;

import java.util.Map;

/**
 * 任务状态事件（沿用旧栈 task_state_update 的 JSON 契约：booking.id / status / detail / cancellation / assigned_to）。
 *
 * <p>保持同构的原因：下游（上层业务、日志比对、契约测试）依赖该结构做任务状态轮询与取消识别；
 * 活动任务取消发布 COMPLETED + cancellation 标记的 fork 语义在事件发布器中实现。</p>
 */
public record TaskStateEvent(

        /** 内核任务 ID（对外字段名 rmf_task_id 的取值来源）。 */
        String taskId,

        /** 内核原始状态：queued/underway/completed/failed/killed/canceled。 */
        String status,

        /** 任务类别。 */
        String category,

        /** 业务 detail（任务模板、点位等上下文，原样透传）。 */
        Map<String, Object> detail,

        /** 取消标记：活动任务取消时 status=completed 且本字段非空（fork 语义）。 */
        Map<String, Object> cancellation,

        /** 分配结果：fleet（group）+ 机器人名（name）。 */
        String assignedFleet,
        String assignedRobotName,

        /** 关联的调度请求 ID（内核受理时回填记录用，可为空）。 */
        String schedulerRequestId,

        /** 状态说明（失败真实原因/阶段说明），透传到任务状态接口供上层观测。 */
        String message,

        /** 事件时间（毫秒）。 */
        long emittedAtMillis) {

    public TaskStateEvent(String taskId, String status, String category,
                          Map<String, Object> detail, Map<String, Object> cancellation,
                          String assignedFleet, String assignedRobotName, long emittedAtMillis) {
        this(taskId, status, category, detail, cancellation, assignedFleet, assignedRobotName,
                null, null, emittedAtMillis);
    }

    /** 带 schedulerRequestId 的构造（测试与内核受理链路用）。 */
    public TaskStateEvent(String taskId, String status, String category,
                          Map<String, Object> detail, Map<String, Object> cancellation,
                          String assignedFleet, String assignedRobotName, String schedulerRequestId,
                          long emittedAtMillis) {
        this(taskId, status, category, detail, cancellation, assignedFleet, assignedRobotName,
                schedulerRequestId, null, emittedAtMillis);
    }

    /** 携带失败原因/说明的构造（避免与 schedulerRequestId 重载歧义）。 */
    public static TaskStateEvent withMessage(String taskId, String status, String category,
                                             Map<String, Object> detail, Map<String, Object> cancellation,
                                             String assignedFleet, String assignedRobotName,
                                             String schedulerRequestId, String message,
                                             long emittedAtMillis) {
        return new TaskStateEvent(taskId, status, category, detail, cancellation, assignedFleet,
                assignedRobotName, schedulerRequestId, message, emittedAtMillis);
    }

    public boolean isTerminal() {
        return "completed".equals(status) || "failed".equals(status)
                || "killed".equals(status) || "canceled".equals(status);
    }

    public boolean isCancellationMarked() {
        return cancellation != null && !cancellation.isEmpty();
    }
}
