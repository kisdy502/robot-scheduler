package com.zioneer.robotscheduler.core.fleet;

import com.zioneer.robotscheduler.core.charger.ChargerPoolPort;
import com.zioneer.robotscheduler.core.repository.InMemoryRobotRuntimeRepository;
import com.zioneer.robotscheduler.core.task.TaskStateMirror;
import com.zioneer.robotscheduler.core.testsupport.RecoveryTestMaps;
import com.zioneer.robotscheduler.core.traffic.PersistentTrafficRegistry;
import com.zioneer.robotscheduler.core.traffic.ActiveRouteRegistry;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FleetRuntimeRecoveryTest {
    // 无法提交命令序号时不得向 RMS 下发，停止请求也只能返回 RETRY，不能伪报成功。
    @Test
    void sequenceWriteFailurePreventsCommandDelivery() {
        var repository = mock(com.zioneer.robotscheduler.core.repository.RobotRuntimeRepository.class);
        when(repository.nextCommandId("R1")).thenThrow(new IllegalStateException("db unavailable"));
        var gateway = mock(RobotGateway.class);
        var executor = new RobotCommandExecutor(gateway, repository);
        assertThatThrownBy(() -> executor.submitNavigation("R1", "F1", 5, 0, 0, null, Map.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(executor.stop("R1", 0, null)).isEqualTo(RobotCmdResult.RETRY);
        verifyNoInteractions(gateway);
    }
    // 三十台重启：十五台导航先停止并确认三帧，十五台外部作业原样保留，不自动重放导航或停止业务动作。
    @Test
    void thirtyRobotsRecoverNavigationAndExternalWorkSeparately() {
        var store = new InMemoryRobotRuntimeRepository();
        var oldMaps = RecoveryTestMaps.archived("v1", 30);
        oldMaps.loadFromArchive();
        var oldTraffic = new PersistentTrafficRegistry(store, oldMaps);
        var oldOwners = new TaskAssignmentRegistry(store, oldMaps);
        for (int i = 0; i < 30; i++) {
            oldOwners.assign("flow" + i, "R" + i);
            oldTraffic.updateDwell("R" + i, i < 15 ? "H" + i : "W" + i, null);
            if (i < 15) oldTraffic.tryAcquire("R" + i, List.of("P" + i + "#H" + i + "-W" + i),
                    Set.of("H" + i, "W" + i), Duration.ofMinutes(1));
            else oldOwners.startOperation("flow" + i, "R" + i, "photo" + i);
        }
        var maps = RecoveryTestMaps.archived("v1", 30);
        var owners = new TaskAssignmentRegistry(store, maps);
        var traffic = new PersistentTrafficRegistry(store, maps);
        maps.setActivationBlocked(() -> owners.hasExternalOperations() || !traffic.occupationSnapshot().isEmpty());
        assertThat(maps.loadFromArchive()).isTrue();
        var fleet = new FleetStateMirror();
        var gateway = mock(RobotGateway.class);
        when(gateway.stop(anyString(), anyInt(), anyInt(), any())).thenReturn(RobotCmdResult.SUCCESS);
        var chargers = mock(ChargerPoolPort.class);
        when(chargers.list(any())).thenReturn(List.of());
        var manager = new FleetManager(mock(RobotStateSource.class), fleet, new TaskStateMirror(), maps, traffic,
                new ActiveRouteRegistry(maps), mock(RobotEventMonitor.class), new FleetPollingProperties(),
                new RobotCommandExecutor(gateway, store), chargers, new RobotAvailabilityService(owners, traffic),
                new ExternalActivityMirror());
        manager.onStateTick(frames());
        for (int i = 0; i < 15; i++) {
            assertThat(traffic.isAwaitingStop("R" + i)).isTrue();
            assertThat(traffic.occupiedWaypoints()).contains("W" + i);
        }
        manager.onStateTick(frames());
        manager.onStateTick(frames());
        assertThat(traffic.isAwaitingStop("R0")).isTrue();
        manager.onStateTick(frames());
        for (int i = 0; i < 15; i++) {
            assertThat(traffic.isAwaitingStop("R" + i)).isFalse();
            assertThat(traffic.dwellOwner("H" + i)).isEqualTo("R" + i);
            assertThat(traffic.occupiedWaypoints()).doesNotContain("W" + i);
        }
        for (int i = 15; i < 30; i++) {
            assertThat(owners.activeOperation("R" + i).operationId()).isEqualTo("photo" + i);
            assertThat(traffic.dwellOwner("W" + i)).isEqualTo("R" + i);
            assertThat(owners.canExecute("R" + i, "flow" + i)).isFalse();
            verify(gateway, never()).stop(eq("R" + i), anyInt(), anyInt(), any());
        }
        verify(gateway, times(15)).stop(anyString(), anyInt(), anyInt(), any());
        verify(gateway, never()).navigate(anyString(), anyInt(), anyString(), anyDouble(), anyDouble(),
                anyDouble(), any(), any());
        assertThat(owners.snapshot()).hasSize(30);
    }

    // 新进程的命令序号接续旧正本，旧序号回执不能结束新命令。
    @Test
    void commandSequenceSurvivesExecutorRestartAndOldReceiptIsIgnored() {
        var store = new InMemoryRobotRuntimeRepository();
        var gateway = mock(RobotGateway.class);
        when(gateway.navigate(anyString(), anyInt(), anyString(), anyDouble(), anyDouble(), anyDouble(), any(), any()))
                .thenReturn(RobotCmdResult.SUCCESS);
        var before = new RobotCommandExecutor(gateway, store);
        var old = before.submitNavigation("R1", "F1", 0, 0, 0, null, Map.of());
        var restarted = new RobotCommandExecutor(gateway, store);
        var next = restarted.submitNavigation("R1", "F1", 5, 0, 0, null, Map.of());
        assertThat(next.cmdId()).isGreaterThan(old.cmdId());
        assertThat(restarted.onCompletedCommands("R1", List.of(Map.of(
                "cmd_id", old.cmdId(), "status", "COMPLETED")))).isFalse();
        assertThat(next.future().isDone()).isFalse();
        restarted.onCompletedCommands("R1", List.of(Map.of("cmd_id", next.cmdId(), "status", "COMPLETED")));
        assertThat(next.future().join().completed()).isTrue();
    }

    private List<RobotSnapshot> frames() {
        return IntStream.range(0, 30).mapToObj(i -> {
            var robot = new RobotSnapshot();
            robot.setRobotCode("R" + i);
            robot.setMap("F1");
            robot.setX(i < 15 ? 0 : 5);
            robot.setY(i * 10);
            robot.setBattery(80.0);
            return robot;
        }).toList();
    }
}
