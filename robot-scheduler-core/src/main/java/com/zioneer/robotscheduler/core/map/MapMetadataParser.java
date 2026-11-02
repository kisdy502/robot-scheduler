package com.zioneer.robotscheduler.core.map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * 地图元数据 json 解析器：解析 + 结构校验（published 状态、点位 ID 唯一、路径引用完整）。
 *
 * <p>内部使用独立 ObjectMapper（默认 camelCase）：地图编辑器 json 为 camelCase，
 * 不能复用 Spring 全局 snake_case 配置的 mapper（会被命名策略污染导致字段解析为空）。</p>
 */
@Component
public class MapMetadataParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 解析并校验地图 json。
     *
     * @throws MapMetadataException 结构非法或校验失败
     */
    public MapMetadata parse(String jsonContent) {
        MapMetadata metadata;
        try {
            metadata = objectMapper.readValue(jsonContent, MapMetadata.class);
        } catch (IOException e) {
            throw new MapMetadataException("地图 json 解析失败: " + e.getMessage(), e);
        }
        validate(metadata);
        return metadata;
    }

    private void validate(MapMetadata metadata) {
        if (metadata.getVersionInfo() == null || isBlank(metadata.getVersionInfo().getVersion())) {
            throw new MapMetadataException("versionInfo.version is required");
        }
        if (isBlank(metadata.getMapName())) {
            throw new MapMetadataException("mapInfo.mapName is required");
        }
        if (metadata.getFloors().isEmpty()) {
            throw new MapMetadataException("at least one floor is required");
        }
        Set<String> floorIds = new HashSet<>();
        for (FloorMetadata floor : metadata.getFloors()) {
            if (isBlank(floor.getFloorId()) || !floorIds.add(floor.getFloorId())) {
                throw new MapMetadataException("floorId is required and must be unique: " + floor.getFloorId());
            }
            validateFloor(floor);
        }
    }

    private void validateFloor(FloorMetadata floor) {
        requireSpeed(floor.getDefaultMaxSpeed(), false, "floor " + floor.getFloorId());
        for (ZoneMetadata zone : floor.getZoneConstraints()) {
            if (zone.getMaxSpeed() != null) {
                requireSpeed(zone.getMaxSpeed(), true, "zone " + zone.getId());
            }
        }
        Set<String> waypointIds = new HashSet<>();
        for (WaypointMetadata waypoint : floor.getWaypoints()) {
            if (isBlank(waypoint.getId()) || !waypointIds.add(waypoint.getId())) {
                throw new MapMetadataException("waypoint id is required and must be unique in floor "
                        + floor.getFloorId() + ": " + waypoint.getId());
            }
            if (waypoint.getType() == null) {
                throw new MapMetadataException("waypoint type is required: " + waypoint.getId());
            }
        }
        for (PathMetadata path : floor.getPaths()) {
            if (path.getMaxSpeed() != null) {
                requireSpeed(path.getMaxSpeed(), false, "path " + path.getId());
            }
            if (path.getWaypoints() == null || path.getWaypoints().size() < 2) {
                throw new MapMetadataException("path waypoints must contain at least 2 entries: " + path.getId());
            }
            for (String waypointId : path.getWaypoints()) {
                if (!waypointIds.contains(waypointId)) {
                    throw new MapMetadataException("path " + path.getId()
                            + " references unknown waypoint: " + waypointId);
                }
            }
        }
    }

    private void requireSpeed(double speed, boolean allowZero, String resource) {
        if (!Double.isFinite(speed) || speed < 0 || (!allowZero && speed == 0)) {
            throw new MapMetadataException("invalid maxSpeed for " + resource + ": " + speed);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
