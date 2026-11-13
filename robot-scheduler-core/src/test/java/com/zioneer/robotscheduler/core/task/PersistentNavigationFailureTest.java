package com.zioneer.robotscheduler.core.task;

import com.zioneer.robotscheduler.core.assign.CostBasedTaskAssigner;
import com.zioneer.robotscheduler.core.assign.TaskAssignProperties;
import com.zioneer.robotscheduler.core.event.SchedulerEventBus;
import com.zioneer.robotscheduler.core.fleet.FleetStateMirror;
import com.zioneer.robotscheduler.core.fleet.RobotAvailabilityService;
import com.zioneer.robotscheduler.core.fleet.RobotCmdResult;
import com.zioneer.robotscheduler.core.fleet.RobotCommandExecutor;
import com.zioneer.robotscheduler.core.fleet.RobotEventMonitor;
import com.zioneer.robotscheduler.core.fleet.RobotGateway;
import com.zioneer.robotscheduler.core.fleet.RobotSnapshot;
import com.zioneer.robotscheduler.core.fleet.TaskAssignmentRegistry;
import com.zioneer.robotscheduler.core.repository.InMemoryRobotRuntimeRepository;
import com.zioneer.robotscheduler.core.repository.TaskRecord;
import com.zioneer.robotscheduler.core.testsupport.RecoveryTestMaps;
import com.zioneer.robotscheduler.core.traffic.ActiveRouteRegistry;
import com.zioneer.robotscheduler.core.traffic.PersistentTrafficRegistry;
import com.zioneer.robotscheduler.core.traffic.WaitIntentRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PersistentNavigationFailureTest {
    // 导航完成回执后若资源释放落库失败，任务仍应发布失败终态，旧资源继续冻结，不能静默丢失任务。
    @Test
    void databaseFailureDuringProgressKeepsResourcesAndPublishesTerminal() {
        var store = spy(new InMemoryRobotRuntimeRepository());
        var maps = RecoveryTestMaps.loaded();
        var traffic = new PersistentTrafficRegistry(store, maps);
        var owners = new TaskAssignmentRegistry(store, maps);
        var availability = new RobotAvailabilityService(owners, traffic);
        var fleet = new FleetStateMirror();
        var robot = new RobotSnapshot();
        robot.setRobotCode("R1");
        robot.setMap("F1");
        robot.setFloorId("F1");
        robot.setOnline(true);
        robot.setDispatchable(true);
        robot.setMode("idle");
        fleet.upsert(robot);
        var gateway = mock(RobotGateway.class);
        var commands = new RobotCommandExecutor(gateway, store);
        when(gateway.navigate(anyString(), anyInt(), anyString(), anyDouble(), anyDouble(), anyDouble(), any(), any()))
                .thenAnswer(call -> {
                    robot.setX(5);
                    robot.setUpdatedAtMillis(System.currentTimeMillis());
                    commands.onCompletedCommands("R1", List.of(Map.of(
                            "cmd_id", call.getArgument(1), "status", "COMPLETED")));
                    doThrow(new IllegalStateException("db unavailable")).when(store).saveTraffic(any());
                    return RobotCmdResult.SUCCESS;
                });
        var states = new TaskStateMirror();
        ApplicationEventPublisher publisher = event -> states.onTaskStateEvent((TaskStateEvent) event);
        var properties = new TaskAssignProperties();
        var engine = new TaskExecutionEngine(commands, traffic, maps, fleet, new SchedulerEventBus(publisher),
                new CostBasedTaskAssigner(fleet, maps, properties, availability), properties,
                new WaitIntentRegistry(), new ActiveRouteRegistry(maps), mock(RobotEventMonitor.class),
                owners, availability);
        var record = new TaskRecord();
        record.setCategory("go_to_place");
        record.setSchedulerRequestId("failed-progress");
        String id = engine.submit(record, "R1", new TaskGoal("go_to_place", "F1", "W0", null, null, Map.of()));
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(states.find(id)).isPresent().get()
                .satisfies(state -> assertThat(state.status()).isEqualTo("failed")));
        assertThat(traffic.isAwaitingStop("R1")).isTrue();
        assertThat(traffic.occupiedWaypoints()).contains("W0");
        assertThat(traffic.tryAcquire("R2", List.of(), java.util.Set.of("W0"), java.time.Duration.ofMinutes(1)))
                .isFalse();
    }
}
