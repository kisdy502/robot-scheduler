package com.zioneer.robotscheduler.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * POST /api/v1/scheduler/release-robot 请求体。
 */
@Getter
@Setter
@Schema(name = "ReleaseRobotRequest", description = "释放任务流占用的机器人")
public class ReleaseRobotRequest {

    @NotBlank
    @Schema(description = "Taskflow id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String taskflowId;

    @Schema(description = "机器人编码；为空则释放整个 taskflow 的占用")
    private String robotCode;
}
