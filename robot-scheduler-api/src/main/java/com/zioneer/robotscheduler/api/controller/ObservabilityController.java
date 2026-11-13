package com.zioneer.robotscheduler.api.controller;

import com.zioneer.robotscheduler.common.core.result.PageResult;
import com.zioneer.robotscheduler.common.core.result.Result;
import com.zioneer.robotscheduler.core.charger.ChargerPoolPort;
import com.zioneer.robotscheduler.core.traffic.ActiveRouteRegistry;
import com.zioneer.robotscheduler.core.traffic.TrafficRegistry;
import com.zioneer.robotscheduler.core.repository.TaskStateRecord;
import com.zioneer.robotscheduler.core.repository.TaskStateRecordRepository;
import com.zioneer.robotscheduler.persistence.repository.DbTrafficEventSink;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 调度观测接口（内部管理：任务历史/交通审计/充电桩/占用快照，Result 信封分页）。
 */
@Tag(name = "调度观测")
@RestController
@RequestMapping("/api/v1/observability")
@RequiredArgsConstructor
public class ObservabilityController {

    private final TaskStateRecordRepository taskStateRecordRepository;
    private final com.zioneer.robotscheduler.core.repository.TaskRecordRepository taskRecordRepository;
    private final DbTrafficEventSink trafficEventSink;
    private final ChargerPoolPort chargerPool;
    private final TrafficRegistry trafficRegistry;
    private final ActiveRouteRegistry routeRegistry;
    private final com.zioneer.robotscheduler.core.repository.RobotEventRepository robotEventRepository;
    private final com.zioneer.robotscheduler.core.repository.ExternalActivityRepository externalActivityRepository;

    @Operation(summary = "任务历史分页查询（含失败原因 message；重启不丢）")
    @GetMapping("/tasks")
    public Result<PageResult<Map<String, Object>>> taskHistory(
            @Parameter(description = "机器人编码") @RequestParam(name = "robot_code", required = false) String robotCode,
            @Parameter(description = "状态：queued/underway/completed/failed/canceled")
            @RequestParam(name = "status", required = false) String status,
            @Parameter(description = "类别：go_to_place/quality_inspection/charge…")
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "page_num", required = false, defaultValue = "1") int pageNum,
            @RequestParam(name = "page_size", required = false, defaultValue = "20") int pageSize) {
        int safePage = Math.max(1, pageNum);
        int safeSize = Math.min(Math.max(1, pageSize), 200);
        long total = taskStateRecordRepository.count(robotCode, status, category);
        List<Map<String, Object>> list = taskStateRecordRepository
                .findPage(robotCode, status, category, (safePage - 1) * safeSize, safeSize)
                .stream().map(this::toRow).toList();
        enrichRequestPayload(list);
        return Result.ok(PageResult.of(list, total, safePage, safeSize));
    }

    @Operation(summary = "交通事件审计分页（占用/释放记录）")
    @GetMapping("/traffic/events")
    public Result<PageResult<Map<String, Object>>> trafficEvents(
            @Parameter(description = "机器人编码") @RequestParam(name = "robot_code", required = false) String robotCode,
            @Parameter(description = "ACQUIRE/RELEASE") @RequestParam(name = "action", required = false) String action,
            @RequestParam(name = "page_num", required = false, defaultValue = "1") int pageNum,
            @RequestParam(name = "page_size", required = false, defaultValue = "20") int pageSize) {
        int safePage = Math.max(1, pageNum);
        int safeSize = Math.min(Math.max(1, pageSize), 200);
        long total = trafficEventSink.count(robotCode, action);
        List<Map<String, Object>> list = trafficEventSink.findPage(robotCode, action, safePage, safeSize)
                .stream().map(entity -> {
                    Map<String, Object> row = new LinkedHashMap<String, Object>();
                    row.put("id", String.valueOf(entity.getId()));
                    row.put("robot_code", entity.getRobotCode());
                    row.put("action", entity.getAction());
                    row.put("resource_id", entity.getResourceId());
                    row.put("detail", entity.getDetail());
                    row.put("created_at", entity.getCreatedAt() == null ? null
                            : entity.getCreatedAt().toEpochMilli());
                    return row;
                }).toList();
        return Result.ok(PageResult.of(list, total, safePage, safeSize));
    }

    @Operation(summary = "交通占用实时快照（当前谁占哪些资源）")
    @GetMapping("/traffic/occupation")
    public Result<Map<String, Object>> occupation() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("occupied_segments", trafficRegistry.occupiedSegments());
        data.put("occupied_waypoints", trafficRegistry.occupiedWaypoints());
        data.put("by_robot", trafficRegistry.occupationSnapshot());
        return Result.ok(data);
    }

    @Operation(summary = "活动路线快照（各机器人当前任务的规划途经点序列，可视化数据源）")
    @GetMapping("/routes")
    public Result<List<Map<String, Object>>> activeRoutes() {
        List<Map<String, Object>> list = routeRegistry.snapshot().stream().map(route -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("robot_code", route.robotCode());
            row.put("task_id", route.taskId());
            row.put("floor_id", route.floorId());
            row.put("progress", route.progressIndex());
            row.put("total", route.waypoints().size());
            List<Map<String, Object>> waypoints = new ArrayList<>(route.waypoints().size());
            for (var wp : route.waypoints()) {
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("id", wp.id());
                point.put("x", wp.x());
                point.put("y", wp.y());
                waypoints.add(point);
            }
            row.put("waypoints", waypoints);
            row.put("updated_at", route.updatedAtMillis());
            return row;
        }).toList();
        return Result.ok(list);
    }

    @Operation(summary = "机器人事件日志（状态/电量/上下线，K8s 式聚合；WARN/ERROR 里程碑独立成行）")
    @GetMapping("/robot-events")
    public Result<PageResult<Map<String, Object>>> robotEvents(
            @Parameter(description = "机器人编码") @RequestParam(name = "robot_code", required = false) String robotCode,
            @Parameter(description = "事件类型：MODE/BATTERY/ONLINE") @RequestParam(name = "event_type", required = false) String eventType,
            @Parameter(description = "级别：INFO/WARN/ERROR") @RequestParam(name = "severity", required = false) String severity,
            @RequestParam(name = "page_num", required = false, defaultValue = "1") int pageNum,
            @RequestParam(name = "page_size", required = false, defaultValue = "20") int pageSize) {
        int safePage = Math.max(1, pageNum);
        int safeSize = Math.min(Math.max(1, pageSize), 200);
        long total = robotEventRepository.count(robotCode, eventType, severity);
        List<Map<String, Object>> list = robotEventRepository
                .findPage(robotCode, eventType, severity, (safePage - 1) * safeSize, safeSize)
                .stream().map(event -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", String.valueOf(event.getId()));
                    row.put("robot_code", event.getRobotCode());
                    row.put("event_type", event.getEventType());
                    row.put("reason", event.getReason());
                    row.put("severity", event.getSeverity());
                    row.put("old_value", event.getOldValue());
                    row.put("new_value", event.getNewValue());
                    row.put("message", event.getMessage());
                    row.put("count", event.getEventCount());
                    row.put("first_at", event.getFirstAtMillis());
                    row.put("last_at", event.getLastAtMillis());
                    return row;
                }).toList();
        return Result.ok(PageResult.of(list, total, safePage, safeSize));
    }

    @Operation(summary = "外部动作回执镜像（上层应用直连 RMS 的质检/夹爪等动作结果，按 body_task_id 与移动任务聚合）")
    @GetMapping("/activities")
    public Result<PageResult<Map<String, Object>>> externalActivities(
            @Parameter(description = "机器人编码") @RequestParam(name = "robot_code", required = false) String robotCode,
            @Parameter(description = "业务任务 ID（task_id）") @RequestParam(name = "task_id", required = false) String taskId,
            @RequestParam(name = "page_num", required = false, defaultValue = "1") int pageNum,
            @RequestParam(name = "page_size", required = false, defaultValue = "50") int pageSize) {
        int safePage = Math.max(1, pageNum);
        int safeSize = Math.min(Math.max(1, pageSize), 200);
        long total = externalActivityRepository.count(robotCode, taskId);
        List<Map<String, Object>> list = externalActivityRepository
                .findPage(robotCode, taskId, (safePage - 1) * safeSize, safeSize)
                .stream().map(activity -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", String.valueOf(activity.getId()));
                    row.put("robot_code", activity.getRobotCode());
                    row.put("body_task_id", activity.getBodyTaskId());
                    row.put("action_type", activity.getActionType());
                    row.put("action_status", activity.getActionStatus());
                    row.put("result_report", activity.getResultReport());
                    row.put("failure_msg", activity.getFailureMessage());
                    row.put("robot_reported_ms", activity.getRobotReportedMillis());
                    row.put("created_at", activity.getCreatedAtMillis());
                    return row;
                }).toList();
        return Result.ok(PageResult.of(list, total, safePage, safeSize));
    }

    @Operation(summary = "充电桩列表（含占用状态）")
    @GetMapping("/chargers")
    public Result<List<Map<String, Object>>> chargers(
            @Parameter(description = "车队过滤") @RequestParam(name = "fleet_name", required = false) String fleetName) {
        List<Map<String, Object>> list = chargerPool.list(fleetName).stream().map(charger -> {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("charger_id", charger.getChargerId());
            row.put("name", charger.getName());
            row.put("waypoint", charger.getRmfWaypoint());
            row.put("rmf_level", charger.getRmfLevel());
            row.put("resource_type", charger.getResourceType());
            row.put("status", charger.getStatus());
            row.put("occupied_by", charger.getOccupiedBy());
            row.put("enabled", charger.isEnabled());
            row.put("updated_at", charger.getUpdatedAtMillis());
            return row;
        }).toList();
        return Result.ok(list);
    }

    /** 批量补请求报文（t_task_record.payload → 行.request_payload，一次 in 查询避免 N+1）。 */
    private void enrichRequestPayload(List<Map<String, Object>> rows) {
        List<String> businessIds = rows.stream()
                .map(row -> (String) row.get("business_task_id"))
                .filter(id -> id != null && !id.isBlank())
                .distinct().toList();
        if (businessIds.isEmpty()) {
            return;
        }
        Map<String, Object> payloadByBusinessId = new java.util.HashMap<>();
        for (com.zioneer.robotscheduler.core.repository.TaskRecord record
                : taskRecordRepository.findByBusinessTaskIds(businessIds)) {
            if (record.getPayload() != null && !record.getPayload().isEmpty()) {
                payloadByBusinessId.put(record.getBusinessTaskId(), record.getPayload());
            }
        }
        for (Map<String, Object> row : rows) {
            Object payload = payloadByBusinessId.get(row.get("business_task_id"));
            if (payload != null) {
                row.put("request_payload", payload);
            }
        }
    }

    private Map<String, Object> toRow(TaskStateRecord record) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("rmf_task_id", record.getRmfTaskId());
        row.put("business_task_id", record.getBusinessTaskId());
        row.put("robot_code", record.getRobotCode());
        row.put("category", record.getCategory());
        row.put("status", record.getStatus());
        row.put("message", record.getMessage());
        row.put("cancellation", record.getCancellation());
        // 质检结果与动作回执（业务观测核心字段）
        row.put("inspection_result", record.getInspectionResult());
        if (record.getDetail() != null && record.getDetail().get("action_receipt") != null) {
            row.put("action_receipt", record.getDetail().get("action_receipt"));
        }
        Map<String, Object> detail = record.getDetail();
        row.put("detail", detail == null || detail.size() <= 8 ? detail
                : new LinkedHashMap<>(detail));
        row.put("updated_at", record.getUpdatedAtMillis());
        return row;
    }
}
