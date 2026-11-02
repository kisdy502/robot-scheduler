package com.zioneer.robotscheduler.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * POST /api/v1/scheduler/dispatch_task 请求体。
 */
@Getter
@Setter
@Schema(name = "DispatchTaskRequest", description = "通用任务下发请求（推荐入口）")
public class DispatchTaskRequest {

    @Schema(description = "Taskflow id")
    private String taskflowId;

    @NotBlank
    @Schema(description = "业务任务 id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String taskId;

    @Schema(description = "机器人编码；为空时自动分配")
    private String robotCode;

    @Schema(description = "车队名")
    private String fleetName;

    @Schema(description = "机器人分组（与 fleet_name 同传时必须一致）")
    private String robotGroup;

    @Schema(description = "任务模板 id")
    private String taskTemplateId;

    @Schema(description = "任务模板编码")
    private String taskTemplateCode;

    @NotBlank
    @Schema(description = "任务类型（go_to_place/quality_inspection/charge/swap_battery/return_home/pause_task/resume_task 等）",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String taskTemplateType;

    @Schema(description = "起点点位")
    private String navStartPoint;

    @Schema(description = "终点点位")
    private String navEndPoint;

    @Schema(description = "收尾动作：standby（默认）/ return_home / return_idle_zone")
    private String endAction = "standby";

    @Schema(description = "最早开始时间（unix 毫秒字符串）")
    private String earliestStartTime;

    @Schema(description = "附加参数")
    private Map<String, Object> parameters = Map.of();
}
