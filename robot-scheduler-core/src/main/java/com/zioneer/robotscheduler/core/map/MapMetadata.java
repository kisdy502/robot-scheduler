package com.zioneer.robotscheduler.core.map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 地图元数据（单 json 全量地图描述，结构对齐《地图编辑json文件格式》：
 * 根 = versionInfo + mapInfo 两段）。
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class MapMetadata {

    private VersionInfo versionInfo;

    private MapInfo mapInfo;

    // ==== 委托方法（屏蔽嵌套层级，简化调用方） ====

    public String getMapName() {
        return mapInfo == null ? null : mapInfo.getMapName();
    }

    public void setMapName(String mapName) {
        mapInfo().setMapName(mapName);
    }

    public String getCoordinateSystem() {
        return mapInfo == null ? null : mapInfo.getCoordinateSystem();
    }

    public void setCoordinateSystem(String coordinateSystem) {
        mapInfo().setCoordinateSystem(coordinateSystem);
    }

    public String getUnit() {
        return mapInfo == null ? null : mapInfo.getUnit();
    }

    public void setUnit(String unit) {
        mapInfo().setUnit(unit);
    }

    public List<FloorMetadata> getFloors() {
        return mapInfo == null ? List.of() : mapInfo.getFloors();
    }

    public void setFloors(List<FloorMetadata> floors) {
        mapInfo().setFloors(floors);
    }

    public List<InterFloorConnection> getInterFloorConnections() {
        return mapInfo == null ? List.of() : mapInfo.getInterFloorConnections();
    }

    public void setInterFloorConnections(List<InterFloorConnection> connections) {
        mapInfo().setInterFloorConnections(connections);
    }

    private MapInfo mapInfo() {
        if (mapInfo == null) {
            mapInfo = new MapInfo();
        }
        return mapInfo;
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VersionInfo {

        /** 语义化版本号，如 v0.1.0。 */
        private String version;

        /** 版本状态；仅 "published" 的版本可被激活。 */
        private String status;

        private String lastModified;

        private String modifiedBy;
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MapInfo {

        private String mapName;

        /** 坐标系类型，固定 "map"（SLAM 地图坐标系）。 */
        private String coordinateSystem;

        /** 坐标单位，固定 "meters"。 */
        private String unit;

        private List<FloorMetadata> floors = List.of();

        private List<InterFloorConnection> interFloorConnections = List.of();
    }
}
