package com.zioneer.robotscheduler.api.controller;

import com.zioneer.robotscheduler.api.envelope.ApiEnvelope;
import com.zioneer.robotscheduler.core.dispatch.SchedulerFacade;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 运行态端点（1 个：全量快照）。
 */
@Tag(name = "运行态")
@RestController
@RequestMapping("/api/v1/runtime")
@RequiredArgsConstructor
public class RuntimeController {

    private final SchedulerFacade schedulerFacade;

    @Operation(summary = "车队运行时全量快照（fleets/robots/tasks/chargers/components）")
    @GetMapping("/fleets/snapshot")
    public ApiEnvelope<Map<String, Object>> fleetSnapshot(
            @Parameter(description = "场景编码；与当前场景不一致时返回空记录集")
            @RequestParam(name = "scene_code", required = false) String sceneCode,
            @Parameter(description = "车队名过滤") @RequestParam(name = "fleet_name", required = false) String fleetName,
            @Parameter(description = "楼层过滤") @RequestParam(name = "rmf_level", required = false) String rmfLevel,
            @Parameter(description = "任务列表上限（默认 200，最大 1000）")
            @RequestParam(name = "task_limit", required = false, defaultValue = "200") int taskLimit) {
        return ApiEnvelope.ok(schedulerFacade.runtimeSnapshot(sceneCode, fleetName, rmfLevel, taskLimit));
    }
}
