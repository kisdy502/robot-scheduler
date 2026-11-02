package com.zioneer.robotscheduler.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/**
 * POST /api/v1/scheduler/release-charger 请求体（robot_code 与 charger_waypoint 至少其一）。
 */
@Getter
@Setter
@Schema(name = "ReleaseChargerRequest", description = "充电桩释放请求")
public class ReleaseChargerRequest {

    @Schema(description = "机器人编码")
    private String robotCode;

    @Schema(description = "充电桩 waypoint")
    private String chargerWaypoint;

    @Schema(description = "资源类型")
    private String resourceType;
}
