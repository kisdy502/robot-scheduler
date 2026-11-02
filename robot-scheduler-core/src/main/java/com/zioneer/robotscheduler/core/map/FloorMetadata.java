package com.zioneer.robotscheduler.core.map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 楼层（本期调度按单层执行，楼层维度为跨层调度向后兼容保留）。
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class FloorMetadata {

    /** 楼层标识，如 "F1"。 */
    private String floorId;

    private String name;

    /** 楼层基准高度（米）。 */
    private double elevation;

    /** 楼层默认速度（m/s）。 */
    private double defaultMaxSpeed;

    private List<WaypointMetadata> waypoints = List.of();

    private List<ZoneMetadata> zoneConstraints = List.of();

    private List<ShelfMetadata> shelves = List.of();

    private List<PathMetadata> paths = List.of();
}
