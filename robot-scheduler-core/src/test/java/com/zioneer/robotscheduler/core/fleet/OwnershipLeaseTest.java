package com.zioneer.robotscheduler.core.fleet;

import com.zioneer.robotscheduler.core.repository.InMemoryRobotRuntimeRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用权租约（孤儿回收判据）与强制释放测试。
 */
class OwnershipLeaseTest {

    private final TaskAssignmentRegistry registry = new TaskAssignmentRegistry();

    // 心跳超时 + 无活动任务 → 可回收（孤儿判定判据齐备）
    @Test
    void orphanConditions_heartbeatStaleAndNoTasks() throws InterruptedException {
        registry.assign("tf-1", "R1");
        Long since = registry.ownerSince("tf-1");
        Long beat = registry.ownerHeartbeat("tf-1");
        assertThat(since).isNotNull();
        assertThat(beat).isNotNull();

        // 任务在身 → hasActiveTasks=true（活证明，不可回收）
        registry.registerTask("task-a", "R1", "tf-1");
        assertThat(registry.hasActiveTasks("tf-1")).isTrue();

        // 任务终态清理后 → 无活动任务（孤儿可回收形态）
        registry.finishTask("task-a");
        assertThat(registry.hasActiveTasks("tf-1")).isFalse();

        // 心跳刷新：业务流活动续期
        Thread.sleep(5);
        registry.touch("tf-1");
        assertThat(registry.ownerHeartbeat("tf-1")).isGreaterThan(beat);
    }

    // 孤儿回收：占用清除 + 可被新 taskflow 抢占
    @Test
    void orphanRelease_freesRobotForNewOwner() {
        registry.assign("tf-orphan", "R1");
        assertThat(registry.orphanRelease("R1", "tf-orphan", "心跳超时 180s 且无活动任务")).isTrue();
        assertThat(registry.ownerOf("R1")).isNull();
        registry.assign("tf-new", "R1");
        assertThat(registry.ownerOf("R1")).isEqualTo("tf-new");
        // 重复回收他人（owner 不匹配）→ false 不误伤
        assertThat(registry.orphanRelease("R1", "tf-orphan", "stale")).isFalse();
    }

    // 强制释放：有活动任务拒绝（409 门禁判据），无任务放行
    @Test
    void forceRelease_blockedByActiveTasks_thenSucceeds() {
        registry.assign("tf-1", "R1");
        registry.registerTask("task-x", "R1", "tf-1");
        assertThat(registry.forceRelease("R1", "紧急征用")).isFalse();

        registry.finishTask("task-x");
        assertThat(registry.forceRelease("R1", "紧急征用")).isTrue();
        assertThat(registry.ownerOf("R1")).isNull();
        // 无占用时强释幂等 false
        assertThat(registry.forceRelease("R1", "无占用")).isFalse();
    }

    // 正常释放后心跳/取得时间一并清除（不留脏租约数据）
    @Test
    void release_clearsLeaseTimes() {
        registry.assign("tf-1", "R1");
        assertThat(registry.release("tf-1", "R1")).isTrue();
        assertThat(registry.ownerHeartbeat("tf-1")).isNull();
        assertThat(registry.ownerSince("tf-1")).isNull();
    }
}
