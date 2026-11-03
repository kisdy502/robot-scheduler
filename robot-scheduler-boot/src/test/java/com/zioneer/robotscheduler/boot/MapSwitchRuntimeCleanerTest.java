package com.zioneer.robotscheduler.boot;

import com.zioneer.robotscheduler.core.fleet.TaskAssignmentRegistry;
import com.zioneer.robotscheduler.core.map.MapActivationConflictException;
import com.zioneer.robotscheduler.core.map.MapManager;
import com.zioneer.robotscheduler.core.map.MapMetadataParser;
import com.zioneer.robotscheduler.core.repository.MapArchiveRepository;
import com.zioneer.robotscheduler.core.traffic.ActiveRouteRegistry;
import com.zioneer.robotscheduler.core.traffic.InMemoryTrafficRegistry;
import com.zioneer.robotscheduler.core.traffic.WaitIntentRegistry;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class MapSwitchRuntimeCleanerTest {
    // 驻留位置帧尚未到达时，外部作业也应阻止切图；作业结束后允许激活新版本。
    @Test
    void externalOperationBlocksMapSwitchEvenBeforeDwellFrameArrives() {
        var maps = new MapManager(new MapMetadataParser(), mock(MapArchiveRepository.class));
        var traffic = new InMemoryTrafficRegistry();
        var owners = new TaskAssignmentRegistry();
        new MapSwitchRuntimeCleaner(owners, maps, traffic, new WaitIntentRegistry(),
                new ActiveRouteRegistry(maps)).register();
        String json = """
                {"versionInfo":{"version":"v1","status":"published"},
                 "mapInfo":{"mapName":"External","floors":[{"floorId":"F1","defaultMaxSpeed":1.0,
                  "waypoints":[{"id":"A","type":"path","position":[0,0,0]}],"paths":[]}]}}
                """;
        maps.activateFromJson(json, null, null);
        owners.assign("flow", "R1");
        owners.startOperation("flow", "R1", "photo");
        assertThatThrownBy(() -> maps.activateFromJson(json.replace("v1", "v2"), null, null))
                .isInstanceOf(MapActivationConflictException.class);
        owners.finishOperation("flow", "R1", "photo");
        maps.activateFromJson(json.replace("v1", "v2"), null, null);
        assertThat(maps.activeSnapshot().orElseThrow().version()).isEqualTo("v2");
    }

    @Test
    void wiredGuard_rejectsDwellAndPreservesRuntimeUntilSafeSwitch() {
        String json = """
                {"versionInfo":{"version":"v1","status":"published"},
                 "mapInfo":{"mapName":"Wiring","floors":[{"floorId":"F1","defaultMaxSpeed":1.0,
                  "waypoints":[{"id":"A","type":"path","position":[0,0,0]}],"paths":[]}]}}
                """;
        MapManager maps = new MapManager(new MapMetadataParser(), mock(MapArchiveRepository.class));
        InMemoryTrafficRegistry traffic = new InMemoryTrafficRegistry();
        WaitIntentRegistry waits = new WaitIntentRegistry();
        ActiveRouteRegistry routes = new ActiveRouteRegistry(maps);
        new MapSwitchRuntimeCleaner(new TaskAssignmentRegistry(), maps, traffic, waits, routes).register();
        maps.activateFromJson(json, null, null);
        traffic.updateDwell("R1", "A", null);
        waits.record("R2", List.of("P"));
        routes.register("R1", "task", "F1", List.of("A"));
        assertThatThrownBy(() -> maps.activateFromJson(json.replace("v1", "v2"), null, null))
                .isInstanceOf(MapActivationConflictException.class);
        assertThat(waits.snapshot()).hasSize(1);
        assertThat(routes.snapshot()).hasSize(1);
        assertThat(traffic.occupiedWaypoints()).contains("A");
        traffic.updateDwell("R1", null, null);
        maps.activateFromJson(json.replace("v1", "v2"), null, null);
        assertThat(waits.snapshot()).isEmpty();
        assertThat(routes.snapshot()).isEmpty();
        assertThat(maps.activeSnapshot().orElseThrow().version()).isEqualTo("v2");
    }
}
