package com.zioneer.robotscheduler.persistence.charger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zioneer.robotscheduler.core.charger.ChargerInfo;
import com.zioneer.robotscheduler.core.charger.ChargerRecordRepository;
import com.zioneer.robotscheduler.core.map.MapManager;
import com.zioneer.robotscheduler.core.map.MapMetadataParser;
import com.zioneer.robotscheduler.core.repository.MapArchiveRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DB 充电桩池测试：启动加载、分配落库、释放、地图对账（新增/保留占用/移除失效）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DbChargerPoolTest {

    private static final String MAP = """
            {
              "versionInfo": {"version": "v1", "status": "published"},
              "mapInfo": {"mapName": "ChargeMap", "floors": [{
                "floorId": "F1", "defaultMaxSpeed": 1.0,
                "waypoints": [
                  {"id": "CHG_A", "name": "充电位A", "position": [0, 0, 0], "type": "charge"},
                  {"id": "PICK_B", "name": "取货位", "position": [5, 0, 0], "type": "pickup"}
                ],
                "paths": []
              }]}
            }
            """;

    @Mock
    private ChargerRecordRepository repository;

    @Mock
    private MapArchiveRepository mapArchiveRepository;

    private MapManager mapManager;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(mapArchiveRepository.findActive()).thenReturn(Optional.empty());
        lenient().when(mapArchiveRepository.findByMapNameAndVersion(anyString(), anyString()))
                .thenReturn(Optional.empty());
        lenient().when(repository.findAll()).thenReturn(List.of());
        mapManager = new MapManager(new MapMetadataParser(), mapArchiveRepository);
    }

    private ChargerInfo charger(String id, String waypoint, int status) {
        ChargerInfo info = new ChargerInfo();
        info.setChargerId(id);
        info.setName(id);
        info.setRmfWaypoint(waypoint);
        info.setResourceType("charger");
        info.setStatus(status);
        info.setEnabled(true);
        return info;
    }

    @Test
    void construction_loadsFromDb() {
        when(repository.findAll()).thenReturn(List.of(charger("CHG_DB", "CHG_DB", 0)));

        DbChargerPool pool = new DbChargerPool(repository, mapManager);

        assertThat(pool.list(null)).hasSize(1);
    }

    @Test
    void allocate_persistsOccupancy() {
        when(repository.findAll()).thenReturn(List.of(charger("CHG_1", "CHG_1", 0)));
        DbChargerPool pool = new DbChargerPool(repository, mapManager);

        Optional<ChargerInfo> allocated = pool.allocate("R001", null, null, null);

        assertThat(allocated).isPresent();
        assertThat(allocated.get().getStatus()).isEqualTo(1);
        verify(repository).updateOccupancy(any(ChargerInfo.class));
    }

    @Test
    void release_persistsFree() {
        when(repository.findAll()).thenReturn(List.of(charger("CHG_1", "CHG_1", 0)));
        DbChargerPool pool = new DbChargerPool(repository, mapManager);
        pool.allocate("R001", null, null, null);

        boolean released = pool.release("R001", null, null);

        assertThat(released).isTrue();
        ArgumentCaptor<ChargerInfo> captor = ArgumentCaptor.forClass(ChargerInfo.class);
        verify(repository, org.mockito.Mockito.times(2)).updateOccupancy(captor.capture());
        assertThat(captor.getAllValues().get(1).getStatus()).isEqualTo(0);
    }

    @Test
    void syncFromActiveMap_upsertsChargeWaypoints() throws Exception {
        mapManager.activateFromJson(MAP, null, null);
        DbChargerPool pool = new DbChargerPool(repository, mapManager);

        pool.syncFromActiveMap();

        // 地图 charge 点 CHG_A 入库；非充电位 PICK_B 不入库
        ArgumentCaptor<ChargerInfo> captor = ArgumentCaptor.forClass(ChargerInfo.class);
        verify(repository).upsert(captor.capture());
        assertThat(captor.getValue().getChargerId()).isEqualTo("CHG_A");
        assertThat(captor.getValue().getSceneCode()).isEqualTo("ChargeMap");
    }

    @Test
    void syncFromActiveMap_removesStaleChargers() throws Exception {
        mapManager.activateFromJson(MAP, null, null);
        when(repository.findAll()).thenReturn(List.of(charger("CHG_STALE", "CHG_STALE", 0)));
        DbChargerPool pool = new DbChargerPool(repository, mapManager);

        pool.syncFromActiveMap();

        verify(repository).deleteByChargerIdsNotIn(anyList());
        // 失效桩被移除，地图上的充电位保留
        assertThat(pool.list(null)).hasSize(1);
        assertThat(pool.list(null).get(0).getChargerId()).isEqualTo("CHG_A");
    }

    @Test
    void syncFromActiveMap_noMap_skips() {
        DbChargerPool pool = new DbChargerPool(repository, mapManager);

        pool.syncFromActiveMap();

        verify(repository, never()).upsert(any(ChargerInfo.class));
    }
}
