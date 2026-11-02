package com.zioneer.robotscheduler.core.task;

import com.zioneer.robotscheduler.core.event.SchedulerEventBus;
import com.zioneer.robotscheduler.core.fleet.FleetStateMirror;
import com.zioneer.robotscheduler.core.fleet.RobotCmdResult;
import com.zioneer.robotscheduler.core.fleet.RobotCommandExecutor;
import com.zioneer.robotscheduler.core.fleet.RobotGateway;
import com.zioneer.robotscheduler.core.map.MapManager;
import com.zioneer.robotscheduler.core.map.MapMetadataParser;
import com.zioneer.robotscheduler.core.repository.MapArchiveRepository;
import com.zioneer.robotscheduler.core.repository.TaskRecord;
import com.zioneer.robotscheduler.core.traffic.InMemoryTrafficRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 内核任务执行引擎测试：受理/排队/执行闭环/失败回执/超时/取消（fork 语义）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TaskExecutionEngineTest {

    private static final String SIMPLE_MAP = """
            {
              "versionInfo": {"version": "v1", "status": "published"},
              "mapInfo": {
                "mapName": "EngineTestMap",
                "floors": [{
                  "floorId": "F1", "defaultMaxSpeed": 1.0,
                  "waypoints": [
                    {"id": "WP_A", "name": "A", "position": [0, 0, 0], "type": "charge"},
                    {"id": "WP_B", "name": "B", "position": [6, 0, 0], "type": "pickup"}
                  ],
                  "paths": [
                    {"id": "P1", "from": "WP_A", "to": "WP_B", "waypoints": ["WP_A", "WP_B"],
                     "isBidirectional": true}
                  ]
                }]
              }
            }
            """;

    @Mock
    private RobotGateway gateway;

    @Mock
    private ApplicationEventPublisher publisher;

    @Mock
    private MapArchiveRepository mapArchiveRepository;

    private MapManager mapManager;
    private FleetStateMirror fleetStateMirror;
    private TaskStateMirror taskStateMirror;
    private InMemoryTrafficRegistry trafficRegistry;
    private com.zioneer.robotscheduler.core.traffic.WaitIntentRegistry waitIntents;
    private com.zioneer.robotscheduler.core.assign.TaskAssignProperties assignProperties;
    private TaskExecutionEngine engine;
    private RobotCommandExecutor commandExecutor;

    @BeforeEach
    void setUp() throws Exception {
        fleetStateMirror = new FleetStateMirror();
        taskStateMirror = new TaskStateMirror();
        trafficRegistry = new InMemoryTrafficRegistry();
        waitIntents = new com.zioneer.robotscheduler.core.traffic.WaitIntentRegistry();
        assignProperties = new com.zioneer.robotscheduler.core.assign.TaskAssignProperties();
        assignProperties.setAssignmentTimeoutSeconds(60);
        commandExecutor = new RobotCommandExecutor(gateway);
        mapManager = new MapManager(new MapMetadataParser(), mapArchiveRepository);
        lenient().when(mapArchiveRepository.findActive()).thenReturn(java.util.Optional.empty());
        lenient().when(mapArchiveRepository.findByMapNameAndVersion(anyString(), anyString()))
                .thenReturn(java.util.Optional.empty());
        mapManager.activateFromJson(SIMPLE_MAP, null, null);
        engine = new TaskExecutionEngine(commandExecutor, trafficRegistry, mapManager,
                fleetStateMirror, new SchedulerEventBus(publisher),
                new com.zioneer.robotscheduler.core.assign.CostBasedTaskAssigner(fleetStateMirror,
                        mapManager, assignProperties),
                assignProperties,
                waitIntents,
                new com.zioneer.robotscheduler.core.traffic.ActiveRouteRegistry(mapManager),
                org.mockito.Mockito.mock(com.zioneer.robotscheduler.core.fleet.RobotEventMonitor.class));
        engine.robotReadyTimeoutSeconds = 2;
        engine.navigationTimeoutBaseSeconds = 1;
        engine.segmentConservativeSpeedMps = 1000;
        engine.actionTimeoutDefaultSeconds = 3;
        // 机器人就绪（在线、可派发、在图上 WP_A 附近）
        lenient().when(gateway.navigate(anyString(), anyInt(), anyString(), anyDouble(), anyDouble(),
                anyDouble(), any(), any())).thenReturn(RobotCmdResult.SUCCESS);
        lenient().when(gateway.startActivity(anyString(), anyInt(), anyString(), any(), any()))
                .thenReturn(RobotCmdResult.SUCCESS);
        lenient().when(gateway.ackCompletion(anyString(), anyString())).thenReturn(RobotCmdResult.SUCCESS);
        lenient().when(gateway.stop(anyString(), anyInt(), anyInt(), any())).thenReturn(RobotCmdResult.SUCCESS);
    }

    private void robotReady(String robotCode, double x, double y) {
        var snapshot = new com.zioneer.robotscheduler.core.fleet.RobotSnapshot();
        snapshot.setRobotCode(robotCode);
        snapshot.setFleetName("RG_TEST");
        snapshot.setMap("F1");
        snapshot.setX(x);
        snapshot.setY(y);
        snapshot.setBattery(80.0);
        snapshot.setMode("idle");
        snapshot.setOnline(true);
        snapshot.setDispatchable(true);
        snapshot.setUpdatedAtMillis(System.currentTimeMillis());
        fleetStateMirror.upsert(snapshot);
    }

    private TaskRecord record(String taskId, String category, String robotCode) {
        TaskRecord record = new TaskRecord();
        record.setSchedulerRequestId("req-" + taskId);
        record.setBusinessTaskId(taskId);
        record.setCategory(category);
        record.setRobotCode(robotCode);
        return record;
    }

    private void injectCompletion(String robotCode, int cmdId, String status) {
        commandExecutor.onCompletedCommands(robotCode, List.of(Map.of(
                "cmd_id", String.valueOf(cmdId),
                "status", status,
                "id", "cmp-" + cmdId)));
    }

    /** 导航完成回执 + 同帧位置更新（真实链路：回执随状态帧到达，机器人已位于目标点）。 */
    private void completeNavigationAt(String robotCode, int cmdId, double x, double y) {
        var snapshot = fleetStateMirror.list().stream()
                .filter(r -> robotCode.equals(r.getRobotCode())).findFirst().orElseThrow();
        snapshot.setX(x);
        snapshot.setY(y);
        fleetStateMirror.upsert(snapshot);
        injectCompletion(robotCode, cmdId, "COMPLETED");
    }

    // 待分配任务绑定地图：等待分配期间激活新版本被拒（MapActivationConflictException）；取消后放行激活
    @Test
    void pendingAutoAssignment_bindsMapUntilCanceled() {
        String task = engine.submitForAutoAssign(record("pending-map", "go_to_place", null),
                new TaskGoal("go_to_place", null, "WP_B", null, null, Map.of()), null, null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                mapManager.activateFromJson(SIMPLE_MAP.replace("v1", "v2"), null, null))
                .isInstanceOf(com.zioneer.robotscheduler.core.map.MapActivationConflictException.class);
        engine.cancel(task, record("pending-map", "go_to_place", null), null, "test");
        mapManager.activateFromJson(SIMPLE_MAP.replace("v1", "v2"), null, null);
        assertThat(mapManager.activeSnapshot().orElseThrow().version()).isEqualTo("v2");
    }

    // 终态事件已发布但执行线程未退出：地图绑定仍保持（激活被拒）；线程退出后新版本才能激活
    @Test
    void completedEvent_doesNotReleaseMapUntilExecutionThreadExits() throws Exception {
        var terminalEntered = new java.util.concurrent.CountDownLatch(1);
        var allowTerminal = new java.util.concurrent.CountDownLatch(1);
        var terminal = new java.util.concurrent.atomic.AtomicReference<TaskStateEvent>();
        org.mockito.Mockito.doAnswer(call -> {
            TaskStateEvent event = call.getArgument(0);
            if ("completed".equals(event.status())) {
                terminal.set(event);
                terminalEntered.countDown();
                if (!allowTerminal.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("terminal timeout");
            }
            return null;
        }).when(publisher).publishEvent(any(TaskStateEvent.class));
        robotReady("R001", 0, 0);
        engine.submit(record("bound-map", "go_to_place", "R001"), "R001",
                new TaskGoal("go_to_place", null, "WP_B", null, null, Map.of()));
        ArgumentCaptor<Integer> command = ArgumentCaptor.forClass(Integer.class);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(gateway).navigate(eq("R001"), command.capture(), eq("F1"), eq(6.0),
                        eq(0.0), eq(0.0), eq(1.0), any()));
        String v2 = SIMPLE_MAP.replace("v1", "v2").replace("[6, 0, 0]", "[60, 0, 0]");
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> mapManager.activateFromJson(v2, null, null))
                    .isInstanceOf(com.zioneer.robotscheduler.core.map.MapActivationConflictException.class);
            completeNavigationAt("R001", command.getValue(), 6, 0);
            assertThat(terminalEntered.await(5, TimeUnit.SECONDS)).isTrue();
            // 终态发布前任务已从状态表移除，执行线程尚未退出，地图仍应被保护。
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> mapManager.activateFromJson(v2, null, null))
                    .isInstanceOf(com.zioneer.robotscheduler.core.map.MapActivationConflictException.class);
            assertThat(terminal.get().detail().get("map_binding")).isEqualTo(Map.of(
                    "map_name", "EngineTestMap", "version", "v1", "generation", 1L));
        } finally {
            allowTerminal.countDown();
        }
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> mapManager.activateFromJson(v2, null, null));
        assertThat(mapManager.activeGraph().orElseThrow().waypoint("F1", "WP_B").orElseThrow().getX())
                .isEqualTo(60);
    }

    // 路线穿过减速区（区域限速0.3 < 全局速度帽1.0）：导航速度按限速预算取较小值 0.3 下发
    @Test
    void navigation_passesZoneSpeedLimitToGateway() {
        verifyNavigationSpeed(1.0, 0.3, false);
    }

    // 导航被 RMS 忙拒（REJECTED）后重发：速度帽 0.2 原样保留，不被重试重置为区域限速
    @Test
    void navigation_busyRetry_preservesRobotSpeedCap() {
        verifyNavigationSpeed(0.2, 0.2, true);
    }

    private void verifyNavigationSpeed(double cap, double expected, boolean retry) {
        assignProperties.setNavigationMaxSpeedMps(cap);
        String map = SIMPLE_MAP.replace("\"defaultMaxSpeed\": 1.0",
                "\"defaultMaxSpeed\": 1.0, \"zoneConstraints\": [{\"id\":\"SLOW\","
                        + "\"type\":\"slowDown\",\"maxSpeed\":0.3,"
                        + "\"vertices\":[[2,-1],[4,-1],[4,1],[2,1]]}]");
        mapManager.activateFromJson(map, null, null);
        robotReady("R001", 0, 0);
        engine.submit(record("speed-zone", "go_to_place", "R001"), "R001",
                new TaskGoal("go_to_place", null, "WP_B", null, null, Map.of()));
        ArgumentCaptor<Integer> command = ArgumentCaptor.forClass(Integer.class);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(gateway).navigate(eq("R001"), command.capture(), eq("F1"), eq(6.0),
                        eq(0.0), eq(0.0), eq(expected), any()));
        // 测试保守速度设置为 1000m/s；限速预算必须阻止约 1s 后提前失败。
        ArgumentCaptor<TaskStateEvent> speedEvents = ArgumentCaptor.forClass(TaskStateEvent.class);
        await().during(2, TimeUnit.SECONDS).atMost(4, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(publisher, atLeastOnce()).publishEvent(speedEvents.capture());
            assertThat(speedEvents.getAllValues()).noneMatch(event -> "failed".equals(event.status()));
        });
        if (retry) {
            commandExecutor.onCompletedCommands("R001", List.of(Map.of(
                    "cmd_id", String.valueOf(command.getValue()), "status", "REJECTED",
                    "message", "BODY_ADD_TASK_REJECTED", "id", "busy")));
            await().atMost(6, TimeUnit.SECONDS).untilAsserted(() ->
                    verify(gateway, org.mockito.Mockito.times(2)).navigate(eq("R001"), command.capture(),
                            eq("F1"), eq(6.0), eq(0.0), eq(0.0), eq(expected), any()));
        }
        completeNavigationAt("R001", command.getValue(), 6, 0);
    }

    // 途经点被他人驻留致路线全部被过滤：登记真实等待意图（不下发导航）；取消后等待清空
    @Test
    void routeFilteredByOccupiedIntermediatePoint_recordsRealWaitAndClearsOnCancel() throws Exception {
        TaskExecutionEngine three = threePointEngine();
        robotReady("R001", 0, 0);
        trafficRegistry.updateDwell("blocking-robot", "WP_M", null);
        TaskRecord record = record("filtered-wait", "go_to_place", "R001");
        String task = three.submit(record, "R001",
                new TaskGoal("go_to_place", null, "WP_B", null, null, Map.of()));
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            var blocking = waitIntents.blockers("R001", trafficRegistry.occupationSnapshot());
            assertThat(blocking).hasSize(1);
            assertThat(blocking.get(0).robotCode()).isEqualTo("blocking-robot");
            assertThat(blocking.get(0).waypointIds()).contains("WP_M");
        });
        verify(gateway, org.mockito.Mockito.never()).navigate(eq("R001"), anyInt(), anyString(),
                anyDouble(), anyDouble(), anyDouble(), any(), any());
        three.cancel(task, record, "R001", "test");
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(waitIntents.snapshot()).isEmpty());
    }

    // 停靠点缺失致停靠失败：完成消息如实报“停靠未完成”（不谎称已停靠），工位驻留保留不释放
    @Test
    void failedStandby_doesNotClaimRobotHasLeftWorkstation() {
        robotReady("R001", 0, 0);
        engine.submit(record("standby-missing", "quality_inspection", "R001"), "R001",
                new TaskGoal("quality_inspection", null, "WP_B", "quality_inspection", null,
                        "missing-parking", Map.of()));
        ArgumentCaptor<Integer> nav = ArgumentCaptor.forClass(Integer.class);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(gateway).navigate(eq("R001"), nav.capture(), eq("F1"), eq(6.0),
                        eq(0.0), eq(0.0), any(), any()));
        completeNavigationAt("R001", nav.getValue(), 6, 0);
        ArgumentCaptor<Integer> action = ArgumentCaptor.forClass(Integer.class);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> verify(gateway).startActivity(
                eq("R001"), action.capture(), eq("quality_inspection"), any(), any()));
        injectCompletion("R001", action.getValue(), "COMPLETED");
        ArgumentCaptor<TaskStateEvent> events = ArgumentCaptor.forClass(TaskStateEvent.class);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(publisher, atLeastOnce()).publishEvent(events.capture());
            assertThat(events.getAllValues()).anyMatch(event -> "completed".equals(event.status())
                    && event.message().contains("停靠未完成") && !event.message().contains("已停靠")
                    && event.detail().containsKey("standby_warning"));
        });
        assertThat(trafficRegistry.occupiedWaypoints()).contains("WP_B");
    }

    // 静态拓扑不可达（单向边反向）：直接失败并报“静态拓扑”，不误入交通等待也不下发导航
    @Test
    void staticUnreachable_isReportedWithoutFalseWaitOrTrafficTimeout() {
        mapManager.activateFromJson(SIMPLE_MAP
                .replace("[\"WP_A\", \"WP_B\"]", "[\"WP_B\", \"WP_A\"]")
                .replace("\"isBidirectional\": true", "\"isBidirectional\": false"), null, null);
        robotReady("R001", 0, 0);
        engine.submit(record("unreachable", "go_to_place", "R001"), "R001",
                new TaskGoal("go_to_place", null, "WP_B", null, null, Map.of()));
        ArgumentCaptor<TaskStateEvent> events = ArgumentCaptor.forClass(TaskStateEvent.class);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(publisher, atLeastOnce()).publishEvent(events.capture());
            assertThat(events.getAllValues()).anyMatch(event -> "failed".equals(event.status())
                    && event.message().contains("静态拓扑"));
        });
        assertThat(waitIntents.snapshot()).isEmpty();
        verify(gateway, org.mockito.Mockito.never()).navigate(eq("R001"), anyInt(), anyString(),
                anyDouble(), anyDouble(), anyDouble(), any(), any());
    }

    // 段前复检拦截：第二段目标点被驻留则原地等待不发车，驻留解除后放行并完成
    @Test
    void navigationSegmentEntry_blockedByDweller_waitsThenProceeds() throws Exception {
        // 段前复检：R1 在 A->M->B 第二段发车前发现 B 被他人驻留 → 原地等待不发车；驻留解除 → 放行
        TaskExecutionEngine engine3 = threePointEngine();
        robotReady("R001", 0.5, 0.0);
        engine3.submit(record("task-seg", "go_to_place", "R001"), "R001",
                new TaskGoal("go_to_place", null, "WP_B", null, null, Map.of()));

        // 第 1 段（A->M，目标 x=3）已下发；完成前把 B 登记为 R002 驻留
        ArgumentCaptor<Integer> cmd1 = ArgumentCaptor.forClass(Integer.class);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(gateway).navigate(eq("R001"), cmd1.capture(), eq("F1"), eq(3.0),
                        eq(0.0), eq(0.0), anyDouble(), any()));
        trafficRegistry.updateDwell("R002", "WP_B", null);
        completeNavigationAt("R001", cmd1.getValue(), 3.0, 0.0);

        // 第 2 段（M->B）被段前复检拦住：等待期间不允许出现对 B 的 navigate
        await().during(2, TimeUnit.SECONDS).untilAsserted(() ->
                verify(gateway, org.mockito.Mockito.never()).navigate(eq("R001"), anyInt(),
                        eq("F1"), eq(6.0), eq(0.0), eq(0.0), anyDouble(), any()));

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            var blocked = waitIntents.blockers("R001", trafficRegistry.occupationSnapshot());
            assertThat(blocked).hasSize(1);
            assertThat(blocked.get(0).robotCode()).isEqualTo("R002");
            assertThat(blocked.get(0).waypointIds()).contains("WP_B");
        });
        // 驻留解除 → 第 2 段放行 → 任务完成
        trafficRegistry.updateDwell("R002", null, null);
        ArgumentCaptor<Integer> cmd2 = ArgumentCaptor.forClass(Integer.class);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(gateway).navigate(eq("R001"), cmd2.capture(), eq("F1"), eq(6.0),
                        eq(0.0), eq(0.0), anyDouble(), any()));
        assertThat(waitIntents.snapshot()).isEmpty();
        completeNavigationAt("R001", cmd2.getValue(), 6.0, 0.0);
        ArgumentCaptor<TaskStateEvent> events = ArgumentCaptor.forClass(TaskStateEvent.class);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(publisher, atLeastOnce()).publishEvent(events.capture());
            assertThat(events.getAllValues()).anyMatch(e -> "completed".equals(e.status()));
        });
    }

    // 路线租约全量持有途经点：R1 在途卡住仍持有 M，R2 目标 M 只能进交通等待（不下发导航）
    @Test
    void routeLease_holdsIntermediateWaypoint_blocksOthersTargetingIt() throws Exception {
        // 途经点全量持有：R1 路线 A->M->B 在途时持有 M；R2 目标 M 的任务必须进交通等待（不下发 navigate）
        TaskExecutionEngine engine3 = threePointEngine();
        robotReady("R001", 0.5, 0.0);
        robotReady("R002", 5.5, 0.0);
        engine3.submit(record("task-hold", "go_to_place", "R001"), "R001",
                new TaskGoal("go_to_place", null, "WP_B", null, null, Map.of()));

        ArgumentCaptor<Integer> cmd1 = ArgumentCaptor.forClass(Integer.class);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(gateway).navigate(eq("R001"), cmd1.capture(), eq("F1"), eq(3.0),
                        eq(0.0), eq(0.0), anyDouble(), any()));
        // R1 卡在第一段（不回执）→ 路线租约持续持有 M；R2 此时对 M 下任务
        engine3.submit(record("task-cross", "go_to_place", "R002"), "R002",
                new TaskGoal("go_to_place", null, "WP_M", null, null, Map.of()));

        // R2 必须停在交通等待：不允许对 R002 下发任何 navigate
        await().during(3, TimeUnit.SECONDS).untilAsserted(() ->
                verify(gateway, org.mockito.Mockito.never()).navigate(eq("R002"), anyInt(),
                        anyString(), anyDouble(), anyDouble(), anyDouble(), any(), any()));
        // R2 等待事件可见（交通等待中）
        ArgumentCaptor<TaskStateEvent> events = ArgumentCaptor.forClass(TaskStateEvent.class);
        verify(publisher, atLeastOnce()).publishEvent(events.capture());
        assertThat(events.getAllValues()).anyMatch(e ->
                e.message() != null && e.message().contains("交通等待"));
    }

    // 停靠完成瞬间 dwell 原子登记：后来者对同一点的任务必须交通等待，杜绝叠车事故
    @Test
    void standbyCompletion_registersDwellImmediately_noDoublePark() throws Exception {
        // 停靠叠车回归（P4 双车同点事故）：R1 停靠 WP_M 完成的瞬间，R2 对 WP_M 的任务
        // 必须进交通等待（dwell 已原子登记），不允许下发导航直接开上去
        TaskExecutionEngine engine3 = threePointEngine();
        robotReady("R001", 0.5, 0.0);
        engine3.submit(record("task-std", "quality_inspection", "R001"), "R001",
                new TaskGoal("quality_inspection", null, "WP_B", "quality_inspection", null,
                        "WP_M", Map.of("action_timeout_seconds", 3)));

        // 主导航两段：A->M, M->B
        ArgumentCaptor<Integer> nav1 = ArgumentCaptor.forClass(Integer.class);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(gateway).navigate(eq("R001"), nav1.capture(), eq("F1"), eq(3.0),
                        eq(0.0), eq(0.0), any(), any()));
        completeNavigationAt("R001", nav1.getValue(), 3.0, 0.0);
        ArgumentCaptor<Integer> nav2 = ArgumentCaptor.forClass(Integer.class);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(gateway).navigate(eq("R001"), nav2.capture(), eq("F1"), eq(6.0),
                        eq(0.0), eq(0.0), any(), any()));
        completeNavigationAt("R001", nav2.getValue(), 6.0, 0.0);

        // 质检动作完成 → 停靠导航 B->M
        ArgumentCaptor<Integer> act = ArgumentCaptor.forClass(Integer.class);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(gateway).startActivity(eq("R001"), act.capture(), eq("quality_inspection"), any(), any()));
        injectCompletion("R001", act.getValue(), "COMPLETED");
        // 停靠导航 B->M（x=3.0 第二次出现：主路线第一段 A->M 也是 3.0，取最后一帧）
        ArgumentCaptor<Integer> nav3 = ArgumentCaptor.forClass(Integer.class);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(gateway, org.mockito.Mockito.times(2)).navigate(eq("R001"), nav3.capture(),
                        eq("F1"), eq(3.0), eq(0.0), eq(0.0), any(), any()));
        completeNavigationAt("R001", nav3.getAllValues().getLast(), 3.0, 0.0);
        // R1 任务完成（含停靠）
        ArgumentCaptor<TaskStateEvent> done = ArgumentCaptor.forClass(TaskStateEvent.class);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(publisher, atLeastOnce()).publishEvent(done.capture());
            assertThat(done.getAllValues()).anyMatch(e -> "completed".equals(e.status()));
        });

        // R2 目标 = R1 停靠点：必须停在交通等待（幽灵窗口下会立刻拿到租约并导航）
        robotReady("R002", 0.5, 0.0);
        engine3.submit(record("task-grab", "go_to_place", "R002"), "R002",
                new TaskGoal("go_to_place", null, "WP_M", null, null, Map.of()));
        await().during(2, TimeUnit.SECONDS).untilAsserted(() ->
                verify(gateway, org.mockito.Mockito.never()).navigate(eq("R002"), anyInt(),
                        anyString(), anyDouble(), anyDouble(), anyDouble(), any(), any()));
    }

    /** 三点链路图（A->M->B 两段）+ 独立引擎（不污染全局 SIMPLE_MAP 单段假设）。 */
    private TaskExecutionEngine threePointEngine() throws Exception {
        String map = """
                {
                  "versionInfo": {"version": "v1", "status": "published"},
                  "mapInfo": {"mapName": "ThreePointMap", "floors": [{
                    "floorId": "F1", "defaultMaxSpeed": 1.0,
                    "waypoints": [
                      {"id": "WP_A", "position": [0, 0, 0], "type": "charge"},
                      {"id": "WP_M", "position": [3, 0, 0], "type": "path"},
                      {"id": "WP_B", "position": [6, 0, 0], "type": "pickup"}
                    ],
                    "paths": [
                      {"id": "P1", "from": "WP_A", "to": "WP_M", "waypoints": ["WP_A", "WP_M"], "isBidirectional": true},
                      {"id": "P2", "from": "WP_M", "to": "WP_B", "waypoints": ["WP_M", "WP_B"], "isBidirectional": true}
                    ]
                  }]}
                }
                """;
        MapManager mapManager = new MapManager(new MapMetadataParser(), mapArchiveRepository);
        mapManager.activateFromJson(map, null, null);
        TaskExecutionEngine engine3 = new TaskExecutionEngine(commandExecutor, trafficRegistry, mapManager,
                fleetStateMirror, new SchedulerEventBus(publisher),
                new com.zioneer.robotscheduler.core.assign.CostBasedTaskAssigner(fleetStateMirror,
                        mapManager, assignProperties),
                assignProperties,
                waitIntents,
                new com.zioneer.robotscheduler.core.traffic.ActiveRouteRegistry(mapManager),
                org.mockito.Mockito.mock(com.zioneer.robotscheduler.core.fleet.RobotEventMonitor.class));
        engine3.robotReadyTimeoutSeconds = 2;
        engine3.navigationTimeoutBaseSeconds = 5;
        engine3.segmentConservativeSpeedMps = 1000;
        engine3.actionTimeoutDefaultSeconds = 3;
        return engine3;
    }

    // 动作超时兜底：充电默认 1h（回执慢），其他动作用默认超时
    @Test
    void chargeAction_defaultTimeout1h_othersDefault() {
        // 生产充电任务不带超时参数：充电默认 1h（充满回执慢），其他动作默认 300s
        var charge = new TaskGoal(com.zioneer.robotscheduler.common.scheduler.task.TaskCategories.CHARGE,
                null, "WP_A", com.zioneer.robotscheduler.common.scheduler.task.TaskCategories.CHARGE,
                null, null, Map.of());
        org.assertj.core.api.Assertions.assertThat(engine.actionTimeoutSeconds(charge)).isEqualTo(3600);

        var qi = new TaskGoal(com.zioneer.robotscheduler.common.scheduler.task.TaskCategories.QUALITY_INSPECTION,
                null, "WP_B", null, null, null, Map.of());
        org.assertj.core.api.Assertions.assertThat(engine.actionTimeoutSeconds(qi))
                .isEqualTo(engine.actionTimeoutDefaultSeconds);
    }

    private static final String CHARGE_MAP = """
            {
              "versionInfo": {"version": "v1", "status": "published"},
              "mapInfo": {"mapName": "ChargeMap", "floors": [{
                "floorId": "F1", "defaultMaxSpeed": 1.0,
                "waypoints": [
                  {"id": "WP_A", "name": "A", "position": [0, 0, 0], "type": "parking"},
                  {"id": "C1", "name": "充电位1", "position": [6, 0, 0], "type": "charge"},
                  {"id": "C2", "name": "充电位2", "position": [3, 4, 0], "type": "charge"}
                ],
                "paths": [
                  {"id": "P1", "from": "WP_A", "to": "C2", "waypoints": ["WP_A", "C2"], "bidirectional": true},
                  {"id": "P2", "from": "C2", "to": "C1", "waypoints": ["C2", "C1"], "bidirectional": true}
                ]
              }]}
            }
            """;

    // 目标充电桩被他人驻留：自动改派最近空闲桩并发布改派事件，而非死等占用释放
    @Test
    void chargeTarget_dwellOccupiedByOther_swapsToNearestFreeCharger() throws Exception {
        // 充电位 C1 被其他机器人驻留占用 → 自动改派最近空闲桩（不死等 300s）
        lenient().when(mapArchiveRepository.findActive()).thenReturn(java.util.Optional.empty());
        lenient().when(mapArchiveRepository.findByMapNameAndVersion(anyString(), anyString()))
                .thenReturn(java.util.Optional.empty());
        MapManager chargeMapManager = new MapManager(new MapMetadataParser(), mapArchiveRepository);
        chargeMapManager.activateFromJson(CHARGE_MAP, null, null);
        var chargeEngine = new TaskExecutionEngine(commandExecutor, trafficRegistry, chargeMapManager,
                fleetStateMirror, new SchedulerEventBus(publisher),
                new com.zioneer.robotscheduler.core.assign.CostBasedTaskAssigner(fleetStateMirror,
                        chargeMapManager, assignProperties),
                assignProperties,
                waitIntents,
                new com.zioneer.robotscheduler.core.traffic.ActiveRouteRegistry(chargeMapManager),
                org.mockito.Mockito.mock(com.zioneer.robotscheduler.core.fleet.RobotEventMonitor.class));
        chargeEngine.robotReadyTimeoutSeconds = 2;
        chargeEngine.navigationTimeoutBaseSeconds = 1;

        robotReady("R001", 0.5, 0.0);
        // 另一台机器人驻留占用 C1
        trafficRegistry.updateDwell("R002", "C1", null);

        chargeEngine.submit(record("task-swap", "charge", "R001"), "R001",
                new TaskGoal(com.zioneer.robotscheduler.common.scheduler.task.TaskCategories.CHARGE,
                        null, "C1", com.zioneer.robotscheduler.common.scheduler.task.TaskCategories.CHARGE,
                        null, Map.of()));

        // 应导航去改派后的 C2（(3,4)），而非被占的 C1
        ArgumentCaptor<Integer> cmdIdCaptor = ArgumentCaptor.forClass(Integer.class);
        await().atMost(6, TimeUnit.SECONDS).untilAsserted(() ->
                verify(gateway).navigate(eq("R001"), cmdIdCaptor.capture(), eq("F1"), eq(3.0),
                        eq(4.0), eq(0.0), anyDouble(), any()));
        // 且有改派事件
        ArgumentCaptor<TaskStateEvent> events = ArgumentCaptor.forClass(TaskStateEvent.class);
        await().atMost(6, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(publisher, org.mockito.Mockito.atLeastOnce()).publishEvent(events.capture());
            assertThat(events.getAllValues()).anyMatch(e ->
                    e.message() != null && e.message().contains("自动改派"));
        });
    }

    // 本体自动充电接管（is_charging=true）：不下发 start-activity，充满后按本体自充完成
    @Test
    void chargeTask_bodyAutoCharging_takesOverWithoutActivity() throws Exception {
        // 真机本体自动充电接管：is_charging=true 时不发 start-activity（会被拒并误触 stop 防御），
        // 等充电结束后按"本体自动充电"完成
        var snapshot = new com.zioneer.robotscheduler.core.fleet.RobotSnapshot();
        snapshot.setRobotCode("R001");
        snapshot.setFleetName("RG_TEST");
        snapshot.setMap("F1");
        snapshot.setX(0.5);
        snapshot.setY(0.0);
        snapshot.setBattery(15.0);
        snapshot.setMode("idle");
        snapshot.setOnline(true);
        snapshot.setDispatchable(true);
        snapshot.setBodyCharging(true);
        snapshot.setUpdatedAtMillis(System.currentTimeMillis());
        fleetStateMirror.upsert(snapshot);

        engine.submit(record("task-bc", "charge", "R001"), "R001",
                new TaskGoal(com.zioneer.robotscheduler.common.scheduler.task.TaskCategories.CHARGE,
                        null, "WP_A", com.zioneer.robotscheduler.common.scheduler.task.TaskCategories.CHARGE,
                        null, Map.of()));

        // 充电中标记翻转（模拟充满/停止）
        new Thread(() -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            fleetStateMirror.list().stream()
                    .filter(r -> "R001".equals(r.getRobotCode()))
                    .findFirst().ifPresent(r -> {
                        r.setBodyCharging(false);
                        fleetStateMirror.upsert(r);
                    });
        }).start();

        ArgumentCaptor<TaskStateEvent> events = ArgumentCaptor.forClass(TaskStateEvent.class);
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(publisher, org.mockito.Mockito.atLeastOnce()).publishEvent(events.capture());
            assertThat(events.getAllValues()).anyMatch(event ->
                    "completed".equals(event.status())
                            && Boolean.TRUE.equals(event.detail().get("body_auto_charge")));
        });
        // 全程不得下发 start-activity（本体自充，重复下发会被拒）
        verify(gateway, org.mockito.Mockito.never())
                .startActivity(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    // 本体在非充电点提前自充：止损——停本体任务 + 快速失败报“提前充电”，不进 1h 充电等待
    @Test
    void chargeTask_bodyChargingOffCharger_failsFast() throws Exception {
        // 本体在非充电点提前自充（未校验点位类型）→ 止损：停本体任务 + 快速失败，不进 1h 充电等待
        var snapshot = new com.zioneer.robotscheduler.core.fleet.RobotSnapshot();
        snapshot.setRobotCode("R001");
        snapshot.setFleetName("RG_TEST");
        snapshot.setMap("F1");
        snapshot.setX(2.5);
        snapshot.setY(0.0);
        snapshot.setBattery(15.0);
        snapshot.setMode("idle");
        snapshot.setOnline(true);
        snapshot.setDispatchable(true);
        snapshot.setBodyCharging(true);
        snapshot.setUpdatedAtMillis(System.currentTimeMillis());
        fleetStateMirror.upsert(snapshot);

        engine.submit(record("task-pre", "charge", "R001"), "R001",
                new TaskGoal(com.zioneer.robotscheduler.common.scheduler.task.TaskCategories.CHARGE,
                        null, "WP_A", com.zioneer.robotscheduler.common.scheduler.task.TaskCategories.CHARGE,
                        null, Map.of()));

        ArgumentCaptor<TaskStateEvent> events = ArgumentCaptor.forClass(TaskStateEvent.class);
        await().atMost(6, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(publisher, org.mockito.Mockito.atLeastOnce()).publishEvent(events.capture());
            assertThat(events.getAllValues()).anyMatch(event ->
                    "failed".equals(event.status())
                            && event.message() != null && event.message().contains("提前充电"));
        });
        // 止损必须停掉本体任务（假充电状态卡死机器人）
        verify(gateway, org.mockito.Mockito.atLeastOnce())
                .stop(eq("R001"), org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyInt(), isNull());
    }

    // go_to_place 基本闭环：目标点坐标原样透传下发导航，收到完成回执后任务 completed
    @Test
    void submit_goToPlace_completesAfterNavigationReceipt() {
        robotReady("R001", 0.5, 0.0);

        engine.submit(record("task-1", "go_to_place", "R001"), "R001",
                new TaskGoal("go_to_place", null, "WP_B", null, null, Map.of()));

        // 目标点坐标原样透传（WP_B = (6,0)，theta=0）
        ArgumentCaptor<Integer> cmdIdCaptor = ArgumentCaptor.forClass(Integer.class);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(gateway).navigate(eq("R001"), cmdIdCaptor.capture(), eq("F1"), eq(6.0), eq(0.0),
                        eq(0.0), anyDouble(), any()));
        completeNavigationAt("R001", cmdIdCaptor.getValue(), 6.0, 0.0);

        ArgumentCaptor<TaskStateEvent> events = ArgumentCaptor.forClass(TaskStateEvent.class);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(publisher, atLeastOnce()).publishEvent(events.capture());
            assertThat(events.getAllValues()).anyMatch(event ->
                    "completed".equals(event.status()) && event.taskId() != null);
        });
    }

    // 完成回执但位置复核持续大幅偏离目标（多轮均超阈）：判定疑似地图不一致，失败止损
    @Test
    void navigationCompletion_withLargeArrivalDeviation_failsAsMapMismatch() {
        // 回执完成但上报位置持续远离目标点（复核多轮均超阈）→ 疑似地图不一致，任务失败止损
        engine.arrivalConfirmIntervalMillis = 100;
        robotReady("R001", 0.5, 0.0);
        engine.submit(record("task-mm", "go_to_place", "R001"), "R001",
                new TaskGoal("go_to_place", null, "WP_B", null, null, Map.of()));

        ArgumentCaptor<Integer> cmdIdCaptor = ArgumentCaptor.forClass(Integer.class);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(gateway).navigate(eq("R001"), cmdIdCaptor.capture(), eq("F1"), eq(6.0),
                        eq(0.0), eq(0.0), anyDouble(), any()));
        // 完成回执但位置始终停在原地（复核后偏差 5.5m 仍 > 3m 阈值）
        injectCompletion("R001", cmdIdCaptor.getValue(), "COMPLETED");

        ArgumentCaptor<TaskStateEvent> events = ArgumentCaptor.forClass(TaskStateEvent.class);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(publisher, atLeastOnce()).publishEvent(events.capture());
            assertThat(events.getAllValues()).anyMatch(event ->
                    "failed".equals(event.status()) && event.message() != null
                            && event.message().contains("地图不一致"));
        });
    }

    // 位置帧滞后于完成回执：首采超阈后多轮复核，位置帧跟上后复核通过，任务正常完成
    @Test
    void navigationCompletion_withDelayedPositionFrame_completesAfterRecheck() throws Exception {
        // 状态帧滞后回执（实测 6.6m = 位置帧落后约 7s）：首次采样超阈，位置帧跟上后复核通过
        engine.arrivalConfirmAttempts = 5;
        engine.arrivalConfirmIntervalMillis = 150;
        robotReady("R001", 0.5, 0.0);
        engine.submit(record("task-mm-lag", "go_to_place", "R001"), "R001",
                new TaskGoal("go_to_place", null, "WP_B", null, null, Map.of()));

        ArgumentCaptor<Integer> cmdIdCaptor = ArgumentCaptor.forClass(Integer.class);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(gateway).navigate(eq("R001"), cmdIdCaptor.capture(), eq("F1"), eq(6.0),
                        eq(0.0), eq(0.0), anyDouble(), any()));
        // 回执立即到达，位置帧 300ms 后才更新到目标点（模拟 RMS 位置上报滞后）
        injectCompletion("R001", cmdIdCaptor.getValue(), "COMPLETED");
        java.util.concurrent.ScheduledExecutorService scheduler =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        try {
            scheduler.schedule(() -> completeNavigationAt("R001", cmdIdCaptor.getValue(), 6.0, 0.0),
                    300, TimeUnit.MILLISECONDS);

            ArgumentCaptor<TaskStateEvent> events = ArgumentCaptor.forClass(TaskStateEvent.class);
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                verify(publisher, atLeastOnce()).publishEvent(events.capture());
                assertThat(events.getAllValues()).anyMatch(event ->
                        "completed".equals(event.status()) && event.taskId() != null);
            });
        } finally {
            scheduler.shutdownNow();
        }
    }

    // 纯动作任务（无导航）：直接下发 start-activity，收到完成回执后任务 completed
    @Test
    void submit_actionTask_completesAfterActionReceipt() {
        robotReady("R001", 0.5, 0.0);

        engine.submit(record("task-2", "pause_task", "R001"), "R001",
                new TaskGoal("pause_task", null, null, "pause_task", "tf-1",
                        Map.of("action_timeout_seconds", 5)));

        ArgumentCaptor<Integer> cmdIdCaptor = ArgumentCaptor.forClass(Integer.class);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(gateway).startActivity(eq("R001"), cmdIdCaptor.capture(), eq("pause_task"),
                        eq("tf-1"), any()));
        injectCompletion("R001", cmdIdCaptor.getValue(), "COMPLETED");

        ArgumentCaptor<TaskStateEvent> events = ArgumentCaptor.forClass(TaskStateEvent.class);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(publisher, atLeastOnce()).publishEvent(events.capture());
            assertThat(events.getAllValues())
                    .anyMatch(event -> "completed".equals(event.status()) && event.taskId() != null);
        });
    }

    // 导航失败回执：任务标记 failed 并发 stop，保留剩余占用直至状态帧确认停止
    @Test
    void navigationFailureReceipt_marksTaskFailed() {
        robotReady("R001", 0.5, 0.0);

        engine.submit(record("task-3", "go_to_place", "R001"), "R001",
                new TaskGoal("go_to_place", null, "WP_B", null, null, Map.of()));

        ArgumentCaptor<Integer> cmdIdCaptor = ArgumentCaptor.forClass(Integer.class);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(gateway).navigate(eq("R001"), cmdIdCaptor.capture(), anyString(), anyDouble(),
                        anyDouble(), anyDouble(), any(), any()));
        injectCompletion("R001", cmdIdCaptor.getValue(), "FAILED");

        ArgumentCaptor<TaskStateEvent> events = ArgumentCaptor.forClass(TaskStateEvent.class);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(publisher, atLeastOnce()).publishEvent(events.capture());
            assertThat(events.getAllValues())
                    .anyMatch(event -> "failed".equals(event.status()));
        });
        // 失败后保留剩余预约，直到后续状态帧确认停止。
        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(trafficRegistry.isAwaitingStop("R001")).isTrue();
            assertThat(trafficRegistry.occupiedSegments()).isNotEmpty();
            verify(gateway).stop(eq("R001"), anyInt(), anyInt(), isNull());
        });
    }

    // 导航回执超时：任务 failed + 发 stop，占用保留待停帧确认，机器人标记不可派发
    @Test
    void navigationTimeout_marksTaskFailed() {
        robotReady("R001", 0.5, 0.0);
        // 回执永不到达 → 导航超时（3s）→ failed

        engine.submit(record("task-4", "go_to_place", "R001"), "R001",
                new TaskGoal("go_to_place", null, "WP_B", null, null, Map.of()));

        ArgumentCaptor<TaskStateEvent> events = ArgumentCaptor.forClass(TaskStateEvent.class);
        await().atMost(8, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(publisher, atLeastOnce()).publishEvent(events.capture());
            assertThat(events.getAllValues()).anyMatch(event -> "failed".equals(event.status()));
        });
        assertThat(trafficRegistry.isAwaitingStop("R001")).isTrue();
        assertThat(trafficRegistry.occupiedSegments()).isNotEmpty();
        assertThat(fleetStateMirror.list().get(0).isDispatchable()).isFalse();
        verify(gateway).stop(eq("R001"), anyInt(), anyInt(), isNull());
    }

    // 机器人未就绪上报：等待就绪超时（2s）后任务直接 failed
    @Test
    void robotNotReady_marksTaskFailedAfterWaiting() {
        // 无就绪机器人：等待 2s 超时 → failed
        engine.submit(record("task-5", "go_to_place", "R001"), "R001",
                new TaskGoal("go_to_place", null, "WP_B", null, null, Map.of()));

        ArgumentCaptor<TaskStateEvent> events = ArgumentCaptor.forClass(TaskStateEvent.class);
        await().atMost(6, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(publisher, atLeastOnce()).publishEvent(events.capture());
            assertThat(events.getAllValues()).anyMatch(event -> "failed".equals(event.status()));
        });
    }

    // 导航中取消：保留交通占用，停帧确认前同机器人新任务不发车；确认停止后新任务恢复导航
    @Test
    void cancelNavigation_retainsTraffic_andResumesAfterStopConfirmation() {
        // 就绪检查每 2 秒轮询；为“保留后恢复”的正常过程留出完整检查窗口。
        engine.robotReadyTimeoutSeconds = 6;
        robotReady("R001", 0, 0);
        TaskRecord first = record("cancel-nav", "go_to_place", "R001");
        String task = engine.submit(first, "R001",
                new TaskGoal("go_to_place", null, "WP_B", null, null, Map.of()));
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(gateway).navigate(eq("R001"), anyInt(), eq("F1"), eq(6.0), eq(0.0), eq(0.0), any(), any()));
        engine.cancel(task, first, "R001", "operator");
        assertThat(trafficRegistry.isAwaitingStop("R001")).isTrue();
        assertThat(trafficRegistry.occupiedSegments()).isNotEmpty();
        engine.submit(record("next-nav", "go_to_place", "R001"), "R001",
                new TaskGoal("go_to_place", null, "WP_B", null, null, Map.of()));
        await().during(1, TimeUnit.SECONDS).atMost(3, TimeUnit.SECONDS).untilAsserted(() ->
                verify(gateway, org.mockito.Mockito.times(1)).navigate(eq("R001"), anyInt(), eq("F1"),
                        eq(6.0), eq(0.0), eq(0.0), any(), any()));
        // FleetManager 的三帧确认由车队测试覆盖；此处验证确认完成后任务可继续。
        assertThat(trafficRegistry.confirmStopped("R001", "WP_A", null)).isTrue();
        robotReady("R001", 0, 0);
        ArgumentCaptor<Integer> resumed = ArgumentCaptor.forClass(Integer.class);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(gateway, org.mockito.Mockito.times(2)).navigate(eq("R001"), resumed.capture(), eq("F1"),
                        eq(6.0), eq(0.0), eq(0.0), any(), any()));
        completeNavigationAt("R001", resumed.getValue(), 6, 0);
    }

    // 执行中任务取消（fork 语义）：对机器人发 stop，任务发布 completed + 取消标记
    @Test
    void cancelRunningTask_stopsRobotAndPublishesForkCompleted() {
        robotReady("R001", 0.5, 0.0);
        TaskRecord taskRecord = record("task-6", "pause_task", "R001");
        String kernelTaskId = engine.submit(taskRecord, "R001",
                new TaskGoal("pause_task", null, null, "pause_task", "tf-1",
                        Map.of("action_timeout_seconds", 30)));

        // 等 underway（动作命令已下发）
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(gateway).startActivity(eq("R001"), anyInt(), eq("pause_task"), anyString(), any()));

        boolean canceled = engine.cancel(kernelTaskId, taskRecord, "R001", "operator");

        assertThat(canceled).isTrue();
        // stop 不带 body_task_id（实测契约：带未知 task_id 被 RMS 拒 10000，会残留注册表）
        verify(gateway).stop(eq("R001"), anyInt(), anyInt(), org.mockito.ArgumentMatchers.isNull());
        ArgumentCaptor<TaskStateEvent> events = ArgumentCaptor.forClass(TaskStateEvent.class);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(publisher, atLeastOnce()).publishEvent(events.capture());
            assertThat(events.getAllValues()).anyMatch(event ->
                    "completed".equals(event.status()) && event.isCancellationMarked());
        });
    }

    // 排队任务取消（fork 语义）：不碰执行中的前序任务，直接发布 canceled
    @Test
    void cancelQueuedTask_publishesCanceled() throws Exception {
        // 第一个任务占住 R001 执行通道（动作等待回执），第二个任务保持排队
        robotReady("R001", 0.5, 0.0);
        TaskRecord first = record("task-first", "pause_task", "R001");
        engine.submit(first, "R001",
                new TaskGoal("pause_task", null, null, "pause_task", "tf-first",
                        Map.of("action_timeout_seconds", 30)));
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(gateway).startActivity(eq("R001"), anyInt(), eq("pause_task"), eq("tf-first"), any()));

        TaskRecord second = record("task-second", "pause_task", "R001");
        String secondTaskId = engine.submit(second, "R001",
                new TaskGoal("pause_task", null, null, "pause_task", "tf-second", Map.of()));

        boolean canceled = engine.cancel(secondTaskId, second, "R001", null);

        assertThat(canceled).isTrue();
        ArgumentCaptor<TaskStateEvent> events = ArgumentCaptor.forClass(TaskStateEvent.class);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(publisher, atLeastOnce()).publishEvent(events.capture());
            assertThat(events.getAllValues()).anyMatch(event ->
                    "canceled".equals(event.status()) && secondTaskId.equals(event.taskId()));
        });
    }

    // 孤儿任务（重启后内核态丢失）：取消走止损——发 stop 并发布 completed+取消标记收口
    @Test
    void cancelOrphanTask_unknownKernelId_stopsRobotAndPublishesForkCompleted() {
        // 调度重启后内核态丢失（taskStates 无此任务）但 DB 记录仍挂非终态：
        // 取消要走孤儿止损——发机器人 stop + 发布 completed+cancellation，返回 true 让记录收口
        TaskRecord orphan = record("task-orphan", "charge", "R001");

        boolean canceled = engine.cancel("charge.unknown", orphan, "R001", "restart orphan");

        assertThat(canceled).isTrue();
        verify(gateway).stop(eq("R001"), anyInt(), anyInt(), org.mockito.ArgumentMatchers.isNull());
        ArgumentCaptor<TaskStateEvent> events = ArgumentCaptor.forClass(TaskStateEvent.class);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(publisher, atLeastOnce()).publishEvent(events.capture());
            assertThat(events.getAllValues()).anyMatch(event ->
                    "charge.unknown".equals(event.taskId()) && "completed".equals(event.status())
                            && event.isCancellationMarked());
        });
    }

    // 提交即发 queued 事件：携带 schedulerRequestId 供上层关联；内核任务 ID 按类别前缀生成
    @Test
    void submit_emitsQueuedEventWithSchedulerRequestId() {
        robotReady("R001", 0.5, 0.0);
        TaskRecord taskRecord = record("task-7", "go_to_place", "R001");

        String kernelTaskId = engine.submit(taskRecord, "R001",
                new TaskGoal("go_to_place", null, "WP_B", null, null, Map.of()));

        assertThat(kernelTaskId).startsWith("go_to_place.");
        ArgumentCaptor<TaskStateEvent> events = ArgumentCaptor.forClass(TaskStateEvent.class);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(publisher, atLeastOnce()).publishEvent(events.capture());
            assertThat(events.getAllValues()).anyMatch(event ->
                    "queued".equals(event.status()) && "req-task-7".equals(event.schedulerRequestId()));
        });
    }

    // 唯一路径被他人占用：占用重试期间对端释放 → 获取成功，任务进入 underway
    @Test
    void trafficConflict_resolvedAfterRelease() {
        robotReady("R001", 0.5, 0.0);
        robotReady("R002", 0.8, 0.2);
        // R002 先占住唯一路径
        trafficRegistry.tryAcquire("R002", List.of("P1#WP_A->WP_B"), java.util.Set.of("WP_B"),
                java.time.Duration.ofSeconds(60));
        // R001 导航到 WP_B：占用失败重试期间 R002 释放 → 成功
        new Thread(() -> {
            try {
                Thread.sleep(2500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            trafficRegistry.releaseAll("R002");
        }).start();

        engine.submit(record("task-8", "go_to_place", "R001"), "R001",
                new TaskGoal("go_to_place", null, "WP_B", null, null, Map.of()));

        ArgumentCaptor<TaskStateEvent> events = ArgumentCaptor.forClass(TaskStateEvent.class);
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(publisher, atLeastOnce()).publishEvent(events.capture());
            assertThat(events.getAllValues()).anyMatch(event -> "underway".equals(event.status()));
        });
    }

    // ==================== 自动分配（P4） ====================

    @Test
    void autoAssign_availableRobot_assignsAndExecutes() {
        robotReady("R001", 0.5, 0.0);

        engine.submitForAutoAssign(record("auto-1", "go_to_place", null),
                new TaskGoal("go_to_place", null, "WP_B", null, null,
                        Map.of("task_id", "auto-1")),
                null, null);

        ArgumentCaptor<TaskStateEvent> events = ArgumentCaptor.forClass(TaskStateEvent.class);
        await().atMost(6, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(publisher, atLeastOnce()).publishEvent(events.capture());
            assertThat(events.getAllValues()).anyMatch(event ->
                    "underway".equals(event.status()) && "R001".equals(event.assignedRobotName()));
        });
    }

    // 自动分配无机器人且超时窗口为 0：重试时立即发布 failed
    @Test
    void autoAssign_noRobot_timeoutFails() throws Exception {
        assignProperties.setAssignmentTimeoutSeconds(0);
        // 需要重置超时窗口：超时 0 → 下一次 retryPendingAssignments 立即 failed
        engine.submitForAutoAssign(record("auto-2", "go_to_place", null),
                new TaskGoal("go_to_place", null, "WP_B", null, null, Map.of("task_id", "auto-2")),
                null, null);

        // deadline=now+0，同毫秒内 now > deadline 不成立 → 睡 50ms 保证超时窗口已过
        Thread.sleep(50);
        engine.retryPendingAssignments();

        ArgumentCaptor<TaskStateEvent> events = ArgumentCaptor.forClass(TaskStateEvent.class);
        await().atMost(8, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(publisher, atLeastOnce()).publishEvent(events.capture());
            assertThat(events.getAllValues()).anyMatch(event ->
                    "failed".equals(event.status()));
        });
    }

    // 自动分配先排队等待，机器人上线后手动重试：补充分配并进入 underway
    @Test
    void autoAssign_pendingThenRobotAppears_assignedOnRetry() {
        // 初始无机器人：保持 queued
        assignProperties.setAssignmentTimeoutSeconds(60);
        engine.submitForAutoAssign(record("auto-3", "pause_task", null),
                new TaskGoal("pause_task", null, null, "pause_task", "tf-auto",
                        Map.of("task_id", "auto-3")),
                null, null);

        // 机器人上线后重试 → 分配执行
        robotReady("R002", 0.5, 0.0);
        engine.retryPendingAssignments();

        ArgumentCaptor<TaskStateEvent> events = ArgumentCaptor.forClass(TaskStateEvent.class);
        await().atMost(6, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(publisher, atLeastOnce()).publishEvent(events.capture());
            assertThat(events.getAllValues()).anyMatch(event ->
                    "underway".equals(event.status()) && "R002".equals(event.assignedRobotName()));
        });
    }

    // 自动分配等待期取消：任务尚未绑定机器人，直接发布 canceled
    @Test
    void autoAssign_cancelWhilePending_publishesCanceled() {
        assignProperties.setAssignmentTimeoutSeconds(60);
        TaskRecord taskRecord = record("auto-4", "pause_task", null);
        String kernelTaskId = engine.submitForAutoAssign(taskRecord,
                new TaskGoal("pause_task", null, null, "pause_task", "tf-auto",
                        Map.of("task_id", "auto-4")),
                null, null);

        boolean canceled = engine.cancel(kernelTaskId, taskRecord, null, null);

        assertThat(canceled).isTrue();
        ArgumentCaptor<TaskStateEvent> events = ArgumentCaptor.forClass(TaskStateEvent.class);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(publisher, atLeastOnce()).publishEvent(events.capture());
            assertThat(events.getAllValues()).anyMatch(event ->
                    "canceled".equals(event.status()) && kernelTaskId.equals(event.taskId()));
        });
    }
}
