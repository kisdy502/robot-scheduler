package com.zioneer.robotscheduler.api.controller;

import com.zioneer.robotscheduler.api.dto.ExternalOperationRequest;
import com.zioneer.robotscheduler.api.envelope.ApiEnvelope;
import com.zioneer.robotscheduler.core.dispatch.SchedulerFacade;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

/** 上层业务系统的状态通知入口，不向 RMS 下发业务动作。 */
@RestController
@RequestMapping("/api/v1/scheduler")
@RequiredArgsConstructor
public class RobotUseController {
    private final SchedulerFacade schedulerFacade;

    @PostMapping("/external-operations/start")
    @Operation(summary = "开始外部作业，阻止导航和自动返充；需先取得使用权",
            description = "持久化成功才确认；存储不可用返回 503，应保留原 operation_id 重试")
    public ApiEnvelope<Map<String, Object>> start(@Valid @RequestBody ExternalOperationRequest request) {
        return ApiEnvelope.ok(schedulerFacade.startExternalOperation(request.getTaskflowId(),
                request.getRobotCode(), request.getOperationId()));
    }

    @PostMapping("/external-operations/finish")
    @Operation(summary = "确认外部作业结束，恢复持有者导航资格；保留使用权和物理驻留",
            description = "写入失败时继续保留外部作业占用；存储不可用返回 503，应保留原 operation_id 重试")
    public ApiEnvelope<Map<String, Object>> finish(@Valid @RequestBody ExternalOperationRequest request) {
        return ApiEnvelope.ok(schedulerFacade.finishExternalOperation(request.getTaskflowId(),
                request.getRobotCode(), request.getOperationId()));
    }

    @GetMapping("/robot-use")
    @Operation(summary = "查询机器人使用权与外部作业状态")
    public ApiEnvelope<Map<String, Object>> status(@RequestParam("robot_code") String robot) {
        return ApiEnvelope.ok(schedulerFacade.robotUse(robot));
    }
}
