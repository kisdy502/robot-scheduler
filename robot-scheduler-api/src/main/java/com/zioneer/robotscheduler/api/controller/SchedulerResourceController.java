package com.zioneer.robotscheduler.api.controller;

import com.zioneer.robotscheduler.api.dto.AssignChargerRequest;
import com.zioneer.robotscheduler.api.dto.ReleaseChargerRequest;
import com.zioneer.robotscheduler.api.dto.ReleaseResourcesRequest;
import com.zioneer.robotscheduler.api.dto.ReleaseRobotRequest;
import com.zioneer.robotscheduler.api.dto.ReloadSceneResourcesRequest;
import com.zioneer.robotscheduler.api.envelope.ApiEnvelope;
import com.zioneer.robotscheduler.core.dispatch.SchedulerFacade;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 资源端点（8 个：机器人占用/充电桩/场景资源）。
 */
@Tag(name = "调度资源")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class SchedulerResourceController {

    private final SchedulerFacade schedulerFacade;

    @Operation(summary = "释放任务流占用的机器人")
    @PostMapping("/scheduler/release-robot")
    public ApiEnvelope<Map<String, Object>> releaseRobot(@Valid @RequestBody ReleaseRobotRequest request) {
        return ApiEnvelope.ok(schedulerFacade.releaseRobot(request.getTaskflowId(), request.getRobotCode()));
    }

    @Operation(summary = "使用权心跳（租约续期：长外部作业期间上层定期调用，防孤儿回收误杀）")
    @PostMapping("/scheduler/robot-use/heartbeat")
    public ApiEnvelope<Map<String, Object>> ownershipHeartbeat(
            @RequestBody Map<String, String> request) {
        return ApiEnvelope.ok(schedulerFacade.ownershipHeartbeat(request.get("taskflow_id")));
    }

    @Operation(summary = "强制释放机器人使用权（运维逃生门：有活动任务 409；审计事件 FORCE_RELEASED）")
    @PostMapping("/scheduler/force-release-robot")
    public ApiEnvelope<Map<String, Object>> forceReleaseRobot(
            @Valid @RequestBody com.zioneer.robotscheduler.api.dto.ForceReleaseRobotRequest request) {
        return ApiEnvelope.ok(schedulerFacade.forceReleaseRobot(request.getRobotCode(), request.getReason()));
    }

    @Operation(summary = "分配充电桩")
    @PostMapping("/scheduler/assign-charger")
    public ApiEnvelope<Map<String, Object>> assignCharger(@Valid @RequestBody AssignChargerRequest request) {
        return ApiEnvelope.ok(schedulerFacade.assignCharger(
                request.getRobotCode(), request.getChargerWaypointList(), request.getResourceType()));
    }

    @Operation(summary = "释放充电桩")
    @PostMapping("/scheduler/release-charger")
    public ApiEnvelope<Map<String, Object>> releaseCharger(@Valid @RequestBody ReleaseChargerRequest request) {
        return ApiEnvelope.ok(schedulerFacade.releaseCharger(
                request.getRobotCode(), request.getChargerWaypoint(), request.getResourceType()));
    }

    @Operation(summary = "机器人资源占用查询")
    @GetMapping("/scheduler/robot-resources")
    public ApiEnvelope<Map<String, Object>> robotResources(
            @Parameter(description = "机器人编码；为空返回全部") @RequestParam(name = "robot_code", required = false) String robotCode) {
        return ApiEnvelope.ok(schedulerFacade.robotResources(robotCode));
    }

    @Operation(summary = "释放机器人全部资源")
    @PostMapping("/scheduler/release-resources")
    public ApiEnvelope<Map<String, Object>> releaseResources(@Valid @RequestBody ReleaseResourcesRequest request) {
        return ApiEnvelope.ok(schedulerFacade.releaseResources(request.getRobotCode()));
    }

    @Operation(summary = "场景资源加载状态")
    @GetMapping("/scheduler/scene-resources/status")
    public ApiEnvelope<Map<String, Object>> sceneResourceStatus() {
        return ApiEnvelope.ok(schedulerFacade.sceneResourceStatus());
    }

    @Operation(summary = "场景资源重载（当前固定 skipped 响应）")
    @PostMapping("/scheduler/scene-resources/reload")
    public ApiEnvelope<Map<String, Object>> reloadSceneResources(@RequestBody(required = false) ReloadSceneResourcesRequest request) {
        return ApiEnvelope.ok(schedulerFacade.reloadSceneResources(
                request == null ? null : request.getResourceTypes(),
                request == null || request.getApplyRuntime() == null || request.getApplyRuntime()));
    }

    @Operation(summary = "资源总览状态（scene-resources/status + local_path）")
    @GetMapping("/resources/status")
    public ApiEnvelope<Map<String, Object>> resourcesStatus() {
        return ApiEnvelope.ok(schedulerFacade.resourcesStatus());
    }
}
