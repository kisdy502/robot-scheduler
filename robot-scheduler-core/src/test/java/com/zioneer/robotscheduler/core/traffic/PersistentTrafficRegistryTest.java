package com.zioneer.robotscheduler.core.traffic;

import com.zioneer.robotscheduler.core.map.MapActivationConflictException;
import com.zioneer.robotscheduler.core.repository.InMemoryRobotRuntimeRepository;
import com.zioneer.robotscheduler.core.testsupport.RecoveryTestMaps;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PersistentTrafficRegistryTest {
    // 提交实际成功但响应断开时不能在本进程或重启后放行后车，须重新停止确认再收缩。
    @Test
    void lostCommitAcknowledgementStillBlocksResourceBeforeAndAfterRestart() {
        var store = spy(new InMemoryRobotRuntimeRepository());
        var traffic = new PersistentTrafficRegistry(store, RecoveryTestMaps.loaded());
        doAnswer(call -> {
            call.callRealMethod();
            throw new IllegalStateException("connection lost after commit");
        }).when(store).saveTraffic(any());
        assertThatThrownBy(() -> traffic.tryAcquire("R1", List.of("S"), Set.of("W0"), Duration.ofMinutes(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(traffic.tryAcquire("R2", List.of("S"), Set.of("W0"), Duration.ofMinutes(1))).isFalse();
        var restarted = new PersistentTrafficRegistry(store, RecoveryTestMaps.loaded());
        assertThat(restarted.isAwaitingStop("R1")).isTrue();
        assertThat(restarted.tryAcquire("R2", List.of("S"), Set.of("W0"), Duration.ofMinutes(1))).isFalse();
        doCallRealMethod().when(store).saveTraffic(any());
        restarted.markStopAccepted("R1");
        restarted.confirmStopped("R1", "H0", null);
        assertThat(restarted.tryAcquire("R2", List.of("S"), Set.of("W0"), Duration.ofMinutes(1))).isTrue();
    }
    // 重启不释放旧路线，也不沿用旧 stop 接受标记；必须重新确认停止才收缩到实际驻留。
    @Test
    void restartKeepsRouteUntilFreshStopConfirmation() {
        var store = new InMemoryRobotRuntimeRepository();
        var traffic = new PersistentTrafficRegistry(store, RecoveryTestMaps.loaded());
        traffic.tryAcquire("R1", List.of("P0#H0-W0"), Set.of("H0", "W0"), Duration.ofMinutes(1));
        traffic.retainUntilStopped("R1", "lost");
        traffic.markStopAccepted("R1");
        var restarted = new PersistentTrafficRegistry(store, RecoveryTestMaps.loaded());
        assertThat(restarted.isAwaitingStop("R1")).isTrue();
        assertThat(restarted.isStopAccepted("R1")).isFalse();
        assertThat(restarted.confirmStopped("R1", "H0", null)).isFalse();
        assertThat(restarted.tryAcquire("R2", List.of(), Set.of("W0"), Duration.ofMinutes(1))).isFalse();
        restarted.markStopAccepted("R1");
        assertThat(restarted.confirmStopped("R1", "H0", null)).isTrue();
        assertThat(restarted.occupiedSegments()).isEmpty();
        assertThat(restarted.occupiedWaypoints()).containsExactly("H0");
        assertThat(new PersistentTrafficRegistry(store, RecoveryTestMaps.loaded()).dwellOwner("H0")).isEqualTo("R1");
    }

    // 只有物理驻留的机器人重启后仍挡住点位，不因没有运行任务而清空或主动停止外部作业。
    @Test
    void dwellSurvivesRestartWithoutCreatingNavigationStop() {
        var store = new InMemoryRobotRuntimeRepository();
        var traffic = new PersistentTrafficRegistry(store, RecoveryTestMaps.loaded());
        traffic.updateDwell("R1", "W0", null);
        var restarted = new PersistentTrafficRegistry(store, RecoveryTestMaps.loaded());
        assertThat(restarted.dwellOwner("W0")).isEqualTo("R1");
        assertThat(restarted.isAwaitingStop("R1")).isFalse();
        assertThat(restarted.tryAcquire("R2", List.of(), Set.of("W0"), Duration.ofMinutes(1))).isFalse();
    }

    // 占用恢复允许加载同版本初始地图，但不能加载另一版本解释旧资源。
    @Test
    void startupOnlyLoadsTheMapBoundToRecoveredResources() {
        var store = new InMemoryRobotRuntimeRepository();
        new PersistentTrafficRegistry(store, RecoveryTestMaps.loaded()).updateDwell("R1", "W0", null);
        var same = RecoveryTestMaps.archived("v1", 1);
        var recovered = new PersistentTrafficRegistry(store, same);
        same.setActivationBlocked(() -> !recovered.occupationSnapshot().isEmpty());
        assertThat(same.loadFromArchive()).isTrue();
        var wrong = RecoveryTestMaps.archived("v2", 1);
        new PersistentTrafficRegistry(store, wrong);
        assertThatThrownBy(wrong::loadFromArchive).isInstanceOf(MapActivationConflictException.class);
    }

    // 新预约写盘失败不能返回放行；提交结果不确定时保守隔离候选资源，避免后车在确认前进入。
    @Test
    void failedAcquireWriteDoesNotGrantAndQuarantinesUncertainResources() {
        var store = spy(new InMemoryRobotRuntimeRepository());
        var traffic = new PersistentTrafficRegistry(store, RecoveryTestMaps.loaded());
        doThrow(new IllegalStateException("db unavailable")).when(store).saveTraffic(any());
        assertThatThrownBy(() -> traffic.tryAcquire("R1", List.of("S"), Set.of("W0"), Duration.ofMinutes(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(traffic.isAwaitingStop("R1")).isTrue();
        assertThat(traffic.occupiedWaypoints()).contains("W0");
        assertThat(traffic.tryAcquire("R2", List.of(), Set.of("W0"), Duration.ofMinutes(1))).isFalse();
        assertThat(store.findTraffic()).isEmpty();
    }

    // 释放预约写盘失败时继续冻结旧资源，重启也必须恢复为待停止确认，不能提前腾空。
    @Test
    void failedReleasePreservesOldReservationAndFreezesRobot() {
        var store = spy(new InMemoryRobotRuntimeRepository());
        var traffic = new PersistentTrafficRegistry(store, RecoveryTestMaps.loaded());
        traffic.tryAcquire("R1", List.of("S"), Set.of("W0"), Duration.ofMinutes(1));
        doThrow(new IllegalStateException("db unavailable")).when(store).saveTraffic(any());
        assertThatThrownBy(() -> traffic.releaseAll("R1")).isInstanceOf(IllegalStateException.class);
        assertThat(traffic.isAwaitingStop("R1")).isTrue();
        assertThat(traffic.occupiedSegments()).contains("S");
        assertThat(new PersistentTrafficRegistry(store, RecoveryTestMaps.loaded()).isAwaitingStop("R1")).isTrue();
    }

    // 静止位置帧不重复写正本，失败回滚的变更也不能被审计为已经成功放行。
    @Test
    void unchangedDwellSkipsWriteAndFailedChangeDoesNotEmitCommittedAudit() {
        var store = spy(new InMemoryRobotRuntimeRepository());
        var traffic = new PersistentTrafficRegistry(store, RecoveryTestMaps.loaded());
        traffic.updateDwell("R1", "H0", null);
        clearInvocations(store);
        traffic.updateDwell("R1", "H0", null);
        verify(store, never()).saveTraffic(any());
        TrafficEventSink sink = mock(TrafficEventSink.class);
        traffic.setEventSink(sink);
        doThrow(new IllegalStateException("db unavailable")).when(store).saveTraffic(any());
        assertThatThrownBy(() -> traffic.updateDwell("R1", "W0", null)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(sink);
        assertThat(traffic.dwellOwner("H0")).isEqualTo("R1");
    }
}
