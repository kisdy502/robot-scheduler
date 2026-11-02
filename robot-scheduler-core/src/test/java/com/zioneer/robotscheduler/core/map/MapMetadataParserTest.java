package com.zioneer.robotscheduler.core.map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 地图元数据解析测试（对齐《地图编辑json文件格式》契约）。
 */
class MapMetadataParserTest {

    private final MapMetadataParser parser = new MapMetadataParser();

    @Test
    void parse_invalidSpeed_rejectedButForbiddenZeroAccepted() {
        String template = """
                {"versionInfo":{"version":"v1"},"mapInfo":{"mapName":"Speed","floors":[{
                  "floorId":"F1","defaultMaxSpeed":1.0,
                  "waypoints":[{"id":"A","type":"path","position":[0,0,0]},
                               {"id":"B","type":"path","position":[1,0,0]}],
                  "paths":[{"id":"P","waypoints":["A","B"],"maxSpeed":0.5}],
                  "zoneConstraints":[{"id":"Z","type":"forbidden","maxSpeed":0.0}]
                }]}}
                """;
        assertThat(parser.parse(template).getFloors()).hasSize(1);
        for (String value : new String[]{"0", "-1", "1e309"}) {
            assertThatThrownBy(() -> parser.parse(template.replace("\"maxSpeed\":0.5", "\"maxSpeed\":" + value)))
                    .isInstanceOf(MapMetadataException.class);
            assertThatThrownBy(() -> parser.parse(template.replace("\"defaultMaxSpeed\":1.0",
                    "\"defaultMaxSpeed\":" + value))).isInstanceOf(MapMetadataException.class);
        }
        assertThatThrownBy(() -> parser.parse(template.replace("\"maxSpeed\":0.0", "\"maxSpeed\":-1")))
                .isInstanceOf(MapMetadataException.class);
    }

    @Test
    void parse_validMap_returnsFullStructure() {
        MapMetadata metadata = parser.parse("""
                {
                  "versionInfo": {"version": "v0.1.0", "status": "published"},
                  "mapInfo": {
                    "mapName": "TestMap",
                    "coordinateSystem": "map",
                    "unit": "meters",
                    "floors": [{
                      "floorId": "F1", "name": "Floor 1", "elevation": 0.0, "defaultMaxSpeed": 1.0,
                      "waypoints": [
                        {"id": "WP_001", "name": "Charging", "position": [1.5, 0.5, 0.0], "type": "charge"},
                        {"id": "WP_002", "name": "Pickup", "position": [3.0, 2.0, 1.57], "type": "pickup"}
                      ],
                      "zoneConstraints": [{
                        "id": "ZC_001", "type": "forbidden", "priority": 1, "maxSpeed": 0.0,
                        "shape": "polygon", "vertices": [[1.0,3.0],[2.0,3.0],[2.0,4.0],[1.0,4.0]]
                      }],
                      "shelves": [],
                      "paths": [{
                        "id": "PATH_001", "from": "WP_001", "to": "WP_002",
                        "waypoints": ["WP_001", "WP_002"], "isBidirectional": true, "maxSpeed": 0.8
                      }]
                    }],
                    "interFloorConnections": []
                  }
                }
                """);

        assertThat(metadata.getMapName()).isEqualTo("TestMap");
        assertThat(metadata.getVersionInfo().getVersion()).isEqualTo("v0.1.0");
        assertThat(metadata.getFloors()).hasSize(1);
        FloorMetadata floor = metadata.getFloors().get(0);
        assertThat(floor.getWaypoints()).hasSize(2);
        assertThat(floor.getWaypoints().get(0).getType()).isEqualTo(WaypointType.CHARGE);
        assertThat(floor.getWaypoints().get(0).getTheta()).isEqualTo(0.0);
        assertThat(floor.getWaypoints().get(1).getX()).isEqualTo(3.0);
        assertThat(floor.getPaths()).hasSize(1);
        assertThat(floor.getZoneConstraints()).hasSize(1);
    }

    @Test
    void parse_missingVersion_throws() {
        assertThatThrownBy(() -> parser.parse("""
                {"versionInfo": {"status": "draft"}, "mapInfo": {"mapName": "X", "floors": []}}
                """))
                .isInstanceOf(MapMetadataException.class)
                .hasMessageContaining("version");
    }

    @Test
    void parse_noFloor_throws() {
        assertThatThrownBy(() -> parser.parse("""
                {"versionInfo": {"version": "v0.1.0"}, "mapInfo": {"mapName": "X", "floors": []}}
                """))
                .isInstanceOf(MapMetadataException.class)
                .hasMessageContaining("floor");
    }

    @Test
    void parse_duplicateWaypointId_throws() {
        assertThatThrownBy(() -> parser.parse("""
                {
                  "versionInfo": {"version": "v0.1.0"},
                  "mapInfo": {"mapName": "X", "floors": [{
                    "floorId": "F1", "defaultMaxSpeed": 1.0,
                    "waypoints": [
                      {"id": "WP_001", "position": [0,0,0], "type": "path"},
                      {"id": "WP_001", "position": [1,1,0], "type": "path"}
                    ], "paths": []
                  }]}
                }
                """))
                .isInstanceOf(MapMetadataException.class)
                .hasMessageContaining("unique");
    }

    @Test
    void parse_pathReferencingUnknownWaypoint_throws() {
        assertThatThrownBy(() -> parser.parse("""
                {
                  "versionInfo": {"version": "v0.1.0"},
                  "mapInfo": {"mapName": "X", "floors": [{
                    "floorId": "F1", "defaultMaxSpeed": 1.0,
                    "waypoints": [{"id": "WP_001", "position": [0,0,0], "type": "path"}],
                    "paths": [{"id": "PATH_1", "waypoints": ["WP_001", "WP_404"]}]
                  }]}
                }
                """))
                .isInstanceOf(MapMetadataException.class)
                .hasMessageContaining("unknown waypoint");
    }

    @Test
    void parse_invalidJson_throws() {
        assertThatThrownBy(() -> parser.parse("not a json"))
                .isInstanceOf(MapMetadataException.class)
                .hasMessageContaining("解析失败");
    }
}
