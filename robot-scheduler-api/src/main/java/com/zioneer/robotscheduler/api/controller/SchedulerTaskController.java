package com.zioneer.robotscheduler.api.controller;

import com.zioneer.robotscheduler.api.dto.AssignTaskRequest;
import com.zioneer.robotscheduler.api.dto.DispatchTaskRequest;
import com.zioneer.robotscheduler.api.dto.GotoPlaceRequest;
import com.zioneer.robotscheduler.api.dto.StopTaskRequest;
import com.zioneer.robotscheduler.api.envelope.ApiEnvelope;
import com.zioneer.robotscheduler.core.dispatch.SchedulerFacade;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 调度任务端点（8 个，路径与字段与旧 API 保持一致）。
 */
@Tag(name = "调度任务")
@RestController
@RequestMapping("/api/v1/scheduler")
@RequiredArgsConstructor
public class SchedulerTaskController {

    private final SchedulerFacade schedulerFacade;

    @Operation(summary = "记录任务流占用的机器人")
    @PostMapping("/assign-task")
    public ApiEnvelope<Map<String, Object>> assignTask(@Valid @RequestBody AssignTaskRequest request) {
        Map<String, Object> data = schedulerFacade.assignTask(
                String.valueOf(request.getTaskflowId()),
                request.getTaskTemplateTypeList(),
                request.getRobotCode(),
                request.getRobotType(),
                request.getRobotGroup(),
                request.getNavPointStart(),
                request.getPriority());
        return ApiEnvelope.ok(data);
    }

    @Operation(summary = "点到点移动（发布后异步执行）")
    @PostMapping("/goto-place")
    public ApiEnvelope<Map<String, Object>> gotoPlace(@Valid @RequestBody GotoPlaceRequest request) {
        Map<String, Object> data = schedulerFacade.gotoPlace(
                String.valueOf(request.getTaskflowId()),
                String.valueOf(request.getTaskId()),
                request.getRobotCode(),
                request.getNavTargetPoint(),
                request.getRobotGroup(),
                request.getTaskTemplateId(),
                request.getTaskTemplateCode(),
                request.getEarliestStartTime(),
                request.getParameters());
        return ApiEnvelope.published(data);
    }

    @Operation(summary = "通用任务下发（推荐入口）")
    @PostMapping("/dispatch_task")
    public ApiEnvelope<Map<String, Object>> dispatchTask(@Valid @RequestBody DispatchTaskRequest request) {
        Map<String, Object> data = schedulerFacade.dispatchTask(
                request.getTaskflowId(),
                request.getTaskId(),
                request.getRobotCode(),
                request.getFleetName(),
                request.getRobotGroup(),
                request.getTaskTemplateId(),
                request.getTaskTemplateCode(),
                request.getTaskTemplateType(),
                request.getNavStartPoint(),
                request.getNavEndPoint(),
                request.getEndAction(),
                request.getEarliestStartTime(),
                request.getParameters());
        return ApiEnvelope.published(data);
    }

    @Operation(summary = "取消任务（task_id/rmf_task_id/robot_code 三选一）")
    @PostMapping("/stop-task")
    public ApiEnvelope<Map<String, Object>> stopTask(@RequestBody StopTaskRequest request) {
        return ApiEnvelope.ok(schedulerFacade.stopTask(
                request.getTaskId(), request.getRmfTaskId(), request.getRobotCode(), request.getReason()));
    }

    @Operation(summary = "任务列表查询")
    @GetMapping("/rmf/tasks")
    public ApiEnvelope<Map<String, Object>> listTasks(
            @Parameter(description = "机器人编码") @RequestParam(name = "robot_code", required = false) String robotCode,
            @Parameter(description = "是否包含归档终态任务") @RequestParam(name = "execution_final", required = false, defaultValue = "false") boolean executionFinal,
            @Parameter(description = "按归一化状态过滤") @RequestParam(name = "status", required = false) String status,
            @Parameter(description = "返回上限（默认 100，最大 1000）") @RequestParam(name = "limit", required = false, defaultValue = "100") int limit,
            @Parameter(description = "是否包含 raw.task_state") @RequestParam(name = "verbose", required = false, defaultValue = "true") boolean verbose) {
        return ApiEnvelope.ok(schedulerFacade.listTasks(robotCode, executionFinal, status, limit, verbose));
    }

    @Operation(summary = "按业务任务 ID 查询状态")
    @GetMapping("/tasks/{task_id}/status")
    public ApiEnvelope<Map<String, Object>> taskStatus(@PathVariable("task_id") String taskId) {
        return ApiEnvelope.ok(schedulerFacade.queryTaskStatus(taskId));
    }

    @Operation(summary = "按调度请求 ID 查询状态（推荐）")
    @GetMapping("/tasks/status")
    public ApiEnvelope<Map<String, Object>> taskStatusByRequestId(
            @Parameter(description = "调度请求 ID") @RequestParam(name = "scheduler_request_id") String schedulerRequestId) {
        return ApiEnvelope.ok(schedulerFacade.queryTaskStatusByRequestId(schedulerRequestId));
    }

    @Operation(summary = "切换地图预检")
    @GetMapping("/pre-switch-check")
    public ApiEnvelope<Map<String, Object>> preSwitchCheck(
            @Parameter(description = "目标场景编码") @RequestParam(name = "scene_code", required = false) String sceneCode,
            @Parameter(description = "只检查该车队") @RequestParam(name = "fleet_name", required = false) String fleetName) {
        return ApiEnvelope.ok(schedulerFacade.preSwitchCheck(sceneCode, fleetName));
    }
}
