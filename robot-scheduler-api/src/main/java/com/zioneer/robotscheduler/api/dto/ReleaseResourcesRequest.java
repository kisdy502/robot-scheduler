package com.zioneer.robotscheduler.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * POST /api/v1/scheduler/release-resources 请求体。
 */
@Getter
@Setter
@Schema(name = "ReleaseResourcesRequest", description = "释放机器人全部资源")
public class ReleaseResourcesRequest {

    @NotBlank
    @Schema(description = "机器人编码", requiredMode = Schema.RequiredMode.REQUIRED)
    private String robotCode;
}
