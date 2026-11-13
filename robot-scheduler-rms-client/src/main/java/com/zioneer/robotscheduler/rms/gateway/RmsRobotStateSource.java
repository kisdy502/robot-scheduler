package com.zioneer.robotscheduler.rms.gateway;

import com.zioneer.robotscheduler.core.fleet.RobotSnapshot;
import com.zioneer.robotscheduler.core.fleet.RobotStateSource;
import com.zioneer.robotscheduler.core.fleet.RobotStateSourceException;
import com.zioneer.robotscheduler.rms.client.RmsUnavailableException;
import com.zioneer.robotscheduler.rms.client.RobotManagementClient;
import com.zioneer.robotscheduler.rms.client.model.RmsInventoryRobot;
import com.zioneer.robotscheduler.rms.client.model.RmsRobotStatus;
import com.zioneer.robotscheduler.rms.config.RobotManagementProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * RobotStateSource 端口的 RMS 适配：RMS 响应 → RobotSnapshot（坐标/电量/楼层原样透传，mode 由 FleetManager 推导）。
 */
@Slf4j
@RequiredArgsConstructor
public class RmsRobotStateSource implements RobotStateSource {

    private final RobotManagementClient client;
    private final RobotManagementProperties properties;

    /** 名称映射缓存（真实名称变化频率极低；避免 1Hz 状态轮询每秒附带 DB 分页查询）。 */
    private volatile java.util.Map<String, String> nameMapCache = java.util.Map.of();
    private volatile long nameMapFetchedAtMillis = 0;
    private static final long NAME_MAP_TTL_MILLIS = 30_000;

    /** SLAM 地图标识缓存（current_map_code：三方地图一致性校验输入，30s 缓存同名称映射）。 */
    private volatile java.util.Map<String, String> mapCodeCache = java.util.Map.of();
    private volatile long mapCodeFetchedAtMillis = 0;

    /** 机器人档案缓存（地图标识 + 本体充电标记，管理列表接口）。 */
    private volatile java.util.Map<String, com.zioneer.robotscheduler.rms.client.RobotManagementClient.RobotProfile> profileCache =
            java.util.Map.of();
    private volatile long profileFetchedAtMillis = 0;

    @Override
    public List<RobotSnapshot> fetchStates() {
        try {
            List<RmsRobotStatus> statuses = client.fetchStatus(properties.getFleetName(), null);
            // fleet status 的 robot_name 等于编码，从管理列表获取真实名称（30s 缓存，失败静默用旧值）
            java.util.Map<String, String> nameMap = freshNameMap();
            java.util.Map<String, com.zioneer.robotscheduler.rms.client.RobotManagementClient.RobotProfile> profiles = freshProfiles();
            return statuses.stream().map(s -> {
                RobotSnapshot snapshot = toSnapshot(s);
                String realName = nameMap.get(snapshot.getRobotCode());
                if (realName != null && !realName.isBlank()) {
                    snapshot.setRobotName(realName);
                }
                applyProfiles(snapshot, profiles);
                return snapshot;
            }).toList();
        } catch (RmsUnavailableException e) {
            throw new RobotStateSourceException("RMS status unavailable: " + e.getMessage(), e);
        }
    }

    /** 名称映射：TTL 内直接用缓存；过期时刷新，失败时回退旧缓存（名称不丢）。 */
    private java.util.Map<String, String> freshNameMap() {
        long now = System.currentTimeMillis();
        if (now - nameMapFetchedAtMillis < NAME_MAP_TTL_MILLIS) {
            return nameMapCache;
        }
        try {
            java.util.Map<String, String> fetched = client.fetchRobotNameMap();
            if (fetched != null && !fetched.isEmpty()) {
                nameMapCache = fetched;
                nameMapFetchedAtMillis = now;
            }
        } catch (Exception e) {
            // 刷新失败：沿用旧缓存，下轮再试
        }
        return nameMapCache;
    }

    /** SLAM 地图标识：TTL 缓存（与名称映射同源接口，错峰刷新），失败沿用旧值。 */
    private java.util.Map<String, String> freshMapCodeMap() {
        long now = System.currentTimeMillis();
        if (now - mapCodeFetchedAtMillis < NAME_MAP_TTL_MILLIS) {
            return mapCodeCache;
        }
        try {
            java.util.Map<String, String> fetched = client.fetchRobotMapCodeMap();
            if (fetched != null && !fetched.isEmpty()) {
                mapCodeCache = fetched;
                mapCodeFetchedAtMillis = now;
            }
        } catch (Exception e) {
            // 刷新失败：沿用旧缓存
        }
        return mapCodeCache;
    }

    /** 机器人档案（地图标识 + 本体充电标记）：TTL 缓存，失败沿用旧值。 */
    private java.util.Map<String, com.zioneer.robotscheduler.rms.client.RobotManagementClient.RobotProfile> freshProfiles() {
        long now = System.currentTimeMillis();
        if (now - profileFetchedAtMillis < NAME_MAP_TTL_MILLIS) {
            return profileCache;
        }
        try {
            java.util.Map<String, com.zioneer.robotscheduler.rms.client.RobotManagementClient.RobotProfile> fetched =
                    client.fetchRobotProfiles();
            if (fetched != null && !fetched.isEmpty()) {
                profileCache = fetched;
                profileFetchedAtMillis = now;
            }
        } catch (Exception e) {
            // 刷新失败：沿用旧缓存
        }
        return profileCache;
    }

    private void applyMapCode(RobotSnapshot snapshot, java.util.Map<String, String> mapCodeMap) {
        String mapCode = mapCodeMap.get(snapshot.getRobotCode());
        if (mapCode != null && !mapCode.isBlank()) {
            snapshot.setSlamMapCode(mapCode);
        }
    }

    private void applyProfiles(RobotSnapshot snapshot,
                               java.util.Map<String, com.zioneer.robotscheduler.rms.client.RobotManagementClient.RobotProfile> profiles) {
        var profile = profiles.get(snapshot.getRobotCode());
        if (profile == null) {
            return;
        }
        if (profile.mapCode() != null && !profile.mapCode().isBlank()) {
            snapshot.setSlamMapCode(profile.mapCode());
        }
        if (profile.bodyCharging() != null) {
            snapshot.setBodyCharging(profile.bodyCharging());
        }
    }

    @Override
    public List<RobotSnapshot> fetchInventory() {
        try {
            List<RmsInventoryRobot> inventory = client.fetchInventory(properties.getFleetName());
            java.util.Map<String, com.zioneer.robotscheduler.rms.client.RobotManagementClient.RobotProfile> profiles = freshProfiles();
            return inventory.stream().map(r -> {
                RobotSnapshot snapshot = toSnapshot(r);
                applyProfiles(snapshot, profiles);
                return snapshot;
            }).toList();
        } catch (RmsUnavailableException e) {
            throw new RobotStateSourceException("RMS inventory unavailable: " + e.getMessage(), e);
        }
    }

    private RobotSnapshot toSnapshot(RmsRobotStatus status) {
        RobotSnapshot snapshot = new RobotSnapshot();
        fillCommon(snapshot, status.getRobotCode(), status.getRobotName());
        snapshot.setPositionValid(status.getX() != null && status.getY() != null
                && Double.isFinite(status.getX()) && Double.isFinite(status.getY()));
        if (status.getX() != null) {
            snapshot.setX(status.getX());
        }
        if (status.getY() != null) {
            snapshot.setY(status.getY());
        }
        if (status.getYaw() != null) {
            snapshot.setYaw(status.getYaw());
        }
        snapshot.setBattery(status.getBattery());
        snapshot.setMap(status.getMapName());
        snapshot.setDecommissioned(status.getDecommissioned());
        snapshot.setRmsOffline("offline".equalsIgnoreCase(status.getOnlineStatus()));
        snapshot.setReplanRequested(Boolean.TRUE.equals(status.getReplan()));
        snapshot.setCompletedCommands(status.getCompletedCommands());
        return snapshot;
    }

    private RobotSnapshot toSnapshot(RmsInventoryRobot inventory) {
        RobotSnapshot snapshot = new RobotSnapshot();
        fillCommon(snapshot, inventory.getRobotCode(), inventory.getRobotName());
        if (inventory.getX() != null) {
            snapshot.setX(inventory.getX());
        }
        if (inventory.getY() != null) {
            snapshot.setY(inventory.getY());
        }
        if (inventory.getYaw() != null) {
            snapshot.setYaw(inventory.getYaw());
        }
        snapshot.setBattery(inventory.getBattery());
        snapshot.setMap(inventory.getMapName());
        snapshot.setDecommissioned(inventory.getDecommissioned());
        snapshot.setRmsOffline("offline".equalsIgnoreCase(inventory.getOnlineStatus()));
        snapshot.setCompatibleChargers(inventory.getCompatibleChargers());
        snapshot.setHomeWaypoint(inventory.getHomeWaypoint());
        return snapshot;
    }

    private void fillCommon(RobotSnapshot snapshot, String robotCode, String robotName) {
        snapshot.setRobotCode(robotCode);
        snapshot.setRobotName(robotName == null ? robotCode : robotName);
        snapshot.setFleetName(properties.getFleetName());
    }
}
