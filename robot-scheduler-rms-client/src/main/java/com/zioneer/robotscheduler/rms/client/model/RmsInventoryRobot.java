package com.zioneer.robotscheduler.rms.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * RMS 机器人清单条目（/fleets/{fleet}/inventory；RMS 字段 snake_case，position 为嵌套对象）。
 */
@Getter
@Setter
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class RmsInventoryRobot {

    private String robotCode;
    private String robotName;
    private String displayName;
    private String mapName;

    private Double x;
    private Double y;
    private Double yaw;

    private Double battery;
    private String onlineStatus;
    private List<String> compatibleChargers = List.of();
    private String homeWaypoint;
    private Boolean decommissioned;

    @JsonSetter("position")
    public void setPosition(RmsRobotStatus.Position position) {
        if (position != null) {
            this.x = position.getX();
            this.y = position.getY();
            this.yaw = position.getYaw();
        }
    }
}
