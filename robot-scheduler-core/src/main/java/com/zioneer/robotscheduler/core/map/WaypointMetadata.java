package com.zioneer.robotscheduler.core.map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.Getter;
import lombok.Setter;

/**
 * 地图点位（SLAM map 坐标系，米制；json 的 position = [x, y, theta]，theta 为弧度）。
 *
 * <p>设计说明：坐标与机器人上报坐标、SLAM 地图 yaml 的 origin 同一坐标系，
 * 全链路零转换（干掉 RMF 的 transformations 体系）。</p>
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class WaypointMetadata {

    /** 点位唯一标识，如 "WP_001"。 */
    private String id;

    /** 点位名称。 */
    private String name;

    /** x（米）。 */
    private double x;

    /** y（米）。 */
    private double y;

    /** 朝向（弧度）。 */
    private double theta;

    /** 点位类型。 */
    private WaypointType type;

    /** json position 数组 [x, y, theta] → 平铺字段。 */
    @JsonSetter("position")
    public void setPosition(double[] position) {
        if (position != null && position.length >= 2) {
            this.x = position[0];
            this.y = position[1];
            this.theta = position.length >= 3 ? position[2] : 0.0;
        }
    }
}
