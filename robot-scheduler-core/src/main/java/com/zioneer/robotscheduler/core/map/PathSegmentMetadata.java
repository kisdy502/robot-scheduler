package com.zioneer.robotscheduler.core.map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

/**
 * 路径段几何信息（地图编辑器扩展）：CURVE 段带三次贝塞尔控制点，backUp/reverse 影响机器人执行方式。
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class PathSegmentMetadata {

    /** 段序号。 */
    private int seq;

    /** 段起点 waypoint id。 */
    private String from;

    /** 段终点 waypoint id。 */
    private String to;

    /** 段类型：LINE / CURVE。 */
    private String segmentType;

    /** CURVE 段的三次贝塞尔控制点 [[x,y],[x,y]]（与起终点共 4 点）。 */
    private double[][] controlPoints;

    /** 是否倒车段。 */
    private boolean backUp;

    /** 是否逆向段。 */
    private boolean reverse;

    public boolean isCurve() {
        return "CURVE".equalsIgnoreCase(segmentType) && controlPoints != null && controlPoints.length >= 2;
    }
}
