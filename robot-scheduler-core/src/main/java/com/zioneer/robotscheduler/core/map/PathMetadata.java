package com.zioneer.robotscheduler.core.map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 路径（可含途经点序列）：waypoints 相邻两点构成路径段。
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class PathMetadata {

    private String id;

    /** 起点位 ID。 */
    private String from;

    /** 终点位 ID。 */
    private String to;

    /** 途经点位 ID 序列（含起终点），相邻两点为一段。 */
    private List<String> waypoints;

    /** 是否双向通行（json 字段名 isBidirectional）。 */
    @JsonProperty("isBidirectional")
    private boolean bidirectional = true;

    /** 路径限速（m/s），缺省用楼层默认速度。 */
    private Double maxSpeed;

    /** 是否允许动态避障偏移；false = 严格沿路径行驶（窄通道/精准对接）。 */
    private boolean enableObstacleAvoidance = true;

    /** 段几何细节（可选）：曲线控制点/倒车标志等。 */
    private List<PathSegmentMetadata> segments = List.of();

    /**
     * 查找 waypoints 相邻对对应的段几何（from/to 匹配）。
     */
    public PathSegmentMetadata segmentBetween(String fromId, String toId) {
        for (PathSegmentMetadata segment : segments) {
            if (fromId.equals(segment.getFrom()) && toId.equals(segment.getTo())) {
                return segment;
            }
        }
        return null;
    }
}
