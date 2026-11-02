package com.zioneer.robotscheduler.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * POST /api/v1/scheduler/assign-charger 请求体。
 */
@Getter
@Setter
@Schema(name = "AssignChargerRequest", description = "充电桩分配请求")
public class AssignChargerRequest {

    @NotBlank
    @Schema(description = "机器人编码", requiredMode = Schema.RequiredMode.REQUIRED)
    private String robotCode;

    @Schema(description = "候选充电桩 waypoint 列表（空则自动分配）")
    private List<String> chargerWaypointList;

    @Schema(description = "资源类型：charger / swap_station / 空=不限")
    private String resourceType;
}
