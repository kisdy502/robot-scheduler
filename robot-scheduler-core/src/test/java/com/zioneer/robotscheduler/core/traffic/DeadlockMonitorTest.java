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

    @Test
    void detect_noConflict_returnsEmpty() {
        registry.tryAcquire("R1", List.of("s1"), java.util.Set.of(), java.time.Duration.ofMinutes(1));
        intents.record("R2", List.of("s9"));

        assertThat(monitor.detect()).isEmpty();
    }

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
