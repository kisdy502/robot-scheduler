package com.zioneer.robotscheduler.common.core.error;

/**
 * 调度服务业务错误码定义。
 *
 * <p>编号约定（对齐平台 AGENTS.md 分段规范）：
 * 1xxxx 通用基础错误码（与 robot-common-core 语义一致，本模块内自持避免跨服务 SNAPSHOT 耦合）；
 * 6xxxx 调度服务错误码（平台已用 1xxxx 通用/2xxxx 质检/3xxxx 网关/4xxxx 机器人地图/5xxxx 任务流）。</p>
 */
public enum ErrorCode {

    // ==================== 通用基础错误码（1xxxx） ====================

    /** 通用请求错误。 */
    BAD_REQUEST(10000, "请求错误"),

    /** 请求参数校验失败。 */
    REQUEST_PARAMETER_VALIDATION_FAILED(10001, "请求参数校验失败"),

    /** 数据已存在或违反数据库约束。 */
    DATA_INTEGRITY_VIOLATION(10002, "数据已存在或违反数据库约束"),

    /** 未授权。 */
    UNAUTHORIZED(10100, "未授权"),

    /** 无权限访问。 */
    FORBIDDEN(10300, "无权限访问"),

    /** 资源不存在。 */
    NOT_FOUND(10400, "资源不存在"),

    /** 服务器内部错误。 */
    INTERNAL_ERROR(15000, "服务器内部错误"),

    // ==================== 调度服务错误码（6xxxx） ====================

    /** 任务状态记录不存在（按业务任务 ID / 调度请求 ID 查询失败）。 */
    SCHEDULER_TASK_RECORD_NOT_FOUND(60400, "任务状态记录不存在"),

    /** 充电桩候选不存在于充电桩池。 */
    SCHEDULER_CHARGER_NOT_FOUND(60401, "充电桩不存在"),

    /** 任务尚未收到调度内核任务 ID，无法取消。 */
    SCHEDULER_TASK_ID_NOT_READY(60402, "任务尚未收到调度内核任务 ID，无法取消"),

    /** 地图仍被任务或交通资源使用，拒绝激活。 */
    SCHEDULER_MAP_SWITCH_CONFLICT(60409, "地图正在使用，暂不可切换"),

    /** 自动机器人分配暂不可用，robot_code 当前必填（保持旧接口 501 语义）。 */
    SCHEDULER_ROBOT_ALLOCATION_UNSUPPORTED(60501, "automatic robot allocation requires fleet state integration; robot_code is required for now"),

    /** 激活的导航图未加载，无法执行点位相关任务。 */
    SCHEDULER_NAV_GRAPH_NOT_LOADED(60502, "active nav_graph is not loaded; waypoint validation is unavailable"),

    /** 场景资源服务暂不可用。 */
    SCHEDULER_SCENE_RESOURCE_UNAVAILABLE(60503, "场景资源服务不可用"),

    /** 充电桩分配服务响应缺少必需字段。 */
    SCHEDULER_CHARGER_RESPONSE_INVALID(60504, "充电桩分配服务响应无效");

    private final int code;
    private final String msg;

    ErrorCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public int getCode() {
        return code;
    }

    public String getMsg() {
        return msg;
    }
}
