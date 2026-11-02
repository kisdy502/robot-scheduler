package com.zioneer.robotscheduler.core.map;

import com.zioneer.robotscheduler.core.routing.NavigationGraph;
import java.time.Instant;

/** 地图运行快照。元数据与图发布后只读；一次引用替换同时切换全部版本信息。 */
public record MapSnapshot(String mapName, String version, long generation,
                          MapMetadata metadata, NavigationGraph graph, Instant loadedAt,
                          String pgmPath, String yamlPath) {
    public String defaultFloorId() {
        return metadata.getFloors().get(0).getFloorId();
    }
}
