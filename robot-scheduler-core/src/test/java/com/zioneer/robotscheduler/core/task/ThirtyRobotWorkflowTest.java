package com.zioneer.robotscheduler.core.task;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.zioneer.robotscheduler.core.map.MapManager;
import com.zioneer.robotscheduler.core.map.MapMetadataParser;
import com.zioneer.robotscheduler.core.repository.MapArchiveRepository;
import com.zioneer.robotscheduler.core.repository.TaskRecord;
import com.zioneer.robotscheduler.core.traffic.ActiveRouteRegistry;
import com.zioneer.robotscheduler.core.traffic.InMemoryTrafficRegistry;
import com.zioneer.robotscheduler.core.traffic.WaitIntentRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 30 台指定机器人闭环回归；使用真实内核和隔离的 RMS 契约模拟，不代表实车吞吐或安全认证。 */
class ThirtyRobotWorkflowTest {
    private static final int ROBOTS = 30;
    private static final int ROUNDS = 10;

    // 30 条充裕独立路线连续跑十轮，验证移动、外部作业交接、停靠和释放使用权均能收口。
    @Test
    void thirtySelectedRobots_completeTenWorkflowRounds() throws Exception {
        var fleet = new FleetStateMirror();
        var traffic = new InMemoryTrafficRegistry();
        var owners = new TaskAssignmentRegistry();
        var availability = new RobotAvailabilityService(owners, traffic);
        var maps = new MapManager(new MapMetadataParser(), mock(MapArchiveRepository.class));
        maps.activateFromJson(mapJson(), null, null);
        var gateway = mock(RobotGateway.class);
        var commands = new RobotCommandExecutor(gateway);
        var states = new TaskStateMirror();
        var navigations = new AtomicInteger();
        var inFlight = new AtomicInteger();
        var maxInFlight = new AtomicInteger();
        var firstWave = new java.util.concurrent.CyclicBarrier(ROBOTS);
        ApplicationEventPublisher publisher = event -> states.onTaskStateEvent((TaskStateEvent) event);
        var properties = new TaskAssignProperties();
        var engine = new TaskExecutionEngine(commands, traffic, maps, fleet, new SchedulerEventBus(publisher),
                new CostBasedTaskAssigner(fleet, maps, properties, availability), properties,
                new WaitIntentRegistry(), new ActiveRouteRegistry(maps), mock(RobotEventMonitor.class),
                owners, availability);
        when(gateway.navigate(anyString(), anyInt(), anyString(), anyDouble(), anyDouble(), anyDouble(),
                any(), any())).thenAnswer(call -> {
                    int active = inFlight.incrementAndGet();
                    maxInFlight.accumulateAndGet(active, Math::max);
                    int issued = navigations.incrementAndGet();
                    try {
                        // 首批命令全部进入后才回执，避免测试实际退化成三十台逐个执行。
                        if (issued <= ROBOTS) firstWave.await(10, TimeUnit.SECONDS);
                        String robot = call.getArgument(0);
                        int index = Integer.parseInt(robot.substring(1));
                        RobotSnapshot snapshot = fleet.list().stream()
                                .filter(r -> robot.equals(r.getRobotCode())).findFirst().orElseThrow();
                        double x = call.getArgument(3);
                        snapshot.setX(x);
                        snapshot.setY(call.getArgument(4));
                        snapshot.setYaw(call.getArgument(5));
                        snapshot.setUpdatedAtMillis(System.currentTimeMillis());
                        traffic.updateDwell(robot, (x == 5 ? "W" : "H") + index, null);
                        commands.onCompletedCommands(robot, List.of(Map.of(
                                "cmd_id", call.getArgument(1), "status", "COMPLETED")));
                        return RobotCmdResult.SUCCESS;
                    } finally {
                        inFlight.decrementAndGet();
                    }
                });
        for (int i = 0; i < ROBOTS; i++) {
            var robot = new RobotSnapshot();
            robot.setRobotCode("R" + i);
            robot.setFleetName("test");
            robot.setFloorId("F1");
            robot.setMap("F1");
            robot.setX(10);
            robot.setY(i * 10);
            robot.setMode("idle");
            robot.setOnline(true);
            robot.setDispatchable(true);
            robot.setBattery(80.0);
            fleet.upsert(robot);
            traffic.updateDwell(robot.getRobotCode(), "H" + i, null);
        }
        for (int round = 0; round < ROUNDS; round++) {
            int currentRound = round;
            List<String> outbound = new ArrayList<>();
            for (int i = 0; i < ROBOTS; i++) {
                String owner = "flow-" + round + "-" + i;
                owners.assign(owner, "R" + i);
                outbound.add(submit(engine, round, i, owner, "W"));
            }
            awaitCompleted(states, outbound);
            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
                for (int i = 0; i < ROBOTS; i++) {
                    assertThat(owners.canAllocate("R" + i, "flow-" + currentRound + "-" + i)).isTrue();
                }
            });
            for (int i = 0; i < ROBOTS; i++) {
                String owner = "flow-" + round + "-" + i;
                String robot = "R" + i;
                owners.startOperation(owner, "R" + i, "inspection-" + round + "-" + i);
                assertThat(availability.canExecute(fleet.list().stream()
                        .filter(r -> r.getRobotCode().equals(robot)).findFirst().orElseThrow(), owner)).isFalse();
            }
            assertThat(owners.hasExternalOperations()).isTrue();
            List<String> parking = new ArrayList<>();
            for (int i = 0; i < ROBOTS; i++) {
                String owner = "flow-" + round + "-" + i;
                owners.finishOperation(owner, "R" + i, "inspection-" + round + "-" + i);
                parking.add(submit(engine, round, i, owner, "H"));
            }
            awaitCompleted(states, parking);
            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
                for (int i = 0; i < ROBOTS; i++) {
                    assertThat(owners.canAllocate("R" + i, "flow-" + currentRound + "-" + i)).isTrue();
                }
            });
            for (int i = 0; i < ROBOTS; i++) owners.release("flow-" + round + "-" + i, "R" + i);
            assertThat(owners.snapshot()).isEmpty();
            assertThat(owners.hasExternalOperations()).isFalse();
            // 预约释放后物理驻留必须仍在，不能把“任务结束”误断言为“地图上无人”。
            assertThat(traffic.occupationSnapshot()).hasSize(ROBOTS).allSatisfy((robot, resources) -> {
                assertThat(resources.segmentIds()).isEmpty();
                assertThat(resources.waypointIds()).containsExactly("H" + robot.substring(1));
            });
            assertThat(traffic.occupiedWaypoints()).hasSize(ROBOTS);
        }
        assertThat(navigations).hasValue(ROBOTS * ROUNDS * 2);
        assertThat(maxInFlight).hasValue(ROBOTS);
        assertThat(states.list()).hasSize(ROBOTS * ROUNDS * 2)
                .allSatisfy(state -> assertThat(state.status()).isEqualTo("completed"));
        verify(gateway, never()).startActivity(anyString(), anyInt(), anyString(), any(), any());
    }

    // 30 个并发申请只能有一个取得同一段和同一作业点，驻留接续后仍须互斥。
    @Test
    void thirtyConcurrentContenders_shareOneResourceWithoutDoubleGrant() throws Exception {
        var traffic = new InMemoryTrafficRegistry();
        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int round = 0; round < ROUNDS; round++) {
                List<Callable<String>> requests = IntStream.range(0, ROBOTS).mapToObj(i -> (Callable<String>) () ->
                        traffic.tryAcquire("R" + i, List.of("S"), Set.of("W"), Duration.ofMinutes(1))
                                ? "R" + i : null).toList();
                List<String> winners = new ArrayList<>();
                for (var reply : callers.invokeAll(requests, 10, TimeUnit.SECONDS)) {
                    String winner = reply.get();
                    if (winner != null) winners.add(winner);
                }
                assertThat(winners).hasSize(1);
                String winner = winners.getFirst();
                traffic.releaseAllAndDwell(winner, "W");
                assertThat(traffic.tryAcquire("other", List.of("S"), Set.of("W"), Duration.ofMinutes(1)))
                        .isFalse();
                assertThat(traffic.dwellOwner("W")).isEqualTo(winner);
                traffic.updateDwell(winner, null, null);
                assertThat(traffic.occupationSnapshot()).isEmpty();
            }
        }
    }

    private String submit(TaskExecutionEngine engine, int round, int index, String owner, String target) {
        var record = new TaskRecord();
        record.setSchedulerRequestId(round + ":" + index + ":" + target);
        record.setBusinessTaskId(record.getSchedulerRequestId());
        record.setCategory("go_to_place");
        record.setPayload(Map.of("taskflow_id", owner));
        return engine.submit(record, "R" + index,
                new TaskGoal("go_to_place", "F1", target + index, null, null, Map.of()));
    }

    private void awaitCompleted(TaskStateMirror states, List<String> tasks) {
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            for (String task : tasks) assertThat(states.find(task)).isPresent().get()
                    .satisfies(state -> assertThat(state.status()).isEqualTo("completed"));
        });
    }

    private String mapJson() throws Exception {
        List<Map<String, Object>> points = new ArrayList<>();
        List<Map<String, Object>> paths = new ArrayList<>();
        for (int i = 0; i < ROBOTS; i++) {
            points.add(Map.of("id", "W" + i, "type", "workstation", "position", List.of(5, i * 10, 0)));
            points.add(Map.of("id", "H" + i, "type", "parking", "position", List.of(10, i * 10, 0)));
            paths.add(Map.of("id", "P" + i, "from", "H" + i, "to", "W" + i,
                    "waypoints", List.of("H" + i, "W" + i), "isBidirectional", true));
        }
        return new ObjectMapper().writeValueAsString(Map.of(
                "versionInfo", Map.of("version", "test-30", "status", "published"),
                "mapInfo", Map.of("mapName", "ThirtyRobotTest", "floors", List.of(Map.of(
                        "floorId", "F1", "defaultMaxSpeed", 1.0, "waypoints", points, "paths", paths)))));
    }
}
