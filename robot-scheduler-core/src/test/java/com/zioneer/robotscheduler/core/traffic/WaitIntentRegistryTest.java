package com.zioneer.robotscheduler.core.traffic;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class WaitIntentRegistryTest {
    // 场景：按旧任务 ID 清除等待意图；预期：不影响同机器人的新任务意图
    @Test
    void clearingOldTaskDoesNotRemoveNewTaskIntent() {
        WaitIntentRegistry registry = new WaitIntentRegistry();
        registry.record("R1", "new-task", List.of("segment"), Set.of("point"));
        registry.clear("R1", "old-task");
        assertThat(registry.snapshot()).hasSize(1);
        registry.clear("R1", "new-task");
        assertThat(registry.snapshot()).isEmpty();
    }

    // 场景：按资源类型匹配阻塞者并排除自己；预期：同名点/段资源不误判，仅返回他人
    @Test
    void blockersUseResourceTypeAndExcludeSelf() {
        var traffic = new InMemoryTrafficRegistry();
        traffic.updateDwell("self", "own-point", null);
        traffic.updateDwell("other", "segment-name", null);
        var registry = new WaitIntentRegistry();
        registry.record("self", List.of("segment-name"), Set.of("own-point"));
        assertThat(registry.blockers("self", traffic.occupationSnapshot())).isEmpty();
        registry.record("self", List.of(), Set.of("segment-name"));
        assertThat(registry.blockers("self", traffic.occupationSnapshot()).get(0).robotCode()).isEqualTo("other");
    }
}
