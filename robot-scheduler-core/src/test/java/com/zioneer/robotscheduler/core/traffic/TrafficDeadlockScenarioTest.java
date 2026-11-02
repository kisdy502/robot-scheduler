package com.zioneer.robotscheduler.core.traffic;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 交通死锁场景测试：对向段互斥 / 驻留点占用（对应场景一、场景二）。
 */
class TrafficDeadlockScenarioTest {

    private final InMemoryTrafficRegistry registry = new InMemoryTrafficRegistry();

    // ==================== 场景二：单线对向互斥 ====================

    @Test
    void oppositeDirectionSegments_areMutuallyExclusive() {
        // 双向路径统一段 ID 后，A 占去程段，B 的对向申请必须被拒
        String forward = "PATH_X#WP1-WP2";
        String backward = "PATH_X#WP1-WP2"; // 同一段 ID（无序点对）

        boolean aAcquired = registry.tryAcquire("A", List.of(forward), Set.of("WP2"), Duration.ofMinutes(1));
        boolean bAcquired = registry.tryAcquire("B", List.of(backward), Set.of("WP1"), Duration.ofMinutes(1));

        assertThat(aAcquired).isTrue();
        assertThat(bAcquired).as("对向段应互斥：B 必须等待 A 通过").isFalse();
    }

    @Test
    void sameRobot_canReuseOwnOppositeSegment() {
        // 同一机器人自己占的段不拦自己（任务续占场景）
        registry.tryAcquire("A", List.of("PATH_X#WP1-WP2"), Set.of("WP2"), Duration.ofMinutes(1));
        boolean reacquired = registry.tryAcquire("A", List.of("PATH_X#WP1-WP2"), Set.of("WP3"),
                Duration.ofMinutes(1));
        assertThat(reacquired).isTrue();
    }

    @Test
    void segmentReleased_afterRobotArrives_oppositeCanProceed() {
        // A 通过后释放，B 对向可通行（错峰通过）
        registry.tryAcquire("A", List.of("PATH_X#WP1-WP2"), Set.of("WP2"), Duration.ofMinutes(1));
        registry.releaseSegments("A", List.of("PATH_X#WP1-WP2"));

        boolean bAcquired = registry.tryAcquire("B", List.of("PATH_X#WP1-WP2"), Set.of("WP1"),
                Duration.ofMinutes(1));
        assertThat(bAcquired).isTrue();
    }

    // ==================== 场景一：死端必经点被静止机器人堵住 ====================

    @Test
    void dwellWaypoint_blocksOtherRobotsTargetAcquire() {
        // B 静止驻留在 R1（死端 P6 的必经点），A 的目标点申请 R1 被拒
        registry.updateDwell("B", "R1", null);

        boolean aAcquired = registry.tryAcquire("A", List.of("PATH_Y#P6-R1"), Set.of("R1"),
                Duration.ofMinutes(1));

        assertThat(aAcquired).as("驻留点被 B 占用，A 应被拒（等待 B 离开）").isFalse();
    }

    @Test
    void dwellOwner_visibleForPrecisionError() {
        registry.updateDwell("B", "R1", null);
        assertThat(registry.dwellOwner("R1")).isEqualTo("B");
        assertThat(registry.dwellOwner("P6")).isNull();
    }

    @Test
    void dwellCleared_whenRobotMoves_orOffline() {
        registry.updateDwell("B", "R1", null);
        assertThat(registry.occupiedWaypoints()).contains("R1");

        // B 开始移动/离线 → 清除驻留 → R1 释放
        registry.updateDwell("B", null, null);
        assertThat(registry.occupiedWaypoints()).doesNotContain("R1");

        boolean aAcquired = registry.tryAcquire("A", List.of("PATH_Y#P6-R1"), Set.of("R1"),
                Duration.ofMinutes(1));
        assertThat(aAcquired).isTrue();
    }

    @Test
    void dwellOverwritten_whenRobotMovesToAnotherPoint() {
        // B 从 R1 移到 T1：旧驻留点自动释放
        registry.updateDwell("B", "R1", null);
        registry.updateDwell("B", "T1", null);

        assertThat(registry.occupiedWaypoints()).contains("T1").doesNotContain("R1");
        assertThat(registry.dwellOwner("R1")).isNull();
        assertThat(registry.dwellOwner("T1")).isEqualTo("B");
    }

    @Test
    void dwellDoesNotBlockOwnRobot() {
        // 机器人自己的驻留点不拦自己的任务
        registry.updateDwell("A", "R1", null);
        boolean acquired = registry.tryAcquire("A", List.of(), Set.of("R1"), Duration.ofMinutes(1));
        assertThat(acquired).isTrue();
    }

    // ==================== 场景 L2：静止机器人停在路段中间（路线被占）====================

    @Test
    void dwellSegment_blocksOtherRobotsPathAcquire() {
        // B 静止停在 P1-P2 路段中间（故障/停靠），A 的任务路径含该段 → 被拒
        registry.updateDwell("B", null, "PATH_L#P1-P2");

        boolean aAcquired = registry.tryAcquire("A", List.of("PATH_L#P1-P2"), Set.of("P2"),
                Duration.ofMinutes(1));

        assertThat(aAcquired).as("路段被静止机器人占据，A 应被拒").isFalse();
    }

    @Test
    void dwellSegment_visibleInOccupiedSegments_forPlanningDetour() {
        // 驻留段进入 occupiedSegments → 规划层自动绕行输入
        registry.updateDwell("B", null, "PATH_L#P1-P2");

        assertThat(registry.occupiedSegments()).contains("PATH_L#P1-P2");
    }

    @Test
    void dwellSegmentCleared_whenRobotMovesOrOffline() {
        registry.updateDwell("B", null, "PATH_L#P1-P2");
        assertThat(registry.occupiedSegments()).contains("PATH_L#P1-P2");

        // B 恢复移动/离线 → 清除 → A 可通行
        registry.updateDwell("B", null, null);
        assertThat(registry.occupiedSegments()).doesNotContain("PATH_L#P1-P2");

        boolean aAcquired = registry.tryAcquire("A", List.of("PATH_L#P1-P2"), Set.of("P2"),
                Duration.ofMinutes(1));
        assertThat(aAcquired).isTrue();
    }

    @Test
    void dwellSegment_doesNotBlockOwnRobot() {
        // 自己停的段不拦自己（恢复任务时重新通过该段）
        registry.updateDwell("B", null, "PATH_L#P1-P2");
        boolean acquired = registry.tryAcquire("B", List.of("PATH_L#P1-P2"), Set.of("P2"),
                Duration.ofMinutes(1));
        assertThat(acquired).isTrue();
    }

    @Test
    void dwellPointAndSegment_coexist() {
        // 同一机器人同时登记驻留点+驻留段（吸附点靠近 waypoint 但也在段上的边界情况）
        registry.updateDwell("B", "P1", "PATH_L#P1-P2");
        assertThat(registry.occupiedWaypoints()).contains("P1");
        assertThat(registry.occupiedSegments()).contains("PATH_L#P1-P2");
        assertThat(registry.dwellOwner("P1")).isEqualTo("B");
        assertThat(registry.dwellSegmentOwner("PATH_L#P1-P2")).isEqualTo("B");
    }

    @Test
    void ownDwellPoint_doesNotBlockOwnPlanning() {
        // 回归：机器人 A 驻留自己的起点 P1，从 P1 规划出发不应被自己的驻留点堵死
        // （JGraphT "Graph must contain the source vertex" 修复验证）
        registry.updateDwell("A", "P1", null);
        var blockedWaypoints = registry.occupiedWaypoints(); // 含 A 自己的驻留点 P1

        var metadata = new com.zioneer.robotscheduler.core.map.MapMetadata();
        var versionInfo = new com.zioneer.robotscheduler.core.map.MapMetadata.VersionInfo();
        versionInfo.setVersion("v1");
        versionInfo.setStatus("published");
        metadata.setVersionInfo(versionInfo);
        metadata.setMapName("DwellSelfMap");
        var floor = new com.zioneer.robotscheduler.core.map.FloorMetadata();
        floor.setFloorId("F1");
        floor.setDefaultMaxSpeed(1.0);
        var p1 = new com.zioneer.robotscheduler.core.map.WaypointMetadata();
        p1.setId("P1"); p1.setX(0); p1.setY(0); p1.setTheta(0);
        p1.setType(com.zioneer.robotscheduler.core.map.WaypointType.PATH);
        var p2 = new com.zioneer.robotscheduler.core.map.WaypointMetadata();
        p2.setId("P2"); p2.setX(5); p2.setY(0); p2.setTheta(0);
        p2.setType(com.zioneer.robotscheduler.core.map.WaypointType.PATH);
        floor.setWaypoints(List.of(p1, p2));
        var path = new com.zioneer.robotscheduler.core.map.PathMetadata();
        path.setId("L1"); path.setFrom("P1"); path.setTo("P2");
        path.setWaypoints(List.of("P1", "P2")); path.setBidirectional(true);
        floor.setPaths(List.of(path));
        metadata.setFloors(List.of(floor));

        var graph = com.zioneer.robotscheduler.core.routing.NavigationGraph.from(metadata);
        var route = graph.plan("F1", "P1", "P2", 1.0, Set.of(), blockedWaypoints);

        assertThat(route).as("起点是自己的驻留点，规划不应失败").isPresent();
    }

    // ==================== 组合场景：驻留 + 规划不可达（场景一完整链路）====================

    @Test
    void scenario1_deadEndBlockedByDwellingRobot_planningFindsNoPath() {
        // 地图：P1 - R1 - P6（P6 是死端，R1 是唯一必经点）
        // B 静止在 R1 → A 从 P1 去 P6：规划时 R1 是封锁点 → 无路可达
        var metadata = new com.zioneer.robotscheduler.core.map.MapMetadata();
        var versionInfo = new com.zioneer.robotscheduler.core.map.MapMetadata.VersionInfo();
        versionInfo.setVersion("v1");
        versionInfo.setStatus("published");
        metadata.setVersionInfo(versionInfo);
        metadata.setMapName("DeadEndMap");

        var floor = new com.zioneer.robotscheduler.core.map.FloorMetadata();
        floor.setFloorId("F1");
        floor.setDefaultMaxSpeed(1.0);

        var p1 = new com.zioneer.robotscheduler.core.map.WaypointMetadata();
        p1.setId("P1"); p1.setX(0); p1.setY(0); p1.setTheta(0);
        p1.setType(com.zioneer.robotscheduler.core.map.WaypointType.PATH);
        var r1 = new com.zioneer.robotscheduler.core.map.WaypointMetadata();
        r1.setId("R1"); r1.setX(5); r1.setY(0); r1.setTheta(0);
        r1.setType(com.zioneer.robotscheduler.core.map.WaypointType.PATH);
        var p6 = new com.zioneer.robotscheduler.core.map.WaypointMetadata();
        p6.setId("P6"); p6.setX(10); p6.setY(0); p6.setTheta(0);
        p6.setType(com.zioneer.robotscheduler.core.map.WaypointType.STORAGE);
        floor.setWaypoints(List.of(p1, r1, p6));

        var path1 = new com.zioneer.robotscheduler.core.map.PathMetadata();
        path1.setId("L1"); path1.setFrom("P1"); path1.setTo("R1");
        path1.setWaypoints(List.of("P1", "R1")); path1.setBidirectional(true);
        var path2 = new com.zioneer.robotscheduler.core.map.PathMetadata();
        path2.setId("L2"); path2.setFrom("R1"); path2.setTo("P6");
        path2.setWaypoints(List.of("R1", "P6")); path2.setBidirectional(true);
        floor.setPaths(List.of(path1, path2));
        metadata.setFloors(List.of(floor));

        var graph = com.zioneer.robotscheduler.core.routing.NavigationGraph.from(metadata);

        // 无障碍：P1 -> P6 可达（经 R1）
        assertThat(graph.plan("F1", "P1", "P6", 1.0, Set.of(), Set.of())).isPresent();

        // B 驻留 R1（死端必经点）→ 规划无路
        registry.updateDwell("B", "R1", null);
        var blockedWaypoints = registry.occupiedWaypoints();
        var route = graph.plan("F1", "P1", "P6", 1.0, Set.of(), blockedWaypoints);

        assertThat(route).as("必经点 R1 被驻留，P6 不可达").isEmpty();

        // B 离开 → 恢复可达
        registry.updateDwell("B", null, null);
        assertThat(graph.plan("F1", "P1", "P6", 1.0, Set.of(), registry.occupiedWaypoints())).isPresent();
    }
}
