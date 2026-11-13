package com.zioneer.robotscheduler.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * POST /api/v1/scheduler/goto-place 请求体。
 */
@Getter
@Setter
@Schema(name = "GotoPlaceRequest", description = "点到点移动请求")
public class GotoPlaceRequest {

    @NotNull
    @Schema(description = "Taskflow id（字符串形式，兼容旧整型 ID）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String taskflowId;

    @NotNull
    @Schema(description = "业务任务 id（字符串形式，兼容旧整型 ID；parameters.idempotent=true 时作为幂等键）",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String taskId;

    @Schema(description = "机器人编码；为空时由调度内核自动分配")
    private String robotCode;

    @NotBlank
    @Schema(description = "目标点位（waypoint 名或索引）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String navTargetPoint;

    @Schema(description = "机器人分组")
    private String robotGroup;

    @Schema(description = "任务模板 id")
    private String taskTemplateId;

    @Schema(description = "任务模板编码")
    private String taskTemplateCode;

    @Schema(description = "最早开始时间（unix 毫秒字符串）")
    private String earliestStartTime;

    @Schema(description = "附加参数：orientation/prefer_same_map/start_at_departure 等；"
            + "idempotent=true 时相同任务 id 的相同请求不重复执行，改变请求返回 409")
    private Map<String, Object> parameters = Map.of();
}
