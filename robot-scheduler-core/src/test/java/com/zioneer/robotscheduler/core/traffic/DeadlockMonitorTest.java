package com.zioneer.robotscheduler.core.traffic;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 死锁监控测试：等待意图 + 占用图 → 环路检测。
 */
class DeadlockMonitorTest {

    private final InMemoryTrafficRegistry registry = new InMemoryTrafficRegistry();
    private final WaitIntentRegistry intents = new WaitIntentRegistry();
    private final DeadlockMonitor monitor = new DeadlockMonitor(intents, registry);

    // 场景：20 台机器人排队使用 9 个工位（正常排队）；预期：不检出死锁，工位释放后相关等待随之解除
    @Test
    void twentyRobotsNineWorkstations_normalQueueIsNotDeadlock() {
        for (int i = 0; i < 9; i++) registry.updateDwell("worker-" + i, "W" + i, null);
        for (int i = 9; i < 20; i++) {
            registry.updateDwell("waiting-" + i, "H" + i, null);
            intents.record("waiting-" + i, List.of(), java.util.Set.of("W" + (i % 9)));
        }
        assertThat(monitor.waitingFor()).hasSize(11);
        assertThat(monitor.detect()).isEmpty();
        registry.updateDwell("worker-0", "free-parking", null);
        assertThat(monitor.waitingFor()).doesNotContainKeys("waiting-9", "waiting-18");
    }

    // 场景：离站机器人被等待其工位的进站机器人挡住；预期：检出双向等待环
    @Test
    void departureBlockedByRobotWaitingForItsWorkstation_isDetected() {
        registry.updateDwell("leaving-workstation", "W8", null);
        registry.updateDwell("entering-workstation", "P36", null);
        intents.record("leaving-workstation", List.of(), java.util.Set.of("P36"));
        intents.record("entering-workstation", List.of(), java.util.Set.of("W8"));
        assertThat(monitor.detect()).containsExactly(List.of("entering-workstation", "leaving-workstation"));
    }

    // 场景：20 台机器人首尾相接等待成环；预期：不受深度限制检出唯一 20 节点环
    @Test
    void twentyRobotCycle_isDetectedOnce_withoutDepthLimit() {
        for (int i = 0; i < 20; i++) {
            registry.updateDwell("R%02d".formatted(i), "P" + i, null);
            intents.record("R%02d".formatted(i), List.of(), java.util.Set.of("P" + ((i + 1) % 20)));
        }
        assertThat(monitor.detect()).hasSize(1);
        assertThat(monitor.detect().get(0)).hasSize(20);
    }

    // 场景：驻留点多占用者并存且等待者被段占用者阻塞；预期：等待集含全部占用者，占用者释放后环消失
    @Test
    void allDwellOwnersAreIncluded_andReleasedOwnersDisappear() {
        registry.updateDwell("A", "P", null);
        registry.updateDwell("B", "P", null);
        registry.tryAcquire("C", List.of("segment-C"), java.util.Set.of(), java.time.Duration.ofMinutes(1));
        intents.record("C", List.of(), java.util.Set.of("P"));
        intents.record("B", List.of("segment-C"));
        assertThat(monitor.waitingFor().get("C")).containsExactly("A", "B");
        assertThat(monitor.detect()).containsExactly(List.of("B", "C"));
        registry.updateDwell("B", null, null);
        assertThat(monitor.detect()).isEmpty();
    }

    // 场景：两台机器人互等对方占用的资源；预期：检出 R1-R2 环
    @Test
    void detect_mutualWaiting_findsCycle() {
        // R1 占 s1 等 s2；R2 占 s2 等 s1 → 环
        registry.tryAcquire("R1", List.of("s1"), java.util.Set.of(), java.time.Duration.ofMinutes(1));
        registry.tryAcquire("R2", List.of("s2"), java.util.Set.of(), java.time.Duration.ofMinutes(1));
        intents.record("R1", List.of("s2"));
        intents.record("R2", List.of("s1"));

        List<List<String>> cycles = monitor.detect();

        assertThat(cycles).isNotEmpty();
        assertThat(cycles.get(0)).containsExactlyInAnyOrder("R1", "R2");
    }

    // 场景：占用与等待无交集；预期：检测结果为空
    @Test
    void detect_noConflict_returnsEmpty() {
        registry.tryAcquire("R1", List.of("s1"), java.util.Set.of(), java.time.Duration.ofMinutes(1));
        intents.record("R2", List.of("s9"));

        assertThat(monitor.detect()).isEmpty();
    }

    // 场景：三台机器人循环等待（R1→R2→R3→R1）；预期：检出三方环
    @Test
    void detect_threeWayCycle_detected() {
        registry.tryAcquire("R1", List.of("s1"), java.util.Set.of(), java.time.Duration.ofMinutes(1));
        registry.tryAcquire("R2", List.of("s2"), java.util.Set.of(), java.time.Duration.ofMinutes(1));
        registry.tryAcquire("R3", List.of("s3"), java.util.Set.of(), java.time.Duration.ofMinutes(1));
        intents.record("R1", List.of("s2"));
        intents.record("R2", List.of("s3"));
        intents.record("R3", List.of("s1"));

        List<List<String>> cycles = monitor.detect();

        assertThat(cycles).isNotEmpty();
        assertThat(cycles.get(0)).containsExactlyInAnyOrder("R1", "R2", "R3");
    }

    // 场景：等待意图已过期（超 2 分钟）；预期：过期意图被忽略，不检出死锁
    @Test
    void detect_staleIntentIgnored() throws Exception {
        registry.tryAcquire("R1", List.of("s1"), java.util.Set.of(), java.time.Duration.ofMinutes(1));
        registry.tryAcquire("R2", List.of("s2"), java.util.Set.of(), java.time.Duration.ofMinutes(1));
        intents.record("R1", List.of("s2"));
        intents.record("R2", List.of("s1"));
        // 等待意图过期（>2 分钟）
        java.lang.reflect.Field field = WaitIntentRegistry.class.getDeclaredField("intentsByRobot");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, WaitIntentRegistry.WaitIntent> map =
                (java.util.Map<String, WaitIntentRegistry.WaitIntent>) field.get(intents);
        map.replaceAll((key, value) -> new WaitIntentRegistry.WaitIntent(value.robotCode(),
                value.segmentIds(), System.currentTimeMillis() - 121_000));

        assertThat(monitor.detect()).isEmpty();
    }
}
