package com.zioneer.robotscheduler.core.dispatch;

import com.zioneer.robotscheduler.common.scheduler.SchedulerConstants;
import com.zioneer.robotscheduler.common.scheduler.task.DispatchStatus;
import com.zioneer.robotscheduler.common.scheduler.task.TaskCategories;
import com.zioneer.robotscheduler.common.scheduler.task.TaskStatus;
import com.zioneer.robotscheduler.core.charger.ChargerInfo;
import com.zioneer.robotscheduler.core.charger.ChargerPoolPort;
import com.zioneer.robotscheduler.core.event.SchedulerEventBus;
import com.zioneer.robotscheduler.core.fleet.FleetStateMirror;
import com.zioneer.robotscheduler.core.fleet.RobotSnapshot;
import com.zioneer.robotscheduler.core.fleet.TaskAssignmentRegistry;
import com.zioneer.robotscheduler.core.map.MapManager;
import com.zioneer.robotscheduler.core.repository.TaskRecord;
import com.zioneer.robotscheduler.core.repository.TaskRecordRepository;
import com.zioneer.robotscheduler.core.task.TaskGoal;
import com.zioneer.robotscheduler.core.task.TaskStateEvent;
import com.zioneer.robotscheduler.core.task.TaskStateMirror;
import com.zioneer.robotscheduler.core.task.TaskExecutionEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 门面默认实现（P1：受理/记录/查询/资源闭环 + 镜像聚合；内核执行链路 P3 接入）。
 *
 * <p>查询判定链对齐旧栈 resolve_polished_task_status：
 * task_state（终态优先）&gt; cancel_requested &gt; 回执 errors &gt; 回执超时 &gt; published=queued &gt; unknown。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultSchedulerFacade implements SchedulerFacade {

    private static final Set<String> VALID_END_ACTIONS = Set.of("standby", "return_home", "return_idle_zone");

    private final TaskRecordRepository taskRecordRepository;
    private final ChargerPoolPort chargerPool;
    private final TaskStateMirror taskStateMirror;
    private final FleetStateMirror fleetStateMirror;
    private final TaskAssignmentRegistry assignmentRegistry;
    private final SchedulerEventBus eventBus;
    private final MapManager mapManager;
    private final TaskExecutionEngine executionEngine;

    /**
     * 内核任务状态回执入口：首次收到某调度请求的事件时回填记录的 rmf_task_id 与受理状态
     * （对齐旧栈 _on_api_response 的记录更新语义）。
     */
    @EventListener
    public void onTaskStateEvent(TaskStateEvent event) {
        String requestId = event.schedulerRequestId();
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        taskRecordRepository.findBySchedulerRequestId(requestId).ifPresent(record -> {
            boolean changed = record.getRmfTaskId() == null;
            record.setRmfTaskId(event.taskId());
            if (record.getDispatchStatus() == DispatchStatus.PUBLISHED) {
                record.setDispatchStatus(DispatchStatus.RMF_RESPONSE_RECEIVED);
            }
            record.setUpdatedAtMillis(System.currentTimeMillis());
            if (changed) {
                taskRecordRepository.update(record);
            }
        });
    }

    @Override
    public Map<String, Object> assignTask(String taskflowId, List<String> taskTemplateTypeList,
                                          String robotCode, String robotType, String robotGroup,
                                          String navPointStart, Integer priority) {
        // 旧栈语义：自动分配需要车队状态集成，当前 robot_code 必填，否则 501
        if (robotCode == null || robotCode.isBlank()) {
            throw new SchedulerHttpException(501,
                    "automatic robot allocation requires fleet state integration; robot_code is required for now");
        }
        assignmentRegistry.assign(taskflowId, robotCode);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("robot_code", robotCode);
        return data;
    }

    @Override
    public Map<String, Object> releaseRobot(String taskflowId, String robotCode) {
        if (taskflowId == null || taskflowId.isBlank()) {
            throw SchedulerHttpException.badRequest("taskflow_id is required");
        }
        assignmentRegistry.release(taskflowId, robotCode);
        return null;
    }

    @Override
    public Map<String, Object> gotoPlace(String taskflowId, String taskId, String robotCode,
                                         String navTargetPoint, String robotGroup, String taskTemplateId,
                                         String taskTemplateCode, String earliestStartTime,
                                         Map<String, Object> parameters) {
        if (navTargetPoint == null || navTargetPoint.isBlank()) {
            throw SchedulerHttpException.badRequest("nav_target_point is required");
        }
        validateEarliestStartTime(earliestStartTime);
        return createDispatchRecord(TaskCategories.GO_TO_PLACE, taskflowId, taskId, taskTemplateCode, robotCode,
                null, navTargetPoint, parameters, robotGroup, null);
    }

    @Override
    public Map<String, Object> dispatchTask(String taskflowId, String taskId, String robotCode, String fleetName,
                                            String robotGroup, String taskTemplateId, String taskTemplateCode,
                                            String taskTemplateType, String navStartPoint, String navEndPoint,
                                            String endAction, String earliestStartTime,
                                            Map<String, Object> parameters) {
        if (taskTemplateType == null || taskTemplateType.isBlank()) {
            throw SchedulerHttpException.badRequest("task_template_type is required");
        }
        if (TaskCategories.REMOVED_TYPES.contains(taskTemplateType)) {
            throw SchedulerHttpException.badRequest(
                    "task_template_type '" + taskTemplateType + "' is no longer supported; use 'swap_battery'");
        }
        validateEndAction(endAction);
        validateEarliestStartTime(earliestStartTime);
        validateFleetGroupConsistency(fleetName, robotGroup);
        String category = TaskCategories.normalize(taskTemplateType);
        if (TaskCategories.RETURN_HOME.equals(category)) {
            validateReturnHome(robotCode, navEndPoint, parameters);
        }
        // 目标点类型约束（受理即拒，不入队）：质检→作业点，充电→充电位
        validateTargetPointType(category, navStartPoint, navEndPoint, parameters);
        return createDispatchRecord(category, taskflowId, taskId, taskTemplateCode, robotCode,
                navStartPoint, navEndPoint, parameters, robotGroup, fleetName);
    }

    @Override
    public Map<String, Object> stopTask(String taskId, String rmfTaskId, String robotCode, String reason) {
        Optional<TaskRecord> record = locateRecord(taskId, rmfTaskId, robotCode);
        if (record.isEmpty()) {
            throw SchedulerHttpException.notFound("task status record not found");
        }
        TaskRecord taskRecord = record.get();
        if (taskRecord.getRmfTaskId() == null) {
            throw SchedulerHttpException.conflict(
                    "task has not received an RMF task id yet and cannot be canceled");
        }
        // 幂等：已 cancel_requested 的任务保持可寻址，重复 stop 返回当前状态
        Map<String, Object> cancelRequest = taskRecord.getCancelRequest();
        if (cancelRequest == null) {
            cancelRequest = new LinkedHashMap<>();
            cancelRequest.put("stop_request_id", UUID.randomUUID().toString());
            cancelRequest.put("reason", reason);
            cancelRequest.put("requested_at", System.currentTimeMillis());
            taskRecord.setCancelRequest(cancelRequest);
            taskRecord.setDispatchStatus(DispatchStatus.CANCEL_REQUESTED);
            taskRecord.setUpdatedAtMillis(System.currentTimeMillis());
            taskRecordRepository.update(taskRecord);
            // 内核取消链路：排队→canceled；执行中→机器人 stop + completed+cancellation（fork 语义）；
            // 内核态丢失（重启孤儿）→ 止损取消。robot_code 回退：自动分配任务受理时未指定，
            // 记录字段可能为空，取请求参数兜底（孤儿取消发 stop 依赖它）
            String effectiveRobot = taskRecord.getRobotCode() != null && !taskRecord.getRobotCode().isBlank()
                    ? taskRecord.getRobotCode() : robotCode;
            executionEngine.cancel(taskRecord.getRmfTaskId(), taskRecord, effectiveRobot, reason);
        }
        return buildStopResponse(taskRecord);
    }

    @Override
    public Map<String, Object> listTasks(String robotCode, boolean executionFinal, String status,
                                         int limit, boolean verbose) {
        if (limit <= 0) {
            throw SchedulerHttpException.badRequest("limit must be a positive integer");
        }
        int effectiveLimit = Math.min(limit, SchedulerConstants.MAX_RMF_TASK_LIST_LIMIT);
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (TaskStateEvent event : taskStateMirror.list()) {
            TaskStatus resolved = resolveMirrorStatus(event);
            if (!executionFinal && resolved.isTerminal()) {
                continue;
            }
            if (status != null && !status.isBlank() && !status.equals(resolved.wireName())) {
                continue;
            }
            if (robotCode != null && !robotCode.isBlank()
                    && !robotCode.equals(event.assignedRobotName())) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rmf_task_id", event.taskId());
            TaskRecord record = taskRecordRepository.findByRmfTaskId(event.taskId()).orElse(null);
            row.put("business_task_id", record == null ? event.taskId() : record.getBusinessTaskId());
            row.put("robot_code", event.assignedRobotName());
            row.put("category", event.category());
            row.put("task_status", resolved.wireName());
            row.put("execution_final", resolved.isTerminal());
            row.put("message", resolveTaskMessage(event, resolved));
            row.put("updated_at", event.emittedAtMillis());
            if (verbose) {
                row.put("raw", Map.of("task_state", toTaskStateJson(event)));
            }
            tasks.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tasks", tasks.size() > effectiveLimit ? tasks.subList(0, effectiveLimit) : tasks);
        data.put("count", Math.min(tasks.size(), effectiveLimit));
        data.put("total", tasks.size());
        data.put("execution_final_included", executionFinal);
        return data;
    }

    @Override
    public Map<String, Object> queryTaskStatus(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw SchedulerHttpException.badRequest("task_id is required");
        }
        return taskRecordRepository.findByBusinessTaskId(taskId)
                .map(this::buildStatusResponse)
                .orElseThrow(() -> SchedulerHttpException.notFound("task status record not found: " + taskId));
    }

    @Override
    public Map<String, Object> queryTaskStatusByRequestId(String schedulerRequestId) {
        if (schedulerRequestId == null || schedulerRequestId.isBlank()) {
            throw SchedulerHttpException.badRequest("scheduler_request_id is required");
        }
        return taskRecordRepository.findBySchedulerRequestId(schedulerRequestId)
                .map(this::buildStatusResponse)
                .orElseThrow(() -> SchedulerHttpException.notFound(
                        "task status record not found: " + schedulerRequestId));
    }

    @Override
    public Map<String, Object> preSwitchCheck(String sceneCode, String fleetName) {
        List<RobotSnapshot> robots = fleetStateMirror.listByFleet(fleetName);
        Map<String, Object> data = new LinkedHashMap<>();
        if (robots.isEmpty()) {
            // 旧栈语义：看不到 fleet_states 时 unknown 且 allowed=true
            data.put("allowed", true);
            data.put("check_result", "unknown");
            data.put("reasons", List.of());
            data.put("warnings", List.of());
            data.put("scene_code", sceneCode);
            data.put("requested_scene_code", sceneCode);
            data.put("fleet_name", fleetName);
            data.put("robots", emptyRobotSummary());
            data.put("tasks", emptyTaskSummary());
            data.put("checked_at", System.currentTimeMillis());
            return data;
        }
        List<Map<String, Object>> busyRobots = new ArrayList<>();
        int busy = 0;
        int idle = 0;
        int stale = 0;
        long latestUpdate = 0;
        for (RobotSnapshot robot : robots) {
            boolean staleRobot = !fleetStateMirror.isOnline(robot);
            if (staleRobot) {
                stale++;
            }
            latestUpdate = Math.max(latestUpdate, robot.getUpdatedAtMillis());
            if ("running".equals(robot.getMode())) {
                busy++;
                if (busyRobots.size() < SchedulerConstants.PRE_SWITCH_CHECK_MAX_DETAIL_ITEMS) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("robot_code", robot.getRobotCode());
                    row.put("fleet_name", robot.getFleetName());
                    row.put("mode", robot.getMode());
                    row.put("current_point", robot.getTargetPoint());
                    row.put("stale", staleRobot);
                    row.put("updated_at", robot.getUpdatedAtMillis());
                    busyRobots.add(row);
                }
            } else if ("idle".equals(robot.getMode()) || "waiting".equals(robot.getMode())
                    || "charging".equals(robot.getMode())) {
                idle++;
            }
        }
        List<Map<String, Object>> pendingTasks = new ArrayList<>();
        int queued = 0;
        int running = 0;
        int other = 0;
        boolean truncated = false;
        for (TaskStateEvent event : taskStateMirror.list()) {
            TaskStatus status = resolveMirrorStatus(event);
            if (status.isTerminal()) {
                continue;
            }
            if (TaskStatus.QUEUED.equals(status)) {
                queued++;
            } else if (TaskStatus.RUNNING.equals(status)) {
                running++;
            } else {
                other++;
            }
            if (pendingTasks.size() >= SchedulerConstants.PRE_SWITCH_CHECK_MAX_DETAIL_ITEMS) {
                truncated = true;
            } else {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("task_id", event.taskId());
                row.put("rmf_task_id", event.taskId());
                row.put("robot_code", event.assignedRobotName());
                row.put("category", event.category());
                row.put("task_status", status.wireName());
                row.put("updated_at", event.emittedAtMillis());
                pendingTasks.add(row);
            }
        }
        int pendingTotal = queued + running + other;
        List<String> reasons = new ArrayList<>();
        if (busy > 0) {
            reasons.add("robots are running");
        }
        if (pendingTotal > 0) {
            reasons.add("tasks are pending");
        }
        String checkResult = busy > 0 && pendingTotal > 0 ? "robots_and_tasks_busy"
                : busy > 0 ? "robots_running"
                : pendingTotal > 0 ? "tasks_pending"
                : "ready";
        data.put("allowed", reasons.isEmpty());
        data.put("check_result", checkResult);
        data.put("reasons", reasons);
        data.put("warnings", List.of());
        data.put("scene_code", sceneCode);
        data.put("requested_scene_code", sceneCode);
        data.put("fleet_name", fleetName);
        Map<String, Object> robotSummary = new LinkedHashMap<>();
        robotSummary.put("total", robots.size());
        robotSummary.put("busy", busy);
        robotSummary.put("idle", idle);
        robotSummary.put("stale", stale);
        robotSummary.put("busy_robots", busyRobots);
        data.put("robots", robotSummary);
        Map<String, Object> taskSummary = new LinkedHashMap<>();
        taskSummary.put("pending", queued);
        taskSummary.put("queued", queued);
        taskSummary.put("running", running);
        taskSummary.put("other", other);
        taskSummary.put("truncated", truncated);
        taskSummary.put("pending_tasks", pendingTasks);
        data.put("tasks", taskSummary);
        data.put("checked_at", System.currentTimeMillis());
        return data;
    }

    @Override
    public Map<String, Object> assignCharger(String robotCode, List<String> chargerWaypointList, String resourceType) {
        if (robotCode == null || robotCode.isBlank()) {
            throw SchedulerHttpException.badRequest("robot_code is required");
        }
        Optional<ChargerInfo> allocated = chargerPool.allocate(robotCode, null, chargerWaypointList, resourceType);
        if (allocated.isEmpty()) {
            String detail = chargerWaypointList == null || chargerWaypointList.isEmpty()
                    ? "no charger is available"
                    : "no candidate charger is available";
            throw SchedulerHttpException.conflict(detail);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("charger_waypoint", allocated.get().getRmfWaypoint());
        return data;
    }

    @Override
    public Map<String, Object> releaseCharger(String robotCode, String chargerWaypoint, String resourceType) {
        boolean hasTarget = (robotCode != null && !robotCode.isBlank())
                || (chargerWaypoint != null && !chargerWaypoint.isBlank());
        if (!hasTarget) {
            throw SchedulerHttpException.badRequest("robot_code or charger_waypoint is required");
        }
        chargerPool.release(robotCode, chargerWaypoint, resourceType);
        return null;
    }

    @Override
    public Map<String, Object> robotResources(String robotCode) {
        // 旧栈语义：资源记录恒空，保持 {}
        return Map.of();
    }

    @Override
    public Map<String, Object> releaseResources(String robotCode) {
        if (robotCode == null || robotCode.isBlank()) {
            throw SchedulerHttpException.badRequest("robot_code is required");
        }
        chargerPool.release(robotCode, null, null);
        return null;
    }

    @Override
    public Map<String, Object> sceneResourceStatus() {
        // 数据源为单 json 地图元数据（MapManager）；对外字段结构保持旧契约
        Map<String, Object> data = new LinkedHashMap<>();
        if (mapManager.isLoaded()) {
            var metadata = mapManager.activeMetadata().orElseThrow();
            data.put("configured", true);
            data.put("scene_code", metadata.getMapName());
            data.put("bundle_version", metadata.getVersionInfo().getVersion());
            data.put("status", "active");
            data.put("source", "map_json");
            data.put("loaded_at", mapManager.loadedAt() == null ? null
                    : mapManager.loadedAt().toEpochMilli());
            data.put("resource_types", List.of("map_metadata"));
            data.put("rmf_levels", mapManager.activeGraph()
                    .map(graph -> graph.floorIds()).orElse(List.of()));
        } else {
            data.put("configured", false);
            data.put("scene_code", null);
            data.put("bundle_version", null);
            data.put("status", "not_loaded");
            data.put("source", null);
            data.put("loaded_at", null);
            data.put("resource_types", List.of());
            data.put("rmf_levels", List.of());
        }
        data.put("last_error", null);
        return data;
    }

    @Override
    public Map<String, Object> reloadSceneResources(List<String> resourceTypes, boolean applyRuntime) {
        // 旧栈固定 skipped 响应（scene resource reload 已临时禁用），保持字段一模一样
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "skipped");
        data.put("action", "reload");
        data.put("resource_update_enabled", false);
        data.put("message",
                "scene resource reload/sync is temporarily disabled; using existing local current scene resources");
        data.put("scene_code", null);
        data.put("requested_bundle_version", null);
        data.put("resource_types", resourceTypes == null ? List.of() : resourceTypes);
        data.put("changed_types", List.of());
        data.put("restart_required", List.of());
        data.put("restart_targets", List.of());
        data.put("restart_executed", false);
        data.put("runtime_reload", List.of(Map.of(
                "component", "runtime_consumers",
                "success", true,
                "action", "skipped",
                "message", "resource update disabled")));
        return data;
    }

    @Override
    public Map<String, Object> resourcesStatus() {
        Map<String, Object> data = new LinkedHashMap<>(sceneResourceStatus());
        data.put("local_path", null);
        return data;
    }

    @Override
    public Map<String, Object> runtimeSnapshot(String sceneCode, String fleetName, String rmfLevel, int taskLimit) {
        int effectiveLimit = Math.max(1, Math.min(taskLimit, SchedulerConstants.MAX_RUNTIME_TASK_LIST_LIMIT));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("generated_at", System.currentTimeMillis());
        data.put("scene", sceneResourceStatus());

        List<RobotSnapshot> robots = fleetStateMirror.listByFleet(fleetName).stream()
                .filter(robot -> rmfLevel == null || rmfLevel.isBlank() || rmfLevel.equals(robot.getMap()))
                .toList();

        Map<String, List<TaskStateEvent>> activeTasksByRobot = new LinkedHashMap<>();
        List<TaskStateEvent> activeTasks = new ArrayList<>();
        for (TaskStateEvent event : taskStateMirror.list()) {
            TaskStatus status = resolveMirrorStatus(event);
            if (status.isTerminal()) {
                continue;
            }
            activeTasks.add(event);
            if (event.assignedRobotName() != null) {
                activeTasksByRobot.computeIfAbsent(event.assignedRobotName(), k -> new ArrayList<>()).add(event);
            }
        }

        List<Map<String, Object>> robotRows = new ArrayList<>();
        Map<String, Map<String, Object>> fleetAggregates = new LinkedHashMap<>();
        for (RobotSnapshot robot : robots) {
            boolean online = fleetStateMirror.isOnline(robot);
            // 引擎视角的可派发判定（含地图一致性/离图/移动检测）且必须在线
            boolean dispatchable = online && robot.isDispatchable();
            TaskStateEvent currentTask = activeTasksByRobot.getOrDefault(robot.getRobotCode(), List.of())
                    .stream().findFirst().orElse(null);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("robot_code", robot.getRobotCode());
            row.put("robot_name", robot.getRobotName());
            row.put("fleet_name", robot.getFleetName());
            row.put("scene_code", sceneCode);
            row.put("rmf_level", robot.getMap());
            row.put("slam_map_code", robot.getSlamMapCode());
            row.put("online_status", online ? "online" : "offline");
            row.put("mode", robot.getMode());
            row.put("dispatchable", dispatchable);
            row.put("decommissioned", Boolean.TRUE.equals(robot.getDecommissioned()));
            row.put("battery", robot.getBattery() == null ? null
                    : Math.round(robot.getBattery() * 10.0) / 10.0);
            row.put("home_point", robot.getHomeWaypoint());
            row.put("current_point", robot.getMap());
            row.put("target_point", robot.getTargetPoint());
            row.put("current_map", robot.getMap());
            row.put("pose", robot.getX() == 0 && robot.getY() == 0 && robot.getYaw() == 0 && robot.getMap() == null
                    ? null
                    : Map.of("x", robot.getX(), "y", robot.getY(), "yaw", robot.getYaw(),
                            "level_name", robot.getMap() == null ? "" : robot.getMap(), "index", 0));
            row.put("compatible_chargers", robot.getCompatibleChargers());
            row.put("assigned_charger_id", null);
            row.put("assigned_charger_waypoint", null);
            row.put("current_task_id", currentTask == null ? null : currentTask.taskId());
            row.put("scheduler_request_id", null);
            row.put("rmf_task_id", currentTask == null ? null : currentTask.taskId());
            row.put("task_status", currentTask == null ? null
                    : resolveMirrorStatus(currentTask).wireName());
            row.put("updated_at", robot.getUpdatedAtMillis());
            robotRows.add(row);

            Map<String, Object> fleet = fleetAggregates.computeIfAbsent(
                    robot.getFleetName() == null ? SchedulerConstants.DEFAULT_FLEET_NAME : robot.getFleetName(),
                    k -> newFleetAggregate(k, sceneCode));
            fleet.put("robot_total", (Integer) fleet.get("robot_total") + 1);
            if (online) {
                fleet.put("online_count", (Integer) fleet.get("online_count") + 1);
            }
            if (dispatchable) {
                fleet.put("available_count", (Integer) fleet.get("available_count") + 1);
            }
            incrementModeCount(fleet, robot.getMode());
            fleet.put("last_heartbeat", Math.max(
                    (Long) fleet.get("last_heartbeat"), robot.getUpdatedAtMillis()));
        }
        for (TaskStateEvent event : activeTasks) {
            String fleetKey = event.assignedFleet() == null ? SchedulerConstants.DEFAULT_FLEET_NAME
                    : event.assignedFleet();
            Map<String, Object> fleet = fleetAggregates.get(fleetKey);
            if (fleet != null) {
                fleet.put("queued_task_count", (Integer) fleet.get("queued_task_count") + 1);
                if (TaskStatus.RUNNING.equals(resolveMirrorStatus(event))) {
                    fleet.put("running_task_count", (Integer) fleet.get("running_task_count") + 1);
                }
            }
        }

        List<Map<String, Object>> taskRows = new ArrayList<>();
        for (TaskStateEvent event : activeTasks) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("task_id", event.taskId());
            row.put("scheduler_request_id", event.schedulerRequestId());
            row.put("rmf_task_id", event.taskId());
            row.put("fleet_name", event.assignedFleet());
            row.put("robot_code", event.assignedRobotName());
            row.put("category", event.category());
            row.put("task_status", resolveMirrorStatus(event).wireName());
            row.put("start_point", null);
            row.put("target_point", null);
            row.put("end_point", null);
            row.put("rmf_level", null);
            row.put("error_message", null);
            row.put("created_at", event.emittedAtMillis());
            row.put("updated_at", event.emittedAtMillis());
            taskRows.add(row);
        }

        List<ChargerInfo> chargers = chargerPool.list(fleetName);
        int chargerAvailable = 0;
        int chargerOccupied = 0;
        int chargerError = 0;
        List<Map<String, Object>> chargerItems = new ArrayList<>();
        for (ChargerInfo charger : chargers) {
            if (charger.getStatus() == 0) {
                chargerAvailable++;
            } else if (charger.getStatus() == 1) {
                chargerOccupied++;
            } else {
                chargerError++;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("charger_id", charger.getChargerId());
            item.put("charger_name", charger.getName());
            item.put("resource_type", charger.getResourceType());
            item.put("connector_type", charger.getConnectorType());
            item.put("fleet_name", charger.getFleetName());
            item.put("compatible_fleets", charger.getCompatibleFleets());
            item.put("scene_code", charger.getSceneCode());
            item.put("rmf_level", charger.getRmfLevel());
            item.put("map_code", charger.getMapCode());
            item.put("waypoint", charger.getRmfWaypoint());
            item.put("rmf_waypoint", charger.getRmfWaypoint());
            item.put("point_code", charger.getPointCode());
            item.put("status", charger.getStatus());
            item.put("occupied_by", charger.getOccupiedBy());
            item.put("reserved_by", charger.getReservedBy());
            item.put("updated_at", charger.getUpdatedAtMillis());
            chargerItems.add(item);
        }
        Map<String, Object> chargerSummary = new LinkedHashMap<>();
        chargerSummary.put("total", chargers.size());
        chargerSummary.put("available", chargerAvailable);
        chargerSummary.put("occupied", chargerOccupied);
        chargerSummary.put("error", chargerError);
        chargerSummary.put("last_error", null);
        chargerSummary.put("items", chargerItems);

        Map<String, Object> planning = new LinkedHashMap<>();
        planning.put("robot_count", robots.size());
        planning.put("available_robot_count", robots.stream().filter(fleetStateMirror::isDispatchable).count());
        planning.put("request_count", 0);
        planning.put("queued_request_count", 0);
        planning.put("running_request_count", 0);

        data.put("fleets", new ArrayList<>(fleetAggregates.values()));
        data.put("robots", robotRows);
        data.put("tasks", taskRows.size() > effectiveLimit
                ? taskRows.subList(0, effectiveLimit) : taskRows);
        data.put("task_total", taskRows.size());
        data.put("task_list_limit", effectiveLimit);
        data.put("planning", planning);
        data.put("chargers", chargerSummary);
        data.put("components", List.of(
                Map.of("component", "scheduler-rest-api", "status", "ok", "message", "running"),
                Map.of("component", "rmf-core", "status", "ok", "message", "builtin kernel"),
                Map.of("component", "fleet-adapter", "status", "ok", "message", "builtin")));
        return data;
    }

    private Map<String, Object> newFleetAggregate(String fleetName, String sceneCode) {
        Map<String, Object> fleet = new LinkedHashMap<>();
        fleet.put("fleet_name", fleetName);
        fleet.put("scene_code", sceneCode);
        fleet.put("rmf_level", null);
        fleet.put("bundle_version", null);
        fleet.put("robot_total", 0);
        fleet.put("online_count", 0);
        fleet.put("available_count", 0);
        fleet.put("idle_count", 0);
        fleet.put("running_count", 0);
        fleet.put("charging_count", 0);
        fleet.put("error_count", 0);
        fleet.put("queued_task_count", 0);
        fleet.put("running_task_count", 0);
        fleet.put("last_heartbeat", 0L);
        fleet.put("adapter_status", "unknown");
        fleet.put("rmf_core_status", "unknown");
        return fleet;
    }

    private void incrementModeCount(Map<String, Object> fleet, String mode) {
        String key = switch (mode == null ? "unknown" : mode) {
            case "idle" -> "idle_count";
            case "running" -> "running_count";
            case "charging" -> "charging_count";
            case "error", "unknown" -> "error_count";
            default -> null;
        };
        if (key != null) {
            fleet.put(key, (Integer) fleet.get(key) + 1);
        }
    }

    private Map<String, Object> emptyRobotSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", 0);
        summary.put("busy", 0);
        summary.put("idle", 0);
        summary.put("stale", 0);
        summary.put("busy_robots", List.of());
        return summary;
    }

    private Map<String, Object> emptyTaskSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("pending", 0);
        summary.put("queued", 0);
        summary.put("running", 0);
        summary.put("other", 0);
        summary.put("truncated", false);
        summary.put("pending_tasks", List.of());
        return summary;
    }

    private void validateEndAction(String endAction) {
        if (endAction != null && !endAction.isBlank() && !VALID_END_ACTIONS.contains(endAction)) {
            throw SchedulerHttpException.badRequest(
                    "end_action must be one of " + VALID_END_ACTIONS + ", got '" + endAction + "'");
        }
    }

    private void validateEarliestStartTime(String earliestStartTime) {
        if (earliestStartTime == null || earliestStartTime.isBlank()) {
            return;
        }
        try {
            Long.parseLong(earliestStartTime);
        } catch (NumberFormatException e) {
            throw SchedulerHttpException.badRequest(
                    "earliest_start_time must be unix milliseconds in numeric string, got '"
                            + earliestStartTime + "'");
        }
    }

    private void validateFleetGroupConsistency(String fleetName, String robotGroup) {
        if (fleetName != null && !fleetName.isBlank()
                && robotGroup != null && !robotGroup.isBlank()
                && !fleetName.equals(robotGroup)) {
            throw SchedulerHttpException.badRequest(
                    "fleet_name and robot_group must match when both are provided: fleet_name ["
                            + fleetName + "] robot_group [" + robotGroup + "]");
        }
    }

    private void validateReturnHome(String robotCode, String navEndPoint, Map<String, Object> parameters) {
        Map<String, Object> params = parameters == null ? Map.of() : parameters;
        String homeWaypoint = str(params.get("home_waypoint"));
        String legacyHomeWaypoint = str(params.get("homeWaypoint"));
        if (homeWaypoint != null && legacyHomeWaypoint != null && !homeWaypoint.equals(legacyHomeWaypoint)) {
            throw SchedulerHttpException.conflict("return_home home_waypoint mismatch: parameters.home_waypoint ["
                    + homeWaypoint + "] does not match parameters.homeWaypoint [" + legacyHomeWaypoint + "]");
        }
        String requestedHome = homeWaypoint != null ? homeWaypoint : legacyHomeWaypoint;
        if (requestedHome != null && navEndPoint != null && !navEndPoint.isBlank()
                && !requestedHome.equals(navEndPoint)) {
            throw SchedulerHttpException.conflict("return_home home_waypoint mismatch for robot ["
                    + robotCode + "]: request [" + requestedHome + "], nav_end_point [" + navEndPoint + "]");
        }
        if (robotCode == null || robotCode.isBlank()) {
            throw SchedulerHttpException.badRequest("robot_code is required for return_home");
        }
        if ((requestedHome == null || requestedHome.isBlank())
                && (navEndPoint == null || navEndPoint.isBlank())) {
            throw SchedulerHttpException.badRequest(
                    "parameters.home_waypoint or nav_end_point is required for return_home");
        }
    }

    /** 目标车队组无任何机器人时快速失败（对齐 assignCharger 的 no charger is available 409 语义）。 */
    private void requireFleetRobots(String fleetName, String robotGroup) {
        String filter = fleetName != null && !fleetName.isBlank() ? fleetName : robotGroup;
        String fleetKey = filter == null || filter.isBlank() ? null : filter;
        if (fleetStateMirror.listByFleet(fleetKey).isEmpty()) {
            throw SchedulerHttpException.conflict(fleetKey == null
                    ? "no robot is visible to the scheduler; please check RMS fleet inventory "
                            + "and robot group assignment (group_no)"
                    : "no robot is registered in fleet group [" + fleetKey + "]; please check RMS "
                            + "robot group assignment (group_no) for this fleet");
        }
    }

    private TaskStatus resolveMirrorStatus(TaskStateEvent event) {
        TaskStatus status = TaskStatus.fromRaw(event.status());
        if (!status.isTerminal() && taskStateMirror.isStale(event)) {
            return TaskStatus.STALE;
        }
        return status;
    }

    private String resolveTaskMessage(TaskStateEvent event, TaskStatus status) {
        if (status == TaskStatus.STALE) {
            return "task state update timeout";
        }
        return null;
    }

    private Map<String, Object> createDispatchRecord(String category, String taskflowId, String taskId,
                                                     String taskTemplateCode, String robotCode,
                                                     String navStartPoint, String navEndPoint,
                                                     Map<String, Object> parameters,
                                                     String robotGroup, String fleetName) {
        // 自动分配准入检查（fail-fast）：车队组一台机器人都没注册属配置问题，秒级不会自愈，
        // 直接拒绝并提示检查 RMS，避免 60s 排队傻等；有机器人但忙碌/离线不拦截（走排队语义）
        if (robotCode == null || robotCode.isBlank()) {
            requireFleetRobots(fleetName, robotGroup);
        }
        long now = System.currentTimeMillis();
        TaskRecord record = new TaskRecord();
        record.setSchedulerRequestId(UUID.randomUUID().toString());
        record.setBusinessTaskId(taskId);
        record.setCategory(category);
        record.setRobotCode(robotCode);
        record.setDispatchStatus(DispatchStatus.PUBLISHED);
        record.setMessage("任务已受理，正在等待调度方确认");
        // 原始请求报文镜像（对外契约字段名，任务列表"请求报文"列直接展示）
        Map<String, Object> requestPayload = new java.util.LinkedHashMap<>();
        requestPayload.put("task_id", taskId);
        if (taskflowId != null && !taskflowId.isBlank()) {
            requestPayload.put("taskflow_id", taskflowId);
        }
        if (taskTemplateCode != null && !taskTemplateCode.isBlank()) {
            requestPayload.put("task_template_code", taskTemplateCode);
        }
        requestPayload.put("task_template_type", category);
        if (navStartPoint != null && !navStartPoint.isBlank()) {
            requestPayload.put("nav_start_point", navStartPoint);
        }
        if (navEndPoint != null && !navEndPoint.isBlank()) {
            requestPayload.put("nav_end_point", navEndPoint);
        }
        if (robotCode != null && !robotCode.isBlank()) {
            requestPayload.put("robot_code", robotCode);
        }
        if (robotGroup != null && !robotGroup.isBlank()) {
            requestPayload.put("robot_group", robotGroup);
        }
        if (fleetName != null && !fleetName.isBlank()) {
            requestPayload.put("fleet_name", fleetName);
        }
        if (parameters != null && !parameters.isEmpty()) {
            requestPayload.put("parameters", parameters);
        }
        record.setPayload(requestPayload);
        record.setCreatedAtMillis(now);
        record.setUpdatedAtMillis(now);
        taskRecordRepository.save(record);
        // 提交内核执行：指定机器人直接执行；无 robot_code 走自动分配（等待可用机器人）
        if (robotCode != null && !robotCode.isBlank()) {
            TaskGoal goal = buildGoal(category, taskId, taskTemplateCode, navStartPoint, navEndPoint, taskflowId,
                    parameters, robotCode);
            executionEngine.submit(record, robotCode, goal);
        } else {
            TaskGoal goal = buildGoal(category, taskId, taskTemplateCode, navStartPoint, navEndPoint, taskflowId,
                    parameters, null);
            executionEngine.submitForAutoAssign(record, goal, fleetName, robotGroup);
        }
        return buildDispatchData(record);
    }

    /**
     * 任务类别 → 执行目标：
     * 纯导航（go_to_place/return_home）、导航+动作（quality_inspection/charge/swap_battery）、
     * 纯动作（pause_task/resume_task）、立即完成（cache_task_context）。
     */
    private TaskGoal buildGoal(String category, String businessTaskId, String taskTemplateCode,
                               String navStartPoint, String navEndPoint,
                               String taskflowId, Map<String, Object> parameters, String robotCode) {
        Map<String, Object> params = new java.util.HashMap<>(parameters == null ? Map.of() : parameters);
        // RMS 要求命令 description 携带 task_id（body_task_id），缺失会被拒绝
        params.putIfAbsent("task_id", businessTaskId);
        // 顶层 task_template_code 注入透传（机器人侧 start-activity 按模板 code 查模板，非固定类型）
        if (taskTemplateCode != null && !taskTemplateCode.isBlank()) {
            params.putIfAbsent("task_template_code", taskTemplateCode);
        }
        // 质检 label 契约：taskflow_id|task_template_code（顶层 taskflow_id 注入透传）
        if (taskflowId != null && !taskflowId.isBlank()) {
            params.putIfAbsent("taskflow_id", taskflowId);
        }
        String goalWaypoint = null;
        String action = null;
        String label = null;
        switch (category) {
            case TaskCategories.GO_TO_PLACE -> goalWaypoint = navEndPoint;
            case TaskCategories.RETURN_HOME -> goalWaypoint = str(params.get("home_waypoint")) != null
                    ? str(params.get("home_waypoint")) : navEndPoint;
            case TaskCategories.QUALITY_INSPECTION -> {
                goalWaypoint = navEndPoint;
                action = TaskCategories.QUALITY_INSPECTION;
                label = buildInspectionLabel(params);
            }
            case TaskCategories.CHARGE -> {
                // 任务流充电契约：目标充电位在 nav_start_point（历史命名）；兼容 charger_waypoint 参数
                // 与 nav_end_point。目标不可解析（机器人侧 SLAM 目标名如 Goal_uX0Ef）→ 充电位分配器自动选桩
                goalWaypoint = str(params.get("charger_waypoint")) != null
                        ? str(params.get("charger_waypoint"))
                        : (navStartPoint != null && !navStartPoint.isBlank() ? navStartPoint : navEndPoint);
                if (!isWaypointResolvable(goalWaypoint) && robotCode != null && !robotCode.isBlank()) {
                    String allocated = chargerPool.allocate(robotCode, null, null, null)
                            .map(ChargerInfo::getRmfWaypoint)
                            .orElse(null);
                    if (allocated != null) {
                        log.info("充电目标不可解析，已自动分配充电位: robot={} 原目标={} 分配={}",
                                robotCode, goalWaypoint, allocated);
                        goalWaypoint = allocated;
                        params.put("charger_waypoint", allocated);
                    }
                }
                action = TaskCategories.CHARGE;
                label = str(params.get("taskflow_id"));
            }
            case TaskCategories.SWAP_BATTERY -> {
                goalWaypoint = navEndPoint;
                action = TaskCategories.SWAP_BATTERY;
                label = str(params.get("taskflow_id"));
            }
            case TaskCategories.PAUSE_TASK, TaskCategories.RESUME_TASK -> {
                action = category;
                label = str(params.get("taskflow_id"));
            }
            default -> {
                // cache_task_context 等无导航无动作：立即完成
            }
        }
        // 机器人本体契约：charge/return_home 等行为模板需要 parameters.destination（目标坐标对象）
        if (action != null && goalWaypoint != null) {
            injectDestination(params, goalWaypoint);
        }
        // 停靠等待点（parameters.end_point / endAction）：质检做完让出作业点，导航至停靠点等待
        String standby = str(params.get("end_point"));
        if (standby == null) {
            standby = str(params.get("endPoint"));
        }
        return new TaskGoal(category, null, goalWaypoint, action, label, standby, params);
    }

    /** 目标点在当前地图可解析（id 或 name；无图视为不可解析）。 */
    private boolean isWaypointResolvable(String waypointId) {
        if (waypointId == null || waypointId.isBlank()) {
            return false;
        }
        return mapManager.activeGraph()
                .filter(graph -> graph.isLoaded())
                .flatMap(graph -> graph.waypointByIdOrName(mapManager.activeFloorId().orElse(null), waypointId))
                .isPresent();
    }

    /**
     * 任务类别目标点类型约束（受理即拒 fail-fast，不入队不傻等）：
     * 质检目标必须是作业点(workstation)，充电目标必须是充电位(charge)。
     * 充电目标不可解析（空/机器人侧 SLAM 目标名）不在此拦截——执行引擎自动选桩兜底。
     */
    private void validateTargetPointType(String category, String navStartPoint, String navEndPoint,
                                         Map<String, Object> parameters) {
        com.zioneer.robotscheduler.core.map.WaypointType required = switch (category) {
            case TaskCategories.QUALITY_INSPECTION -> com.zioneer.robotscheduler.core.map.WaypointType.WORKSTATION;
            case TaskCategories.CHARGE -> com.zioneer.robotscheduler.core.map.WaypointType.CHARGE;
            default -> null;
        };
        if (required == null) {
            return;
        }
        String target = navEndPoint;
        if (TaskCategories.CHARGE.equals(category)) {
            String chargerWaypoint = parameters == null ? null : str(parameters.get("charger_waypoint"));
            target = chargerWaypoint != null ? chargerWaypoint
                    : (navStartPoint != null && !navStartPoint.isBlank() ? navStartPoint : navEndPoint);
        }
        if (target == null || target.isBlank()) {
            return;
        }
        var graph = mapManager.activeGraph().filter(g -> g.isLoaded()).orElse(null);
        if (graph == null) {
            return;
        }
        var waypoint = graph.waypointByIdOrName(mapManager.activeFloorId().orElse(null), target);
        if (waypoint.isEmpty()) {
            if (TaskCategories.CHARGE.equals(category)) {
                return;
            }
            throw SchedulerHttpException.badRequest("质检目标点不存在或不是作业点: " + target
                    + "（质检目标必须为 workstation 类型点位，请检查地图点位配置）");
        }
        if (waypoint.get().getType() != required) {
            String requiredZh = required == com.zioneer.robotscheduler.core.map.WaypointType.WORKSTATION
                    ? "作业点(workstation)" : "充电位(charge)";
            String actual = waypoint.get().getType() == null ? "未知"
                    : waypoint.get().getType().wireName();
            throw SchedulerHttpException.badRequest("目标点类型不匹配: 任务类别 " + category
                    + " 要求" + requiredZh + "，点 " + target + " 实际类型为 " + actual);
        }
    }

    /** 按点位 id/name 注入 destination 坐标对象（地图已加载且点位存在时）。 */
    private void injectDestination(Map<String, Object> params, String waypointId) {
        mapManager.activeGraph().ifPresent(graph ->
                graph.waypointByIdOrName(mapManager.activeFloorId().orElse(null), waypointId)
                        .ifPresent(waypoint -> params.put("destination", Map.of(
                                "x", waypoint.getX(),
                                "y", waypoint.getY(),
                                "yaw", waypoint.getTheta()))));
    }

    private String buildInspectionLabel(Map<String, Object> params) {
        String taskflowId = str(params.get("taskflow_id"));
        String templateCode = str(params.get("task_template_code"));
        if (taskflowId != null && templateCode != null) {
            return taskflowId + "|" + templateCode;
        }
        return taskflowId != null ? taskflowId : templateCode;
    }

    private Optional<TaskRecord> locateRecord(String taskId, String rmfTaskId, String robotCode) {
        if (taskId != null && !taskId.isBlank()) {
            return taskRecordRepository.findByBusinessTaskId(taskId);
        }
        if (rmfTaskId != null && !rmfTaskId.isBlank()) {
            return taskRecordRepository.findByRmfTaskId(rmfTaskId);
        }
        if (robotCode != null && !robotCode.isBlank()) {
            // 按 robot_code 定位：取最近若干条记录，跳过镜像中已终态的任务（含已取消完成的旧任务）
            for (TaskRecord candidate : taskRecordRepository.findRecentByRobotCode(robotCode, 5)) {
                String kernelTaskId = candidate.getRmfTaskId();
                if (kernelTaskId == null) {
                    // 从未受理：返回让 stopTask 走 409 语义
                    return Optional.of(candidate);
                }
                boolean terminal = taskStateMirror.find(kernelTaskId)
                        .map(event -> TaskStatus.fromRaw(event.status()).isTerminal())
                        .orElse(false);
                if (!terminal) {
                    return Optional.of(candidate);
                }
            }
            return Optional.empty();
        }
        throw SchedulerHttpException.badRequest("one of task_id / rmf_task_id / robot_code is required");
    }

    /** dispatch data 结构（对齐 build_dispatch_task_data）。 */
    private Map<String, Object> buildDispatchData(TaskRecord record) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scheduler_request_id", record.getSchedulerRequestId());
        data.put("business_task_id", record.getBusinessTaskId());
        data.put("rmf_task_id", record.getRmfTaskId());
        data.put("task_status", "queued");
        data.put("dispatch_status", record.getDispatchStatus().wireName());
        data.put("rmf_response_received", record.getDispatchStatus() != DispatchStatus.PUBLISHED);
        data.put("task_state_received", false);
        data.put("execution_final", false);
        data.put("message", record.getMessage());
        data.put("created_at", record.getCreatedAtMillis());
        return data;
    }

    private Map<String, Object> buildStatusResponse(TaskRecord record) {
        // abandoned 记录（超龄放弃）在查询时呈现为终态 failed（旧栈：abandoned 行不再出现在活跃列表）
        if (record.isAbandoned()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("task_id", record.getBusinessTaskId());
            data.put("business_task_id", record.getBusinessTaskId());
            data.put("scheduler_request_id", record.getSchedulerRequestId());
            data.put("rmf_task_id", record.getRmfTaskId());
            data.put("task_status", TaskStatus.FAILED.wireName());
            data.put("dispatch_status", record.getDispatchStatus().wireName());
            data.put("rmf_response_received", record.getDispatchStatus() != DispatchStatus.PUBLISHED);
            data.put("task_state_received", false);
            data.put("execution_final", true);
            data.put("message", "任务长时间未收到调度确认，已放弃");
            data.put("raw", Map.of());
            data.put("updated_at", record.getUpdatedAtMillis());
            return data;
        }
        Optional<TaskStateEvent> state = record.getRmfTaskId() == null
                ? Optional.empty()
                : taskStateMirror.find(record.getRmfTaskId());
        TaskStatus status;
        String message = record.getMessage();
        if (state.isPresent()) {
            status = TaskStatus.fromRaw(state.get().status());
        } else if (record.getDispatchStatus() == DispatchStatus.CANCEL_REQUESTED) {
            status = TaskStatus.CANCEL_REQUESTED;
        } else if (record.getDispatchStatus() == DispatchStatus.PUBLISHED && record.getRmfTaskId() == null
                && System.currentTimeMillis() - record.getCreatedAtMillis()
                > (long) (SchedulerConstants.DEFAULT_RESPONSE_TIMEOUT_SECONDS * 1000)) {
            status = TaskStatus.FAILED;
            message = "调度方长时间未确认任务，已标记失败";
        } else {
            status = TaskStatus.QUEUED;
        }
        boolean taskStateReceived = state.isPresent();
        boolean executionFinal = status.isTerminal();
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("task_state", state.map(this::toTaskStateJson).orElse(null));
        raw.put("rmf_response", record.getResponse());
        raw.put("cancel_response", null);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task_id", record.getBusinessTaskId());
        data.put("business_task_id", record.getBusinessTaskId());
        data.put("scheduler_request_id", record.getSchedulerRequestId());
        data.put("rmf_task_id", record.getRmfTaskId());
        data.put("robot_code", record.getRobotCode());
        // 分配透明化：自动分配任务的评分明细（ETA/电量惩罚/总分，最新事件 detail 携带）
        state.map(event -> event.detail() == null ? null : event.detail().get("assignment_trace"))
                .ifPresent(trace -> data.put("assignment_trace", trace));
        state.map(TaskStateEvent::message)
                .filter(msg -> msg != null && msg.contains("自动分配给"))
                .ifPresent(msg -> data.put("assignment_summary", msg));
        data.put("task_status", status.wireName());
        data.put("dispatch_status", record.getDispatchStatus().wireName());
        data.put("rmf_response_received", record.getDispatchStatus() != DispatchStatus.PUBLISHED);
        data.put("task_state_received", taskStateReceived);
        data.put("execution_final", executionFinal);
        data.put("message", message);
        data.put("raw", raw);
        data.put("updated_at", record.getUpdatedAtMillis());
        return data;
    }

    private Map<String, Object> buildStopResponse(TaskRecord record) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task_id", record.getBusinessTaskId());
        data.put("robot_code", record.getRobotCode());
        data.put("scheduler_request_id", record.getSchedulerRequestId());
        data.put("rmf_task_id", record.getRmfTaskId());
        data.put("stop_request_id", record.getCancelRequest() == null ? null
                : record.getCancelRequest().get("stop_request_id"));
        data.put("task_status", "cancel_requested");
        data.put("dispatch_status", record.getDispatchStatus().wireName());
        data.put("rmf_response_received", record.getDispatchStatus() != DispatchStatus.PUBLISHED);
        data.put("task_state_received", false);
        data.put("execution_final", false);
        data.put("message", record.getMessage());
        return data;
    }

    private Map<String, Object> toTaskStateJson(TaskStateEvent event) {
        Map<String, Object> json = new LinkedHashMap<>();
        Map<String, Object> booking = new LinkedHashMap<>();
        booking.put("id", event.taskId());
        json.put("booking", booking);
        json.put("status", event.status());
        json.put("category", event.category());
        if (event.detail() != null && !event.detail().isEmpty()) {
            json.put("detail", event.detail());
        }
        if (event.cancellation() != null) {
            json.put("cancellation", event.cancellation());
        }
        if (event.assignedRobotName() != null || event.assignedFleet() != null) {
            Map<String, Object> assignedTo = new LinkedHashMap<>();
            assignedTo.put("name", event.assignedRobotName());
            assignedTo.put("group", event.assignedFleet());
            json.put("assigned_to", assignedTo);
        }
        json.put("updated_at", event.emittedAtMillis());
        return json;
    }

    private String str(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }
}
