package com.zioneer.robotscheduler.core.routing;

import com.zioneer.robotscheduler.core.map.FloorMetadata;
import com.zioneer.robotscheduler.core.map.MapMetadata;
import com.zioneer.robotscheduler.core.map.PathMetadata;
import com.zioneer.robotscheduler.core.map.WaypointMetadata;
import com.zioneer.robotscheduler.core.map.WaypointType;
import com.zioneer.robotscheduler.core.map.ZoneMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 导航图构建与规划测试：区域约束（禁行/限速）、单向路径、位置吸附、交通段封锁绕行。
 *
 * <p>拓扑：A(0,0)-B(3,0)-C(6,0) 直线；A-D(3,3)、B-D、D→E(3,6) 单向、E-F(3,9) 穿禁区。
 * B-D 段穿 slowDown 区；A-D 段不穿（绕行路径）。</p>
 */
class NavigationGraphTest {

    private final NavigationGraph graph = NavigationGraph.from(buildMap());

    @Test
    void plan_directRoute_usesShortestPath() {
        Optional<NavigationRoute> route = graph.plan("F1", "WP_A", "WP_C", 1.0, null);

        assertThat(route).isPresent();
        assertThat(route.get().waypointIds()).containsExactly("WP_A", "WP_B", "WP_C");
        assertThat(route.get().totalLengthMeters()).isEqualTo(6.0);
        // A-B 段限速 0.8（3/0.8=3.75s）+ B-C 段默认 1.0（3s）
        assertThat(route.get().etaSeconds()).isEqualTo(6.75);
    }

    @Test
    void plan_segmentBlocked_detoursAround() {
        // 封锁 A-B 段后走 A-D-B-C 绕行
        Optional<NavigationRoute> route = graph.plan("F1", "WP_A", "WP_C", 1.0,
                Set.of("PATH_AB#WP_A-WP_B"));

        assertThat(route).isPresent();
        assertThat(route.get().waypointIds()).containsExactly("WP_A", "WP_D", "WP_B", "WP_C");
        assertThat(route.get().totalLengthMeters()).isEqualTo(Math.hypot(3, 3) + 3.0 + 3.0);
    }

    @Test
    void plan_slowDownZoneLimitsSegmentSpeed() {
        // 封锁 B-A 段后 B→D 只剩 B-D 直连：段限速 = min(path 0.5, slowDown 区 0.3) = 0.3
        Optional<NavigationRoute> route = graph.plan("F1", "WP_B", "WP_D", 1.0,
                Set.of("PATH_AB#WP_A-WP_B"));

        assertThat(route).isPresent();
        assertThat(route.get().waypointIds()).containsExactly("WP_B", "WP_D");
        NavigationRoute.Segment segment = route.get().segments().get(0);
        assertThat(segment.maxSpeedMetersPerSecond()).isEqualTo(0.3);
        // 机器人速度 1.0，有效速度 0.3 → ETA = 3/0.3 = 10s
        assertThat(route.get().etaSeconds()).isEqualTo(10.0);
    }

    @Test
    void plan_robotSpeedCapsSegmentSpeed() {
        Optional<NavigationRoute> route = graph.plan("F1", "WP_A", "WP_B", 0.25, null);

        assertThat(route).isPresent();
        // 段限速 0.8 但机器人上限 0.25 → ETA = 3/0.25 = 12s
        assertThat(route.get().etaSeconds()).isEqualTo(12.0);
    }

    @Test
    void plan_forbiddenZoneRemovesSegment() {
        // E-F 唯一路径穿过 forbidden 区 → 该段不建边，规划无路
        assertThat(graph.plan("F1", "WP_E", "WP_F", 1.0, null)).isEmpty();
    }

    @Test
    void plan_unidirectionalPath_onlyOneDirection() {
        // D→E 单向：E 不能到 D
        assertThat(graph.plan("F1", "WP_D", "WP_E", 1.0, null)).isPresent();
        assertThat(graph.plan("F1", "WP_E", "WP_D", 1.0, null)).isEmpty();
    }

    @Test
    void plan_segmentIdContainsPathAndWaypoints() {
        NavigationRoute route = graph.plan("F1", "WP_A", "WP_B", 1.0, null).orElseThrow();

        assertThat(route.segments().get(0).id()).isEqualTo("PATH_AB#WP_A-WP_B");
        assertThat(route.segments().get(0).pathId()).isEqualTo("PATH_AB");
    }

    @Test
    void plan_viaWaypointsExpandsToSegments() {
        // 封锁直连 B-C 段后走带途经点的 B-VIA-C 路径（两段）
        Optional<NavigationRoute> route = graph.plan("F1", "WP_B", "WP_C", 1.0,
                Set.of("PATH_BC#WP_B-WP_C"));

        assertThat(route).isPresent();
        assertThat(route.get().waypointIds()).containsExactly("WP_B", "WP_VIA", "WP_C");
        assertThat(route.get().segments()).hasSize(2);
    }

    @Test
    void attach_withinWaypointThreshold_attachesToWaypoint() {
        GraphAttachment attachment = graph.attach("F1", 0.4, 0.0, 3.0, 1.0);

        assertThat(attachment.attached()).isTrue();
        assertThat(attachment.waypointIdOptional()).contains("WP_A");
    }

    @Test
    void attach_onSegment_attachesToNearestSegment() {
        // (1.5, 0.2) 距 A、B 均超阈值，但在 A-B 段附近（垂距 0.2）
        GraphAttachment attachment = graph.attach("F1", 1.5, 0.2, 0.5, 1.0);

        assertThat(attachment.attached()).isTrue();
        assertThat(attachment.segmentIdOptional()).contains("PATH_AB#WP_A-WP_B");
        assertThat(attachment.distanceMeters()).isEqualTo(0.2);
    }

    @Test
    void attach_farAway_returnsNone() {
        GraphAttachment attachment = graph.attach("F1", 50.0, 50.0, 3.0, 1.0);

        assertThat(attachment.attached()).isFalse();
    }

    @Test
    void waypointsByType_returnsChargePoints() {
        List<WaypointMetadata> chargers = graph.waypointsByType("F1", WaypointType.CHARGE);

        assertThat(chargers).extracting(WaypointMetadata::getId).containsExactly("WP_A");
    }

    @Test
    void waypointByIdOrName_resolvesBoth() {
        assertThat(graph.waypointByIdOrName("F1", "WP_B")).isPresent();
        assertThat(graph.waypointByIdOrName("F1", "Waypoint B")).isPresent();
        assertThat(graph.waypointByIdOrName("F1", "WP_404")).isEmpty();
    }

    @Test
    void unknownFloor_planReturnsEmpty() {
        assertThat(graph.plan("F9", "WP_A", "WP_B", 1.0, null)).isEmpty();
    }

    @Test
    void plan_sameSourceAndGoal_returnsImmediateArrival() {
        // 原地任务（机器人已在目标点）：from==to 应返回单点路径而非异常（Set.of 重复元素回归）
        Optional<NavigationRoute> route = graph.plan("F1", "WP_A", "WP_A", 1.0,
                java.util.Set.of(), java.util.Set.of("WP_A"));

        assertThat(route).isPresent();
        assertThat(route.get().waypointIds()).containsExactly("WP_A");
        assertThat(route.get().segments()).isEmpty();
        assertThat(route.get().etaSeconds()).isZero();
    }

    @Test
    void plan_robotSpeedChangesBestRoute_withoutMutatingSharedGraph() {
        MapMetadata map = buildMap();
        FloorMetadata floor = map.getFloors().get(0);
        floor.setZoneConstraints(List.of());
        floor.setPaths(List.of(
                path("FAST_DETOUR", List.of("WP_A", "WP_D", "WP_C"), 2.0, true),
                path("SHORT", List.of("WP_A", "WP_C"), 0.8, true)));
        NavigationGraph routes = NavigationGraph.from(map);
        assertThat(routes.plan("F1", "WP_A", "WP_C", 2.0, null).orElseThrow().waypointIds())
                .containsExactly("WP_A", "WP_D", "WP_C");
        NavigationRoute slow = routes.plan("F1", "WP_A", "WP_C", 0.5, Set.of("unused")).orElseThrow();
        assertThat(slow.waypointIds()).containsExactly("WP_A", "WP_C");
        assertThat(slow.etaSeconds()).isEqualTo(12.0);
        assertThat(slow.segments().get(0).maxSpeedMetersPerSecond()).isEqualTo(0.5);
        assertThat(routes.plan("F1", "WP_A", "WP_C", 2.0, null).orElseThrow().waypointIds())
                .containsExactly("WP_A", "WP_D", "WP_C");
    }

    @Test
    void plan_invalidRobotSpeed_isRejected() {
        for (double speed : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    graph.plan("F1", "WP_A", "WP_B", speed, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ==================== 测试地图构建 ====================

    private MapMetadata buildMap() {
        MapMetadata metadata = new MapMetadata();
        MapMetadata.VersionInfo versionInfo = new MapMetadata.VersionInfo();
        versionInfo.setVersion("v0.1.0");
        versionInfo.setStatus("published");
        metadata.setVersionInfo(versionInfo);
        metadata.setMapName("UnitTestMap");
        metadata.setCoordinateSystem("map");
        metadata.setUnit("meters");

        FloorMetadata floor = new FloorMetadata();
        floor.setFloorId("F1");
        floor.setName("Floor 1");
        floor.setElevation(0.0);
        floor.setDefaultMaxSpeed(1.0);

        floor.setWaypoints(List.of(
                waypoint("WP_A", "Waypoint A", 0, 0, WaypointType.CHARGE),
                waypoint("WP_B", "Waypoint B", 3, 0, WaypointType.PATH),
                waypoint("WP_C", "Waypoint C", 6, 0, WaypointType.PICKUP),
                waypoint("WP_D", "Waypoint D", 3, 3, WaypointType.PARKING),
                waypoint("WP_E", "Waypoint E", 3, 6, WaypointType.STORAGE),
                waypoint("WP_F", "Waypoint F", 3, 9, WaypointType.WORKSTATION),
                waypoint("WP_VIA", "Via Point", 4.5, 0.5, WaypointType.PATH)));

        ZoneMetadata slowZone = new ZoneMetadata();
        slowZone.setId("ZC_SLOW");
        slowZone.setType("slowDown");
        slowZone.setPriority(2);
        slowZone.setMaxSpeed(0.3);
        // 只挡 B-D 段（x=3, y∈[0,3]），不挡 A-D 段
        slowZone.setVertices(new double[][]{{2.8, 1.5}, {3.5, 1.5}, {3.5, 2.5}, {2.8, 2.5}});

        ZoneMetadata forbidden = new ZoneMetadata();
        forbidden.setId("ZC_FORBIDDEN");
        forbidden.setType("forbidden");
        forbidden.setPriority(1);
        forbidden.setMaxSpeed(0.0);
        forbidden.setVertices(new double[][]{{2.5, 7}, {3.5, 7}, {3.5, 8}, {2.5, 8}});
        floor.setZoneConstraints(List.of(slowZone, forbidden));

        floor.setPaths(List.of(
                path("PATH_AB", List.of("WP_A", "WP_B"), 0.8, true),
                path("PATH_BC", List.of("WP_B", "WP_C"), null, true),
                path("PATH_BVC", List.of("WP_B", "WP_VIA", "WP_C"), null, true),
                path("PATH_AD", List.of("WP_A", "WP_D"), null, true),
                path("PATH_BD", List.of("WP_B", "WP_D"), 0.5, true),
                path("PATH_DE", List.of("WP_D", "WP_E"), null, false),
                path("PATH_EF", List.of("WP_E", "WP_F"), null, true)));

        metadata.setFloors(List.of(floor));
        return metadata;
    }

    private WaypointMetadata waypoint(String id, String name, double x, double y, WaypointType type) {
        WaypointMetadata waypoint = new WaypointMetadata();
        waypoint.setId(id);
        waypoint.setName(name);
        waypoint.setX(x);
        waypoint.setY(y);
        waypoint.setTheta(0.0);
        waypoint.setType(type);
        return waypoint;
    }

    private PathMetadata path(String id, List<String> waypointIds, Double maxSpeed, boolean bidirectional) {
        PathMetadata path = new PathMetadata();
        path.setId(id);
        path.setFrom(waypointIds.get(0));
        path.setTo(waypointIds.get(waypointIds.size() - 1));
        path.setWaypoints(waypointIds);
        path.setMaxSpeed(maxSpeed);
        path.setBidirectional(bidirectional);
        return path;
    }
}
