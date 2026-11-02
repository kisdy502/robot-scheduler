package com.zioneer.robotscheduler.core.task;

import java.util.Map;

/**
 * 任务执行目标（由调度请求解析而来）：
 * 导航目标（goalWaypointId 非空时先导航）+ 业务动作（action 非空时导航后执行）。
 */
/**
 * 任务执行目标（调度请求 → 执行语义的中间表示）。
 *
 * <p>由 facade 按 task_template_type 构建：
 * 纯导航（go_to_place/return_home）、导航+动作（quality_inspection/charge/swap_battery）、
 * 纯动作（pause_task/resume_task）、立即完成（cache_task_context 等无导航无动作）。
 * 交给 {@link TaskExecutionEngine#submit} 执行。</p>
 */
public record TaskGoal(

        /** 任务类别（go_to_place/quality_inspection/charge/swap_battery/return_home/pause_task/resume_task）。 */
        String category,

        /** 目标楼层（空=当前激活楼层）。 */
        String floorId,

        /** 导航目标点位 id（空=无导航，纯动作任务）。 */
        String goalWaypointId,

        /** 业务动作类别（空=纯导航任务）。 */
        String action,

        /** 动作 label（业务上下文透传 RMS）。 */
        String actionLabel,

        /** 任务完成后要去的停靠等待点（空=原地待命）；质检场景：做完立刻让出作业点。 */
        String standbyWaypointId,

        /** 原始请求参数（透传 description）。 */
        Map<String, Object> parameters) {

    public boolean requiresNavigation() {
        return goalWaypointId != null && !goalWaypointId.isBlank();
    }

    public boolean requiresAction() {
        return action != null && !action.isBlank();
    }

    public boolean requiresStandby() {
        return standbyWaypointId != null && !standbyWaypointId.isBlank();
    }

    /** 兼容构造器（无停靠点）。 */
    public TaskGoal(String category, String floorId, String goalWaypointId, String action,
                    String actionLabel, Map<String, Object> parameters) {
        this(category, floorId, goalWaypointId, action, actionLabel, null, parameters);
    }
}
