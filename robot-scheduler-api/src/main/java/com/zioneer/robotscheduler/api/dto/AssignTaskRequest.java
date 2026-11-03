package com.zioneer.robotscheduler.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * POST /api/v1/scheduler/assign-task 请求体（字段与旧 API 一致）。
 */
@Getter
@Setter
@Schema(name = "AssignTaskRequest", description = "记录任务流占用的机器人")
public class AssignTaskRequest {

    @NotNull
    @Schema(description = "Taskflow id", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long taskflowId;

    @Schema(description = "任务模板类型列表")
    private List<String> taskTemplateTypeList;

    @Schema(description = "机器人编码（当前必填，自动分配暂不支持）")
    private String robotCode;

    @Schema(description = "机器人类型")
    private String robotType;

    @Schema(description = "机器人分组")
    private String robotGroup;

    @Schema(description = "起点点位")
    private String navPointStart;

    @Schema(description = "优先级")
    private Integer priority;
}
