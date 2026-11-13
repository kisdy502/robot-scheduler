package com.zioneer.robotscheduler.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * POST /api/v1/scheduler/force-release-robot 请求体（运维逃生门，reason 必填供审计）。
 */
@Getter
@Setter
@Schema(name = "ForceReleaseRobotRequest", description = "强制释放机器人使用权")
public class ForceReleaseRobotRequest {

    @NotBlank
    @Schema(description = "机器人编码", requiredMode = Schema.RequiredMode.REQUIRED)
    private String robotCode;

    @NotBlank
    @Schema(description = "强制释放原因（审计事件记录）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String reason;
}
