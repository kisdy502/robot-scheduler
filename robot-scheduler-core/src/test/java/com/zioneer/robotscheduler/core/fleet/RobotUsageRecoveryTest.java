package com.zioneer.robotscheduler.core.fleet;

import com.zioneer.robotscheduler.core.dispatch.SchedulerHttpException;
import com.zioneer.robotscheduler.core.repository.InMemoryRobotRuntimeRepository;
import com.zioneer.robotscheduler.core.testsupport.RecoveryTestMaps;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RobotUsageRecoveryTest {
    // 外部作业期间排队的移动不打执行标记，重启不能把排队误判为残留导航而停止业务动作。
    @Test
    void queuedMoveDuringExternalOperationDoesNotCreateExecutionMarker() {
        var store = new InMemoryRobotRuntimeRepository();
        var registry = new TaskAssignmentRegistry(store, RecoveryTestMaps.loaded());
        registry.assign("flow", "R1");
        registry.startOperation("flow", "R1", "photo");
        registry.registerTask("queued", "R1", "flow");
        assertThat(registry.beginExecution("queued")).isFalse();
        assertThat(store.findTraffic()).isEmpty();
        var restored = new TaskAssignmentRegistry(store, RecoveryTestMaps.loaded());
        assertThat(restored.activeOperation("R1").operationId()).isEqualTo("photo");
    }

    // 仅执行旧动作、没有未来路线时，也要在重启后停止对账；旧任务退出不能清掉新任务的执行标记。
    @Test
    void activeActionWithoutRouteSurvivesAsRecoveryStopAndUsesTaskIdentity() {
        var store = new InMemoryRobotRuntimeRepository();
        var maps = RecoveryTestMaps.loaded();
        var registry = new TaskAssignmentRegistry(store, maps);
        registry.registerTask("first", "R1", null);
        assertThat(registry.beginExecution("first")).isTrue();
        registry.registerTask("second", "R1", null);
        assertThat(registry.beginExecution("second")).isTrue();
        registry.finishTask("first");
        var restored = new com.zioneer.robotscheduler.core.traffic.PersistentTrafficRegistry(store, maps);
        assertThat(restored.isAwaitingStop("R1")).isTrue();
        restored.markStopAccepted("R1");
        restored.confirmStopped("R1", "H0", null);
        assertThat(new com.zioneer.robotscheduler.core.traffic.PersistentTrafficRegistry(store, maps)
                .isAwaitingStop("R1")).isFalse();
    }

    // 执行结束落库失败先阻止业务接续，数据库恢复后通过后台收口移除登记，避免永久占用内存任务。
    @Test
    void executionFinalizationWriteFailureRetriesBeforeAllowingExternalWork() {
        var store = spy(new InMemoryRobotRuntimeRepository());
        var registry = new TaskAssignmentRegistry(store, RecoveryTestMaps.loaded());
        registry.assign("flow", "R1");
        registry.registerTask("task", "R1", "flow");
        registry.beginExecution("task");
        doThrow(new IllegalStateException("db unavailable")).when(store).finishSchedulerExecution("R1", "task");
        assertThatThrownBy(() -> registry.finishTask("task")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.startOperation("flow", "R1", "photo"))
                .isInstanceOf(SchedulerHttpException.class);
        doCallRealMethod().when(store).finishSchedulerExecution("R1", "task");
        registry.retryFinishingTasks();
        assertThat(registry.startOperation("flow", "R1", "photo").state()).isEqualTo("running");
    }
    // 进程重建后仍保留持有者与进行中的外部作业，重复开始通知返回原开始时间。
    @Test
    void restartRestoresOwnerAndActiveOperation() {
        var store = new InMemoryRobotRuntimeRepository();
        var before = new TaskAssignmentRegistry(store, RecoveryTestMaps.loaded());
        before.assign("flow", "R1");
        var started = before.startOperation("flow", "R1", "photo");
        var restarted = new TaskAssignmentRegistry(store, RecoveryTestMaps.loaded());
        assertThat(restarted.ownerOf("R1")).isEqualTo("flow");
        assertThat(restarted.startOperation("flow", "R1", "photo")).isEqualTo(started);
        assertThat(restarted.canExecute("R1", "flow")).isFalse();
        assertThatThrownBy(() -> restarted.release("flow", "R1")).isInstanceOf(SchedulerHttpException.class);
    }

    // 结束通知与使用权释放各自持久化，重启后不会复活已经结束的作业或已释放的使用权。
    @Test
    void finishedOperationAndReleasedOwnershipStayFinishedAfterRestart() {
        var store = new InMemoryRobotRuntimeRepository();
        var before = new TaskAssignmentRegistry(store, RecoveryTestMaps.loaded());
        before.assign("flow", "R1");
        before.startOperation("flow", "R1", "photo");
        var ended = before.finishOperation("flow", "R1", "photo");
        var restarted = new TaskAssignmentRegistry(store, RecoveryTestMaps.loaded());
        assertThat(restarted.finishOperation("flow", "R1", "photo")).isEqualTo(ended);
        assertThat(restarted.activeOperation("R1")).isNull();
        restarted.release("flow", "R1");
        assertThat(new TaskAssignmentRegistry(store, RecoveryTestMaps.loaded()).snapshot()).isEmpty();
    }

    // 写盘失败不能对外确认取得使用权，内存也不能留下未持久化的持有者。
    @Test
    void failedClaimWriteDoesNotGrantOwnership() {
        var store = spy(new InMemoryRobotRuntimeRepository());
        var registry = new TaskAssignmentRegistry(store, RecoveryTestMaps.loaded());
        doThrow(new IllegalStateException("db unavailable")).when(store).saveUsage(any());
        assertThatThrownBy(() -> registry.assign("flow", "R1")).isInstanceOf(SchedulerHttpException.class)
                .satisfies(error -> assertThat(((SchedulerHttpException) error).getHttpStatus()).isEqualTo(503));
        assertThat(registry.snapshot()).isEmpty();
    }

    // 结束通知落库失败时继续保留外部作业占用，不能先释放再补写数据库。
    @Test
    void failedFinishWriteKeepsOperationBusy() {
        var store = spy(new InMemoryRobotRuntimeRepository());
        var registry = new TaskAssignmentRegistry(store, RecoveryTestMaps.loaded());
        registry.assign("flow", "R1");
        registry.startOperation("flow", "R1", "photo");
        doThrow(new IllegalStateException("db unavailable")).when(store).saveUsage(any());
        assertThatThrownBy(() -> registry.finishOperation("flow", "R1", "photo"))
                .isInstanceOf(SchedulerHttpException.class);
        assertThat(registry.canExecute("R1", "flow")).isFalse();
        assertThat(new TaskAssignmentRegistry(store, RecoveryTestMaps.loaded()).activeOperation("R1")).isNotNull();
    }
}
