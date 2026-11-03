package com.zioneer.robotscheduler.core.fleet;

import com.zioneer.robotscheduler.core.dispatch.SchedulerHttpException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TaskAssignmentRegistryTest {
    private final TaskAssignmentRegistry registry = new TaskAssignmentRegistry();

    // 同一任务流重复取得同一机器人使用权应幂等，其他任务流或换车请求应被拒绝。
    @Test
    void claimIsExclusiveAndIdempotent() {
        registry.assign("flow1", "R1");
        registry.assign("flow1", "R1");
        assertThatThrownBy(() -> registry.assign("flow2", "R1")).isInstanceOf(SchedulerHttpException.class);
        assertThatThrownBy(() -> registry.assign("flow1", "R2")).isInstanceOf(SchedulerHttpException.class);
        assertThat(registry.canUse("R1", "flow1")).isTrue();
        assertThat(registry.canUse("R1", null)).isFalse();
        assertThat(registry.canUse("R2", "flow1")).isFalse();
    }

    // 传错任务流或机器人不能删除原使用权，正确释放后重复释放不产生变化。
    @Test
    void wrongReleasePreservesClaimAndCorrectReleaseIsIdempotent() {
        registry.assign("flow1", "R1");
        assertThatThrownBy(() -> registry.release("flow1", "R2")).isInstanceOf(SchedulerHttpException.class);
        assertThatThrownBy(() -> registry.release("flow2", "R1")).isInstanceOf(SchedulerHttpException.class);
        assertThat(registry.ownerOf("R1")).isEqualTo("flow1");
        assertThat(registry.release("flow1", "R1")).isTrue();
        assertThat(registry.release("flow1", "R1")).isFalse();
    }

    // 已受理任务阻止其他流程抢占、开始外部作业和释放使用权，任务退出后才允许释放。
    @Test
    void acceptedTaskPreventsClaimByOtherFlowAndPreventsExternalWorkAndRelease() {
        registry.registerTask("task", "R1", "flow1");
        assertThatThrownBy(() -> registry.assign("flow2", "R1")).isInstanceOf(SchedulerHttpException.class);
        registry.assign("flow1", "R1");
        assertThatThrownBy(() -> registry.startOperation("flow1", "R1", "op1"))
                .isInstanceOf(SchedulerHttpException.class);
        assertThatThrownBy(() -> registry.release("flow1", "R1")).isInstanceOf(SchedulerHttpException.class);
        registry.finishTask("task");
        assertThat(registry.release("flow1", "R1")).isTrue();
    }

    // 外部作业期间持有者也不能导航；开始和结束通知可重试，结束后仍保留原使用权。
    @Test
    void externalWorkBlocksEvenOwnerUntilConfirmedFinishedAndKeepsClaim() {
        registry.assign("flow1", "R1");
        var started = registry.startOperation("flow1", "R1", "op1");
        assertThat(registry.startOperation("flow1", "R1", "op1")).isEqualTo(started);
        assertThat(registry.hasExternalOperations()).isTrue();
        assertThat(registry.canExecute("R1", "flow1")).isFalse();
        assertThatThrownBy(() -> registry.release("flow1", "R1")).isInstanceOf(SchedulerHttpException.class);
        assertThatThrownBy(() -> registry.finishOperation("flow1", "R1", "wrong"))
                .isInstanceOf(SchedulerHttpException.class);
        var finished = registry.finishOperation("flow1", "R1", "op1");
        assertThat(registry.finishOperation("flow1", "R1", "op1")).isEqualTo(finished);
        assertThat(registry.startOperation("flow1", "R1", "op1")).isEqualTo(finished);
        assertThat(registry.canExecute("R1", "flow1")).isTrue();
        assertThat(registry.canExecute("R1", "flow2")).isFalse();
        assertThat(registry.ownerOf("R1")).isEqualTo("flow1");
        assertThat(registry.hasExternalOperations()).isFalse();
        assertThat(finished.finishedAt()).isGreaterThanOrEqualTo(finished.startedAt());
    }

    // 旧作业的迟到结束通知不能清除新作业，孤儿任务取消也不能停止当前外部作业。
    @Test
    void lateFinishDoesNotClearNewOperation() {
        registry.assign("flow1", "R1");
        registry.startOperation("flow1", "R1", "old");
        registry.finishOperation("flow1", "R1", "old");
        registry.startOperation("flow1", "R1", "new");
        registry.finishOperation("flow1", "R1", "old");
        assertThat(registry.activeOperation("R1").operationId()).isEqualTo("new");
        assertThatThrownBy(() -> registry.assertCanStopOrphan("R1", "flow1"))
                .isInstanceOf(SchedulerHttpException.class);
    }

    // 自动分配必须遵守使用权约束，同一机器人已有任务登记时不能再次分配。
    @Test
    void automaticAdmissionCannotDoubleAllocateOrIgnoreClaim() {
        registry.assign("flow1", "R1");
        registry.registerTask("a", null, null);
        assertThat(registry.assignTask("a", "R1")).isFalse();
        registry.registerTask("b", null, "flow1");
        assertThat(registry.assignTask("b", "R2")).isFalse();
        assertThat(registry.assignTask("b", "R1")).isTrue();
        registry.registerTask("c", null, "flow1");
        assertThat(registry.assignTask("c", "R1")).isFalse();
    }

    // 同一任务流还有待自动分配任务时不能开始外部作业，移除任务登记后才允许开始。
    @Test
    void pendingAutomaticTaskPreventsItsOwnerStartingExternalWork() {
        registry.assign("flow1", "R1");
        registry.registerTask("pending", null, "flow1");
        assertThatThrownBy(() -> registry.startOperation("flow1", "R1", "op"))
                .isInstanceOf(SchedulerHttpException.class);
        registry.finishTask("pending");
        assertThat(registry.startOperation("flow1", "R1", "op").state()).isEqualTo("running");
    }
}
