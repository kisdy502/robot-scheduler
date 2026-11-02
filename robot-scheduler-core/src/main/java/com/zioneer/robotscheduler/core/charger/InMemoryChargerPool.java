package com.zioneer.robotscheduler.core.charger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 充电桩池内存实现（对齐 charger_pool_manager 的 allocate/release/list 语义；P5 由 DB+Redis 实现替换）。
 *
 * <p>分配规则：优先候选 waypoint 列表中的可用桩；候选为空或全忙时按兼容性自动兜底（第一个可用桩）。</p>
 */
public class InMemoryChargerPool implements ChargerPoolPort {

    /** waypoint → 桩信息。 */
    private final Map<String, ChargerInfo> chargersByWaypoint = new ConcurrentHashMap<>();

    public InMemoryChargerPool() {
    }

    public InMemoryChargerPool(List<ChargerInfo> initial) {
        initial.forEach(this::register);
    }

    /** 注册/更新桩（场景资源加载入口）。 */
    public void register(ChargerInfo charger) {
        charger.setUpdatedAtMillis(System.currentTimeMillis());
        chargersByWaypoint.put(charger.getRmfWaypoint(), charger);
    }

    @Override
    public Optional<ChargerInfo> allocate(String robotCode, String fleetName,
                                          List<String> chargerWaypointList, String resourceType) {
        List<String> candidates = chargerWaypointList == null || chargerWaypointList.isEmpty()
                ? List.copyOf(chargersByWaypoint.keySet())
                : chargerWaypointList;
        for (String waypoint : candidates) {
            ChargerInfo charger = chargersByWaypoint.get(waypoint);
            if (available(charger, resourceType)) {
                charger.setStatus(1);
                charger.setOccupiedBy(robotCode);
                charger.setUpdatedAtMillis(System.currentTimeMillis());
                return Optional.of(charger);
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean release(String robotCode, String chargerWaypoint, String resourceType) {
        if (chargerWaypoint != null && !chargerWaypoint.isBlank()) {
            ChargerInfo charger = chargersByWaypoint.get(chargerWaypoint);
            if (charger != null) {
                free(charger);
                return true;
            }
            return false;
        }
        if (robotCode == null || robotCode.isBlank()) {
            return false;
        }
        List<ChargerInfo> released = chargersByWaypoint.values().stream()
                .filter(charger -> robotCode.equals(charger.getOccupiedBy())
                        || robotCode.equals(charger.getReservedBy()))
                .toList();
        released.forEach(this::free);
        return !released.isEmpty();
    }

    @Override
    public boolean occupy(String robotCode, String chargerWaypoint) {
        if (robotCode == null || robotCode.isBlank() || chargerWaypoint == null || chargerWaypoint.isBlank()) {
            return false;
        }
        ChargerInfo charger = chargersByWaypoint.get(chargerWaypoint);
        if (charger == null) {
            return false;
        }
        String current = charger.getOccupiedBy();
        if (current != null && !current.equals(robotCode)) {
            // 已被其他机器人占用：不覆盖（可能是任务分配的占用者，驻留者只在显示上保持交通侧真相）
            return false;
        }
        charger.setStatus(1);
        charger.setOccupiedBy(robotCode);
        charger.setUpdatedAtMillis(System.currentTimeMillis());
        return true;
    }

    @Override
    public List<ChargerInfo> list(String fleetName) {
        return new ArrayList<>(chargersByWaypoint.values());
    }

    private boolean available(ChargerInfo charger, String resourceType) {
        if (charger == null || !charger.isEnabled()) {
            return false;
        }
        if (resourceType != null && !resourceType.isBlank()
                && !resourceType.equals(charger.getResourceType())) {
            return false;
        }
        return charger.getStatus() == 0;
    }

    private void free(ChargerInfo charger) {
        charger.setStatus(0);
        charger.setOccupiedBy(null);
        charger.setReservedBy(null);
        charger.setUpdatedAtMillis(System.currentTimeMillis());
    }
}
