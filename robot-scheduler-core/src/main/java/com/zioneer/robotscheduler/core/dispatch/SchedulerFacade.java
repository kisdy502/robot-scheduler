package com.zioneer.robotscheduler.core.dispatch;

import java.util.Map;

/**
 * 调度门面：api 层 18 个端点对应的业务能力入口（接口与实现分离，便于 api 层 Mock 内核做契约测试）。
 *
 * <p>方法语义逐条对齐旧栈服务层（task_dispatch/task_cancel/task_query/resource/runtime_snapshot/pre_switch_check
 * 六个 service）；字段兼容细节见方案 §6.2。</p>
 */
public interface SchedulerFacade {

    /** POST /scheduler/assign-task：记录任务流占用的机器人（自动分配暂不支持，robot_code 必填）。 */
    Map<String, Object> assignTask(String taskflowId, java.util.List<String> taskTemplateTypeList,
                                   String robotCode, String robotType, String robotGroup,
                                   String navPointStart, Integer priority);

    /** POST /scheduler/goto-place：点到点移动。返回 dispatch data（8 字段结构）。 */
    Map<String, Object> gotoPlace(String taskflowId, String taskId, String robotCode,
                                  String navTargetPoint, String robotGroup, String taskTemplateId,
                                  String taskTemplateCode, String earliestStartTime,
                                  Map<String, Object> parameters);

    /** POST /scheduler/dispatch_task：通用任务下发。返回 dispatch data。 */
    Map<String, Object> dispatchTask(String taskflowId, String taskId, String robotCode, String fleetName,
                                     String robotGroup, String taskTemplateId, String taskTemplateCode,
                                     String taskTemplateType, String navStartPoint, String navEndPoint,
                                     String endAction, String earliestStartTime, Map<String, Object> parameters);

    /** POST /scheduler/stop-task：取消任务。 */
    Map<String, Object> stopTask(String taskId, String rmfTaskId, String robotCode, String reason);

    /** GET /scheduler/rmf/tasks：任务列表。 */
    Map<String, Object> listTasks(String robotCode, boolean executionFinal, String status,
                                  int limit, boolean verbose);

    /** GET /scheduler/tasks/{task_id}/status：按业务任务 ID 查询。 */
    Map<String, Object> queryTaskStatus(String taskId);

    /** GET /scheduler/tasks/status?scheduler_request_id=：按调度请求 ID 查询。 */
    Map<String, Object> queryTaskStatusByRequestId(String schedulerRequestId);

    /** GET /scheduler/pre-switch-check：切换地图预检。 */
    Map<String, Object> preSwitchCheck(String sceneCode, String fleetName);

    /** POST /scheduler/release-robot：释放任务流占用的机器人。 */
    Map<String, Object> releaseRobot(String taskflowId, String robotCode);

    /** POST /scheduler/robot-use/heartbeat：使用权心跳（租约续期，长外部作业期间上层必调）。 */
    Map<String, Object> ownershipHeartbeat(String taskflowId);

    /** POST /scheduler/force-release-robot：强制释放（运维逃生门，有活动任务 409）。 */
    Map<String, Object> forceReleaseRobot(String robotCode, String reason);

    /** POST /scheduler/assign-charger：分配充电桩。 */
    Map<String, Object> assignCharger(String robotCode, java.util.List<String> chargerWaypointList,
                                      String resourceType);

    /** POST /scheduler/release-charger：释放充电桩。 */
    Map<String, Object> releaseCharger(String robotCode, String chargerWaypoint, String resourceType);

    /** GET /scheduler/robot-resources：机器人资源占用查询。 */
    Map<String, Object> robotResources(String robotCode);

    /** POST /scheduler/release-resources：释放机器人全部资源。 */
    Map<String, Object> releaseResources(String robotCode);

    /** GET /scheduler/scene-resources/status。 */
    Map<String, Object> sceneResourceStatus();

    /** POST /scheduler/scene-resources/reload（当前固定 skipped 响应，保持兼容）。 */
    Map<String, Object> reloadSceneResources(java.util.List<String> resourceTypes, boolean applyRuntime);

    /** GET /resources/status（scene-resource/status + local_path）。 */
    Map<String, Object> resourcesStatus();

    /** GET /runtime/fleets/snapshot：运行时全量快照。 */
    Map<String, Object> runtimeSnapshot(String sceneCode, String fleetName, String rmfLevel, int taskLimit);
    /** 外部业务作业通知；仅管理机器人使用状态，不执行质检或夹爪业务。 */
    Map<String, Object> startExternalOperation(String taskflowId, String robotCode, String operationId);
    Map<String, Object> finishExternalOperation(String taskflowId, String robotCode, String operationId);
    Map<String, Object> robotUse(String robotCode);
}
