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

    // 场景：retainUntilStopped 保留占用后调用各类释放
    // 预期：确认停车前释放均无效；markStopAccepted 后才切换为实际驻留段并放出原资源
    @Test
    void retainedTraffic_isNotReleasedUntilStopIsConfirmed() {
        InMemoryTrafficRegistry registry = new InMemoryTrafficRegistry();
        registry.tryAcquire("R1", List.of("remaining-route"), Set.of("B"), Duration.ofMinutes(1));
        registry.updateDwell("R1", "A", null);
        assertThat(registry.retainUntilStopped("R1", "navigation timeout")).isTrue();
        registry.releaseAll("R1");
        registry.releaseSegments("R1", List.of("remaining-route"));
        registry.releaseAllAndDwell("R1", "B");
        registry.updateDwell("R1", null, null);
        assertThat(registry.occupiedSegments()).contains("remaining-route");
        assertThat(registry.occupiedWaypoints()).contains("A", "B");
        assertThat(registry.tryAcquire("R1", List.of("next"), Set.of(), Duration.ofMinutes(1))).isFalse();
        assertThat(registry.tryAcquire("R2", List.of("remaining-route"), Set.of(), Duration.ofMinutes(1))).isFalse();
        assertThat(registry.confirmStopped("R1", null, "actual-segment")).isFalse();
        registry.markStopAccepted("R1");
        assertThat(registry.confirmStopped("R1", null, "actual-segment")).isTrue();
        assertThat(registry.isAwaitingStop("R1")).isFalse();
        assertThat(registry.occupiedSegments()).containsExactly("actual-segment");
        assertThat(registry.occupiedWaypoints()).isEmpty();
        assertThat(registry.tryAcquire("R2", List.of("actual-segment"), Set.of(), Duration.ofMinutes(1))).isFalse();
        assertThat(registry.tryAcquire("R2", List.of("remaining-route"), Set.of("B"), Duration.ofMinutes(1))).isTrue();
    }

    // 场景：无任何占用时仅登记停车保留；预期：占用快照仍可见（地图切换守卫可感知）
    @Test
    void stopHoldWithoutRoute_remainsVisibleToMapSwitchGuard() {
        InMemoryTrafficRegistry registry = new InMemoryTrafficRegistry();
        registry.retainUntilStopped("R1", "cancel action");
        assertThat(registry.occupationSnapshot()).containsKey("R1");
    }

    // 场景：到达释放+驻留的原子性（幽灵窗口回归）；预期：调用返回瞬间其他机器人拿不到该点租约
    @Test
    void releaseAllAndDwell_blocksOthersImmediately_noGhostWindow() {
        // 幽灵窗口回归：到达释放+驻留必须原子——调用返回瞬间其他机器人拿不到该点租约
        InMemoryTrafficRegistry registry = new InMemoryTrafficRegistry();
        assertThat(registry.tryAcquire("R001", List.of("PATH_1#P1-P2"), Set.of("P2"), Duration.ofMinutes(1)))
                .isTrue();

        registry.releaseAllAndDwell("R001", "P2");

        // 同一瞬间其他机器人对 P2 的申请必须失败（旧实现：releaseAll 后有空档可成功）
        assertThat(registry.tryAcquire("R002", List.of(), Set.of("P2"), Duration.ofMinutes(1))).isFalse();
        assertThat(registry.occupiedWaypoints()).contains("P2");
    }

    // 场景：两台车同点驻留（叠车已成事实）；预期：双方占用均可见并产出 DWELL_CONFLICT 审计
    @Test
    void releaseAllAndDwell_conflictKeepsBothVisible_andAudits() {
        // C 防御：两台车同点驻留（叠车已成事实）→ 双方占用都可见（过阻断方向安全）+ 冲突审计
        RecordingSink sink = new RecordingSink();
        InMemoryTrafficRegistry registry = new InMemoryTrafficRegistry();
        registry.setEventSink(sink);

        registry.releaseAllAndDwell("R001", "P9");
        registry.releaseAllAndDwell("R002", "P9");

        assertThat(registry.occupiedWaypoints()).contains("P9");
        var snapshot = registry.occupationSnapshot();
        assertThat(snapshot.get("R001").waypointIds()).contains("P9");
        assertThat(snapshot.get("R002").waypointIds()).contains("P9");
        assertThat(sink.actions).contains("DWELL_CONFLICT");
    }

    // 场景：任务占用、渐进释放与全量释放；预期：段与点的审计事件分开成对发出
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

    // 场景：无图模式仅占用目标点；预期：只发 ACQUIRE_WAYPOINT 事件
    @Test
    void waypointOnlyAcquisition_emitsWaypointEventOnly() {
        RecordingSink sink = new RecordingSink();
        InMemoryTrafficRegistry registry = new InMemoryTrafficRegistry();
        registry.setEventSink(sink);

        // 无图模式：只占用目标点
        registry.tryAcquire("R001", List.of(), Set.of("W1"), Duration.ofMinutes(1));

        assertThat(sink.actions).containsExactly("ACQUIRE_WAYPOINT");
    }

    // 场景：任务占用与驻留点合并生成快照；预期：不抛异常且两类资源均在快照中
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

    // 场景：静止机器人仅有驻留点；预期：驻留点在快照与占用点视图中可见
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

    // 场景：规划排除自身占用的视图；预期：Excluding 视图仅含他人资源，全量视图不受影响
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

    // 场景：驻留变化（停车/换点/离开）；预期：按释旧占新成对审计，无变化不审计
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
