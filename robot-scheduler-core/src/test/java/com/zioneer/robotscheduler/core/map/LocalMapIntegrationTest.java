package com.zioneer.robotscheduler.core.map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zioneer.robotscheduler.core.routing.NavigationGraph;
import com.zioneer.robotscheduler.core.routing.NavigationRoute;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * 真实地图（map1022Pro，local-map/map_info.json）加载与规划集成验证。
 *
 * <p>环境无地图文件时自动跳过（CI 不依赖本地数据）。
 * 地图 v3.5.6 特征：24 点位（含 C1/C2 充电位、H1/H2 停靠位、P3-P11 途经点）、
 * 29 条路径（网状拓扑，含 P8/H2/P9/P10 中部环、P3/H1 枢纽）。</p>
 */
class LocalMapIntegrationTest {

    private static final Path MAP_JSON = Path.of("..", "local-map", "map_info.json");

    private static MapMetadata metadata;
    private static NavigationGraph graph;

    @BeforeAll
    static void loadMap() throws Exception {
        assumeThat(Files.exists(MAP_JSON))
                .as("本地地图文件不存在，跳过真实地图集成测试: %s", MAP_JSON.toAbsolutePath())
                .isTrue();
        metadata = new MapMetadataParser().parse(
                Files.readString(MAP_JSON));
        // 断言绑定 map1022Pro 的具体点位/坐标；本地文件被替换为其他地图时跳过（数据源驱动的集成测试）
        assumeThat(metadata.getMapName())
                .as("本地地图不是 map1022Pro（当前 %s），跳过专属断言", metadata.getMapName())
                .isEqualTo("map1022Pro");
        graph = NavigationGraph.from(metadata);
    }

    // 场景：解析真实地图 map1022Pro；预期：元数据完整（published、单楼层 F1、点位≥13、路径≥11）
    @Test
    void parse_realMap_metadataComplete() {
        assertThat(metadata.getMapName()).isEqualTo("map1022Pro");
        assertThat(metadata.getVersionInfo().getStatus()).isEqualTo("published");
        assertThat(metadata.getFloors()).hasSize(1);
        var floor = metadata.getFloors().get(0);
        assertThat(floor.getFloorId()).isEqualTo("F1");
        assertThat(floor.getWaypoints().size()).isGreaterThanOrEqualTo(13);
        assertThat(floor.getPaths().size()).isGreaterThanOrEqualTo(11);
    }

    // 场景：按类型查询图上点位；预期：充电位含 C1、停靠位含 H1
    @Test
    void graph_chargeAndParkingPoints_queryable() {
        List<WaypointMetadata> chargers = graph.waypointsByType("F1", WaypointType.CHARGE);
        List<WaypointMetadata> parkings = graph.waypointsByType("F1", WaypointType.PARKING);

        assertThat(chargers).extracting(WaypointMetadata::getId).contains("C1");
        assertThat(parkings).extracting(WaypointMetadata::getId).contains("H1");
    }

    // 场景：C1→W4 为贝塞尔曲线段；预期：段长度大于两点间直线距离
    @Test
    void graph_curveSegments_longerThanStraightLine() {
        // PATH_002 C1 -> W4 为贝塞尔曲线段，长度应大于直线距离
        Optional<NavigationRoute> route = graph.plan("F1", "C1", "W4", 1.0, null);

        assertThat(route).isPresent();
        NavigationRoute.Segment segment = route.get().segments().get(0);
        double straight = Math.hypot(9.4082 - 0.2714, 14.6035 - 13.3853);
        assertThat(segment.lengthMeters()).isGreaterThan(straight);
    }

    // 场景：全图规划 C1→W1；预期：走 C1-W4-D1-W1 经典路径且总长超 25 米
    @Test
    void plan_acrossFullMap_c1ToW1() {
        // C1 -> W1 全图规划：C1-W4-D1-W1（经典路径）
        Optional<NavigationRoute> route = graph.plan("F1", "C1", "W1", 1.0, null);

        assertThat(route).isPresent();
        assertThat(route.get().waypointIds()).containsExactly("C1", "W4", "D1", "W1");
        assertThat(route.get().totalLengthMeters()).isGreaterThan(25.0);
    }

    // 场景：封锁 C1-W4 段后规划 C1→W1；预期：经中部环绕行成功且不经过 W4
    @Test
    void plan_blockedSegment_findsAlternative() {
        // 封锁 C1-W4 段后 C1 -> W1 可走中部环：C1-P8-H2-...-W1（网状拓扑绕行能力）
        Optional<NavigationRoute> route = graph.plan("F1", "C1", "W1", 1.0,
                java.util.Set.of("PATH_002#C1-W4"));

        assertThat(route).isPresent();
        assertThat(route.get().waypointIds()).first().isEqualTo("C1");
        assertThat(route.get().waypointIds()).last().isEqualTo("W1");
        assertThat(route.get().waypointIds()).doesNotContain("W4");
    }

    // 场景：机器人真实坐标吸附到图；预期：停靠位附近坐标吸附到 H1
    @Test
    void attach_robotRealPosition_resolvesOnGraph() {
        // 停靠位附近吸附验证
        var parkingNearby = graph.attach("F1", 14.0, -6.3, 3.0, 1.0);
        assertThat(parkingNearby.waypointIdOptional()).contains("H1");
    }

    // 场景：往返路径 waypoints=[T1,E1,T1]；预期：T1 与 E1 双向均可规划
    @Test
    void plan_roundTripPath_expandsBothDirections() {
        // PATH_006 waypoints=[T1,E1,T1]：T1 与 E1 双向连通
        assertThat(graph.plan("F1", "T1", "E1", 1.0, null)).isPresent();
        assertThat(graph.plan("F1", "E1", "T1", 1.0, null)).isPresent();
    }

    // 场景：网状拓扑 H1 为 5 路枢纽；预期：到 W1/L1/P3/P4/P2 各方向均可达
    @Test
    void plan_meshTopology_multiRouteAvailable() {
        // 网状拓扑验证：H1 是 5 路枢纽，从 H1 应能到达多个方向
        List<String> targets = List.of("W1", "L1", "P3", "P4", "P2");
        for (String target : targets) {
            assertThat(graph.plan("F1", "H1", target, 1.0, null))
                    .as("H1 -> %s 应可达", target)
                    .isPresent();
        }
    }
}
