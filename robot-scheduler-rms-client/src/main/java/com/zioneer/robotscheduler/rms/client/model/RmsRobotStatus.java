package com.zioneer.robotscheduler.rms.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * RMS 机器人状态帧（/fleets/{fleet}/status 单机器人条目；RMS 字段 snake_case，position 为嵌套对象）。
 */
@Getter
@Setter
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class RmsRobotStatus {

    private String robotCode;
    private String robotName;
    private String mapName;

    private Double x;
    private Double y;
    private Double yaw;

    /** 电量 0-100。 */
    private Double battery;

    private String onlineStatus;

    /** RMS 请求重规划。 */
    private Boolean replan;

    private String homeWaypoint;

    private List<String> compatibleChargers = List.of();

    /** 是否已停用（decommission）。 */
    private Boolean decommissioned;

    private Long lastCompletedRequest;

    /** 完成命令回执列表。 */
    private List<Map<String, Object>> completedCommands = List.of();

    /** RMS position 嵌套对象 {x, y, yaw} → 平铺字段。 */
    @JsonSetter("position")
    public void setPosition(Position position) {
        if (position != null) {
            this.x = position.x;
            this.y = position.y;
            this.yaw = position.yaw;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Position {

        private Double x;
        private Double y;
        private Double yaw;

        public Double getX() {
            return x;
        }

        public void setX(Double x) {
            this.x = x;
        }

        public Double getY() {
            return y;
        }

        public void setY(Double y) {
            this.y = y;
        }

        public Double getYaw() {
            return yaw;
        }

        public void setYaw(Double yaw) {
            this.yaw = yaw;
        }
    }
}
