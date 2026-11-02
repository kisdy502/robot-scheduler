package com.zioneer.robotscheduler.core.traffic;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 交通注册表审计与快照测试：事件类型完整成对（占/释 × 点/段）、任务+驻留合并不崩溃。
 */
class InMemoryTrafficRegistryTest {

    private static final class RecordingSink implements TrafficEventSink {
        final List<String> actions = new ArrayList<>();
        final List<List<String>> resources = new ArrayList<>();

        @Override
        public void record(String robotCode, String action, List<String> resourceIds, String detail) {
            actions.add(action);
            resources.add(resourceIds);
        }
    }

    @Test
    void acquireAndRelease_emitSegmentAndWaypointEventsSeparately() {
        RecordingSink sink = new RecordingSink();
        InMemoryTrafficRegistry registry = new InMemoryTrafficRegistry();
        registry.setEventSink(sink);

        registry.tryAcquire("R001",
                List.of("PATH_1#P1-P2", "PATH_2#P2-P3"), Set.of("P3"), Duration.ofMinutes(1));
        assertThat(sink.actions).containsExactly("ACQUIRE_SEGMENT", "ACQUIRE_WAYPOINT");
        assertThat(sink.resources.get(0)).containsExactly("PATH_1#P1-P2", "PATH_2#P2-P3");
        assertThat(sink.resources.get(1)).containsExactly("P3");

        // 渐进释放：段释放 + 占用点释放
        registry.releaseSegments("R001", List.of("PATH_1#P1-P2"));
        registry.releaseBehind("R001", "P3");
        assertThat(sink.actions.subList(2, 4))
                .containsExactly("RELEASE_SEGMENT", "RELEASE_WAYPOINT");

        // 全量释放：剩余段分开审计（点已随 releaseBehind 释放，无点事件）
        registry.releaseAll("R001");
        assertThat(sink.actions.get(4)).isEqualTo("RELEASE_SEGMENT");
        assertThat(sink.resources.get(4)).containsExactly("PATH_2#P2-P3");
    }

    @Test
    void waypointOnlyAcquisition_emitsWaypointEventOnly() {
        RecordingSink sink = new RecordingSink();
        InMemoryTrafficRegistry registry = new InMemoryTrafficRegistry();
        registry.setEventSink(sink);

        // 无图模式：只占用目标点
        registry.tryAcquire("R001", List.of(), Set.of("W1"), Duration.ofMinutes(1));

        assertThat(sink.actions).containsExactly("ACQUIRE_WAYPOINT");
    }

    @Test
    void occupationSnapshot_mergesTaskAndDwellWithoutCrash() {
        // 回归：任务占用行曾是 Set.copyOf 不可变集合，驻留 merge addAll 抛 UnsupportedOperationException
        InMemoryTrafficRegistry registry = new InMemoryTrafficRegistry();
        registry.tryAcquire("R001", List.of("PATH_1#P1-P2"), Set.of("P2"), Duration.ofMinutes(1));
        registry.updateDwell("R001", "P9", null);

        assertThatCode(registry::occupationSnapshot).doesNotThrowAnyException();
        var merged = registry.occupationSnapshot().get("R001");
        assertThat(merged.segmentIds()).containsExactlyInAnyOrder("PATH_1#P1-P2");
        assertThat(merged.waypointIds()).containsExactlyInAnyOrder("P2", "P9");
    }

    @Test
    void idleDwellOnly_occupiesWaypointInSnapshot() {
        // 停在点位上的静止机器人：驻留点必须在快照中可见
        InMemoryTrafficRegistry registry = new InMemoryTrafficRegistry();
        registry.updateDwell("R002", "H1", null);

        var row = registry.occupationSnapshot().get("R002");
        assertThat(row).isNotNull();
        assertThat(row.waypointIds()).containsExactly("H1");
        assertThat(registry.occupiedWaypoints()).contains("H1");
    }

    @Test
    void occupiedExcluding_ignoresOwnDwellAndTaskResources() {
        // 回归：机器人驻留在自家门口路段（如 P31-P33）时，规划约束必须排除自身占用，
        // 否则直连边被自己封死 → 大幅绕路（P33 绕行 C5 案例）
        InMemoryTrafficRegistry registry = new InMemoryTrafficRegistry();
        // 自己：驻留段 + 任务占段/点
        registry.updateDwell("R001", null, "PATH_054#P31-P33");
        registry.updateDwell("R001", "P33", null);
        registry.tryAcquire("R001", List.of("PATH_067#P33-P52"), Set.of("P52"), Duration.ofMinutes(1));
        // 别人：驻留点 + 任务占段/点
        registry.updateDwell("R002", "C3", null);
        registry.tryAcquire("R002", List.of("PATH_052#P32-C5"), Set.of("C5"), Duration.ofMinutes(1));

        assertThat(registry.occupiedSegmentsExcluding("R001"))
                .containsExactly("PATH_052#P32-C5");
        assertThat(registry.occupiedWaypointsExcluding("R001"))
                .containsExactlyInAnyOrder("C3", "C5");
        // 全量视图不受影响（互斥判定/观测用）
        assertThat(registry.occupiedSegments()).hasSize(2);
        assertThat(registry.occupiedWaypoints()).hasSize(4);
    }

    @Test
    void dwellChanges_auditedWithDwellDetail() {
        // 驻留进出审计：停车→占点，离开→释点（detail=dwell 区别于任务占用）
        RecordingSink sink = new RecordingSink();
        InMemoryTrafficRegistry registry = new InMemoryTrafficRegistry();
        registry.setEventSink(sink);

        registry.updateDwell("R001", "H1", null);
        registry.updateDwell("R001", "H1", null);            // 无变化不审计
        registry.updateDwell("R001", "H2", null);            // 换点 = 释旧占新
        registry.updateDwell("R001", null, null);            // 离开 = 释点

        assertThat(sink.actions).containsExactly(
                "ACQUIRE_WAYPOINT", "RELEASE_WAYPOINT", "ACQUIRE_WAYPOINT", "RELEASE_WAYPOINT");
        assertThat(sink.resources).containsExactly(
                List.of("H1"), List.of("H1"), List.of("H2"), List.of("H2"));
    }
}
