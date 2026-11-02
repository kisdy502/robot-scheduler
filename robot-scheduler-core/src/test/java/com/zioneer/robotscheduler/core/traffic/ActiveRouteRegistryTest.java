package com.zioneer.robotscheduler.core.traffic;

import com.zioneer.robotscheduler.core.map.MapManager;
import com.zioneer.robotscheduler.core.map.MapMetadataParser;
import com.zioneer.robotscheduler.core.repository.MapArchiveRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * 活动路线注册表测试：注册坐标解析 / 进度推进单调 / 覆盖与清除语义。
 */
@ExtendWith(MockitoExtension.class)
class ActiveRouteRegistryTest {

    private static final String MAP = """
            {
              "versionInfo": {"version": "v1", "status": "published"},
              "mapInfo": {"mapName": "RouteMap", "floors": [{
                "floorId": "F1", "defaultMaxSpeed": 1.0,
                "waypoints": [
                  {"id": "P1", "name": "p1", "position": [0, 0, 0], "type": "path"},
                  {"id": "P2", "name": "p2", "position": [5, 0, 0], "type": "path"},
                  {"id": "P3", "name": "p3", "position": [10, 0, 0], "type": "path"}
                ],
                "paths": []
              }]}
            }
            """;

    @Mock
    private MapArchiveRepository mapArchiveRepository;

    private ActiveRouteRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(mapArchiveRepository.findActive()).thenReturn(Optional.empty());
        lenient().when(mapArchiveRepository.findByMapNameAndVersion(anyString(), anyString()))
                .thenReturn(Optional.empty());
        MapManager mapManager = new MapManager(new MapMetadataParser(), mapArchiveRepository);
        mapManager.activateFromJson(MAP, null, null);
        registry = new ActiveRouteRegistry(mapManager);
    }

    // 场景：注册活动路线；预期：waypoint ID 解析为地图坐标且进度从 0 开始
    @Test
    void register_resolvesWaypointCoordinates() {
        registry.register("R001", "go_to_place.t1", "F1", List.of("P1", "P2", "P3"));

        var route = registry.snapshot().get(0);
        assertThat(route.robotCode()).isEqualTo("R001");
        assertThat(route.taskId()).isEqualTo("go_to_place.t1");
        assertThat(route.progressIndex()).isZero();
        assertThat(route.waypoints()).hasSize(3);
        assertThat(route.waypoints().get(1).x()).isEqualTo(5.0);
        assertThat(route.waypoints().get(2).y()).isEqualTo(0.0);
    }

    // 场景：按上报点位推进进度（含乱序回退）；预期：进度只前进不后退
    @Test
    void advance_movesProgressForwardMonotonically() {
        registry.register("R001", "go_to_place.t1", "F1", List.of("P1", "P2", "P3"));

        registry.advance("R001", "P2");
        assertThat(registry.snapshot().get(0).progressIndex()).isEqualTo(1);

        // 乱序/回退不倒退进度
        registry.advance("R001", "P1");
        assertThat(registry.snapshot().get(0).progressIndex()).isEqualTo(1);

        registry.advance("R001", "P3");
        assertThat(registry.snapshot().get(0).progressIndex()).isEqualTo(2);
    }

    // 场景：同机器人注册新任务路线后清除；预期：新路线覆盖旧路线，clear 后清空
    @Test
    void register_overridesSameRobot_clearRemoves() {
        registry.register("R001", "go_to_place.t1", "F1", List.of("P1", "P2"));
        registry.register("R001", "quality_inspection.t2", "F1", List.of("P2", "P3"));

        var route = registry.snapshot().get(0);
        assertThat(route.taskId()).isEqualTo("quality_inspection.t2");
        assertThat(route.waypoints()).hasSize(2);

        registry.clear("R001");
        assertThat(registry.snapshot()).isEmpty();
    }

    // 场景：多机器人注册后全量清除；预期：快照清空
    @Test
    void clearAll_emptiesSnapshot() {
        registry.register("R001", "t1", "F1", List.of("P1"));
        registry.register("R002", "t2", "F1", List.of("P2"));

        registry.clearAll();

        assertThat(registry.snapshot()).isEmpty();
    }
}
