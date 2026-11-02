package com.zioneer.robotscheduler.common.scheduler.task;

import java.util.Map;

/**
 * 任务类型契约：内核 category 集合与旧栈 payload_builder 的类型归一规则。
 */
public final class TaskCategories {

    private TaskCategories() {
    }

    /** 点到点移动。 */
    public static final String GO_TO_PLACE = "go_to_place";

    /** 质检巡检（本项目核心业务任务）。 */
    public static final String QUALITY_INSPECTION = "quality_inspection";

    /** 充电任务。 */
    public static final String CHARGE = "charge";

    /** 换电任务。 */
    public static final String SWAP_BATTERY = "swap_battery";

    /** 返回家点。 */
    public static final String RETURN_HOME = "return_home";

    /** 暂停任务（compose 类型）。 */
    public static final String PAUSE_TASK = "pause_task";

    /** 恢复任务（compose 类型）。 */
    public static final String RESUME_TASK = "resume_task";

    /** 仅缓存任务上下文，不产生动作。 */
    public static final String CACHE_TASK_CONTEXT = "cache_task_context";

    /** compose 编排类型（pause/resume 使用）。 */
    public static final String COMPOSE = "compose";

    /** 旧栈类型归一映射：入参别名 → 内核 category。 */
    public static final Map<String, String> ALIAS_MAPPING = Map.of(
            "battery_swap", SWAP_BATTERY,
            "returnHome", RETURN_HOME,
            "pause", PAUSE_TASK,
            "resume", RESUME_TASK);

    /** 已下线类型：请求直接拒绝（400）。 */
    public static final java.util.Set<String> REMOVED_TYPES = java.util.Set.of(
            "replaceBattery", "replace_battery");

    /**
     * 归一化任务类型；无别名时原样返回（未知类型由后续校验拦截）。
     */
    public static String normalize(String requestedType) {
        if (requestedType == null) {
            return null;
        }
        return ALIAS_MAPPING.getOrDefault(requestedType, requestedType);
    }
}
