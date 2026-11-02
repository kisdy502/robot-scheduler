package com.zioneer.robotscheduler.persistence.charger;

import com.zioneer.robotscheduler.core.charger.ChargerInfo;
import com.zioneer.robotscheduler.core.charger.ChargerPoolPort;
import com.zioneer.robotscheduler.core.charger.ChargerRecordRepository;
import com.zioneer.robotscheduler.core.map.MapMetadata;
import com.zioneer.robotscheduler.core.map.WaypointType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DB 充电桩池：t_charger 为正本，内存缓存加速分配（分配/释放同步落库，重启不丢占用状态）。
 *
 * <p>替代 InMemoryChargerPool（多实例场景由 DB+行锁保证原子性，当前单实例内存锁足够）。</p>
 */
@Slf4j
@Component
public class DbChargerPool implements ChargerPoolPort {

    private final ChargerRecordRepository repository;
    private final com.zioneer.robotscheduler.core.map.MapManager mapManager;
    private final Map<String, ChargerInfo> chargersById = new ConcurrentHashMap<>();

    public DbChargerPool(ChargerRecordRepository repository,
                         com.zioneer.robotscheduler.core.map.MapManager mapManager) {
        this.repository = repository;
        this.mapManager = mapManager;
        repository.findAll().forEach(charger -> chargersById.put(charger.getChargerId(), charger));
        log.info("充电桩池已从 DB 加载: count={}", chargersById.size());
    }

    /**
     * 地图对账（30s 周期 + 启动时）：地图 charge 点位 ↔ t_charger 同步
     * （新增 upsert、删除多余；保留动态占用状态）。
     */
    @Scheduled(initialDelay = 5000, fixedDelay = 30_000)
    public void syncFromActiveMap() {
        Optional<MapMetadata> metadata = mapManager.activeMetadata();
        if (metadata.isEmpty()) {
            return;
        }
        String mapName = metadata.get().getMapName();
        List<String> keepIds = new ArrayList<>();
        for (var floor : metadata.get().getFloors()) {
            for (var waypoint : floor.getWaypoints()) {
                if (waypoint.getType() != WaypointType.CHARGE) {
                    continue;
                }
                String chargerId = waypoint.getId();
                keepIds.add(chargerId);
                ChargerInfo existing = chargersById.get(chargerId);
                if (existing == null) {
                    ChargerInfo charger = new ChargerInfo();
                    charger.setChargerId(chargerId);
                    charger.setName(waypoint.getName());
                    charger.setRmfWaypoint(waypoint.getId());
                    charger.setPointCode(waypoint.getName());
                    charger.setRmfLevel(floor.getFloorId());
                    charger.setMapCode(mapName);
                    charger.setSceneCode(mapName);
                    charger.setResourceType("charger");
                    charger.setCompatibleFleets(List.of());
                    charger.setStatus(0);
                    charger.setEnabled(true);
                    repository.upsert(charger);
                    chargersById.put(chargerId, charger);
                } else if (mapName.equals(existing.getSceneCode())) {
                    existing.setRmfLevel(floor.getFloorId());
                    repository.upsert(existing);
                }
            }
        }
        if (!keepIds.isEmpty()) {
            int removed = repository.deleteByChargerIdsNotIn(keepIds);
            boolean memoryCleaned = chargersById.keySet().retainAll(keepIds);
            if (removed > 0 || memoryCleaned) {
                log.info("地图对账移除失效充电桩: dbRemoved={} memoryCleaned={}", removed, memoryCleaned);
            }
        }
    }

    @Override
    public synchronized Optional<ChargerInfo> allocate(String robotCode, String fleetName,
                                                       List<String> chargerWaypointList,
                                                       String resourceType) {
        List<ChargerInfo> candidates = new ArrayList<>();
        if (chargerWaypointList == null || chargerWaypointList.isEmpty()) {
            candidates.addAll(chargersById.values());
        } else {
            for (String waypoint : chargerWaypointList) {
                chargersById.values().stream()
                        .filter(charger -> waypoint.equals(charger.getRmfWaypoint()))
                        .findFirst().ifPresent(candidates::add);
            }
        }
        for (ChargerInfo charger : candidates) {
            if (available(charger, resourceType)) {
                charger.setStatus(1);
                charger.setOccupiedBy(robotCode);
                charger.setUpdatedAtMillis(System.currentTimeMillis());
                repository.updateOccupancy(charger);
                return Optional.of(charger);
            }
        }
        return Optional.empty();
    }

    @Override
    public synchronized boolean release(String robotCode, String chargerWaypoint, String resourceType) {
        List<ChargerInfo> released = new ArrayList<>();
        if (chargerWaypoint != null && !chargerWaypoint.isBlank()) {
            chargersById.values().stream()
                    .filter(charger -> chargerWaypoint.equals(charger.getRmfWaypoint()))
                    .findFirst().ifPresent(released::add);
        } else if (robotCode != null && !robotCode.isBlank()) {
            chargersById.values().stream()
                    .filter(charger -> robotCode.equals(charger.getOccupiedBy())
                            || robotCode.equals(charger.getReservedBy()))
                    .forEach(released::add);
        }
        released.forEach(this::free);
        return !released.isEmpty();
    }

    @Override
    public synchronized boolean occupy(String robotCode, String chargerWaypoint) {
        if (robotCode == null || robotCode.isBlank() || chargerWaypoint == null || chargerWaypoint.isBlank()) {
            return false;
        }
        ChargerInfo charger = chargersById.values().stream()
                .filter(c -> chargerWaypoint.equals(c.getRmfWaypoint()))
                .findFirst().orElse(null);
        if (charger == null) {
            return false;
        }
        String current = charger.getOccupiedBy();
        if (current != null && !current.equals(robotCode)) {
            return false;
        }
        charger.setStatus(1);
        charger.setOccupiedBy(robotCode);
        charger.setUpdatedAtMillis(System.currentTimeMillis());
        repository.updateOccupancy(charger);
        return true;
    }

    @Override
    public List<ChargerInfo> list(String fleetName) {
        return List.copyOf(chargersById.values());
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
        repository.updateOccupancy(charger);
    }
}
