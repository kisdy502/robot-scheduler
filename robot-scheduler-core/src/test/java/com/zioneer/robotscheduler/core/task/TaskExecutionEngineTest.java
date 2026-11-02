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
    private com.zioneer.robotscheduler.core.assign.TaskAssignProperties assignProperties;
    private TaskExecutionEngine engine;
    private RobotCommandExecutor commandExecutor;

    @BeforeEach
    void setUp() throws Exception {
        fleetStateMirror = new FleetStateMirror();
        taskStateMirror = new TaskStateMirror();
        trafficRegistry = new InMemoryTrafficRegistry();
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
                new com.zioneer.robotscheduler.core.traffic.WaitIntentRegistry(),
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

    @Test
    void navigation_passesZoneSpeedLimitToGateway() {
        verifyNavigationSpeed(1.0, 0.3, false);
    }

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

        // 驻留解除 → 第 2 段放行 → 任务完成
        trafficRegistry.updateDwell("R002", null, null);
        ArgumentCaptor<Integer> cmd2 = ArgumentCaptor.forClass(Integer.class);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(gateway).navigate(eq("R001"), cmd2.capture(), eq("F1"), eq(6.0),
                        eq(0.0), eq(0.0), anyDouble(), any()));
        completeNavigationAt("R001", cmd2.getValue(), 6.0, 0.0);
        ArgumentCaptor<TaskStateEvent> events = ArgumentCaptor.forClass(TaskStateEvent.class);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(publisher, atLeastOnce()).publishEvent(events.capture());
            assertThat(events.getAllValues()).anyMatch(e -> "completed".equals(e.status()));
        });
    }

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
                new com.zioneer.robotscheduler.core.traffic.WaitIntentRegistry(),
                new com.zioneer.robotscheduler.core.traffic.ActiveRouteRegistry(mapManager),
                org.mockito.Mockito.mock(com.zioneer.robotscheduler.core.fleet.RobotEventMonitor.class));
        engine3.robotReadyTimeoutSeconds = 2;
        engine3.navigationTimeoutBaseSeconds = 5;
        engine3.segmentConservativeSpeedMps = 1000;
        engine3.actionTimeoutDefaultSeconds = 3;
        return engine3;
    }

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
                new com.zioneer.robotscheduler.core.traffic.WaitIntentRegistry(),
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
        // 失败后释放交通占用
        assertThat(trafficRegistry.occupiedSegments()).isEmpty();
    }

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
    }

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
