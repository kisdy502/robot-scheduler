package com.zioneer.robotscheduler.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * POST /api/v1/scheduler/scene-resources/reload 请求体（当前固定 skipped 响应，保持兼容）。
 */
@Getter
@Setter
@Schema(name = "ReloadSceneResourcesRequest", description = "场景资源重载请求（当前禁用）")
public class ReloadSceneResourcesRequest {

    @Schema(description = "资源类型列表")
    private List<String> resourceTypes;

    @Schema(description = "是否应用运行态", defaultValue = "true")
    private Boolean applyRuntime = Boolean.TRUE;
}
