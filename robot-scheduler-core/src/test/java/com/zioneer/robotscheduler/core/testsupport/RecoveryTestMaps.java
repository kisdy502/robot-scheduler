package com.zioneer.robotscheduler.core.testsupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zioneer.robotscheduler.core.map.MapManager;
import com.zioneer.robotscheduler.core.map.MapMetadataParser;
import com.zioneer.robotscheduler.core.repository.MapArchive;
import com.zioneer.robotscheduler.core.repository.MapArchiveRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.mockito.Mockito.*;

/** 独立双向路线，避免恢复测试中的三十台车被测试数据错误地摆在同一点上。 */
public final class RecoveryTestMaps {
    private RecoveryTestMaps() { }
    public static MapManager archived(String version, int robots) {
        try {
            List<Map<String, Object>> points = new ArrayList<>();
            List<Map<String, Object>> paths = new ArrayList<>();
            for (int i = 0; i < robots; i++) {
                points.add(Map.of("id", "H" + i, "type", "parking", "position", List.of(0, i * 10, 0)));
                points.add(Map.of("id", "W" + i, "type", "workstation", "position", List.of(5, i * 10, 0)));
                paths.add(Map.of("id", "P" + i, "from", "H" + i, "to", "W" + i,
                        "waypoints", List.of("H" + i, "W" + i), "isBidirectional", true));
            }
            String json = new ObjectMapper().writeValueAsString(Map.of(
                    "versionInfo", Map.of("version", version, "status", "published"),
                    "mapInfo", Map.of("mapName", "RecoveryMap", "floors", List.of(Map.of(
                            "floorId", "F1", "defaultMaxSpeed", 1.0, "waypoints", points, "paths", paths)))));
            var archive = new MapArchive();
            archive.setMetadataJson(json);
            var repository = mock(MapArchiveRepository.class);
            when(repository.findActive()).thenReturn(Optional.of(archive));
            return new MapManager(new MapMetadataParser(), repository);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
    public static MapManager loaded() {
        MapManager maps = archived("v1", 1);
        maps.loadFromArchive();
        return maps;
    }
}
