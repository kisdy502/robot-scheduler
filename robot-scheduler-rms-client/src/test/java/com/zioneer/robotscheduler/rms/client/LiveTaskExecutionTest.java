package com.zioneer.robotscheduler.rms.client;

import com.zioneer.robotscheduler.core.fleet.TaskAssignmentRegistry;
import com.zioneer.robotscheduler.core.fleet.RobotAvailabilityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zioneer.robotscheduler.core.event.SchedulerEventBus;
import com.zioneer.robotscheduler.core.fleet.FleetManager;
import com.zioneer.robotscheduler.core.fleet.FleetPollingProperties;
import com.zioneer.robotscheduler.core.fleet.FleetStateMirror;
import com.zioneer.robotscheduler.core.fleet.RobotCommandExecutor;
import com.zioneer.robotscheduler.core.fleet.RobotSnapshot;
import com.zioneer.robotscheduler.core.map.MapManager;
import com.zioneer.robotscheduler.core.map.MapMetadataParser;
import com.zioneer.robotscheduler.core.repository.MapArchiveRepository;
import com.zioneer.robotscheduler.core.repository.TaskRecord;
import com.zioneer.robotscheduler.core.task.TaskExecutionEngine;
import com.zioneer.robotscheduler.core.task.TaskGoal;
import com.zioneer.robotscheduler.core.traffic.InMemoryTrafficRegistry;
import com.zioneer.robotscheduler.rms.config.RobotManagementProperties;
import com.zioneer.robotscheduler.rms.gateway.RmsRobotStateSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 真实任务执行闭环验证（设置 RMS_LIVE_TASK=true 激活；会真实驱动机器人，联调专用）。
 *
 * <p>链路：dispatch → 内核执行 → navigate 真实下发 RMS → completed_commands 回执 → completed 事件。
 * 目标点 E1 为电梯口（仿真机当前位置不在图上，召回图上最近路径点，为后续联调做准备）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveTaskExecutionTest {
    private final TaskAssignmentRegistry ownershipRegistry = new TaskAssignmentRegistry();

    private static final String RMS_BASE_URL = System.getenv()
            .getOrDefault("RMS_LIVE_BASE_URL", "http://127.0.0.1:18083");
    private static final String FLEET = System.getenv()
            .getOrDefault("RMS_LIVE_FLEET", "RG_20260407_00001");
    private static final String ROBOT = System.getenv()
            .getOrDefault("RMS_LIVE_ROBOT", "RB_20261023_00001");
    private static final Path MAP_JSON = Path.of("..", "local-map", "map_info.json");

    private TaskExecutionEngine engine;
    private FleetStateMirror mirror;
    private FleetManager fleetManager;
    private java.util.concurrent.ExecutorService poller;
    private final java.util.List<com.zioneer.robotscheduler.core.task.TaskStateEvent> events =
            new CopyOnWriteArrayList<>();

    @BeforeAll
    void setUp() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("RMS_LIVE_TASK")),
                "设置 RMS_LIVE_TASK=true 激活真实任务执行测试（会驱动机器人）");
        RobotManagementProperties properties = new RobotManagementProperties();
        properties.setBaseUrl(RMS_BASE_URL);
        properties.setFleetName(FLEET);
        HttpRobotManagementClient client = new HttpRobotManagementClient(properties,
                RestClient.builder(), new ObjectMapper());
        RmsRobotStateSource stateSource = new RmsRobotStateSource(client, properties);

        MapArchiveRepository archiveRepository = Mockito.mock(MapArchiveRepository.class);
        Mockito.when(archiveRepository.findActive()).thenReturn(java.util.Optional.empty());
        MapManager mapManager = new MapManager(new MapMetadataParser(), archiveRepository);
        Assumptions.assumeTrue(Files.exists(MAP_JSON), "缺少本地地图文件");
        mapManager.loadFromFiles(MAP_JSON.toString(), null, null);

        FleetPollingProperties pollingProperties = new FleetPollingProperties();
        pollingProperties.setMapNameMapping(Map.of("L1", "F1"));
        mirror = new FleetStateMirror();
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof com.zioneer.robotscheduler.core.task.TaskStateEvent taskEvent) {
                events.add(taskEvent);
            }
        };
        RobotCommandExecutor commandExecutor = new RobotCommandExecutor(
                new com.zioneer.robotscheduler.rms.gateway.RmsRobotGateway(client));
        engine = new TaskExecutionEngine(commandExecutor, new InMemoryTrafficRegistry(), mapManager,
                mirror, new SchedulerEventBus(publisher),
                new com.zioneer.robotscheduler.core.assign.CostBasedTaskAssigner(mirror, mapManager,
                        new com.zioneer.robotscheduler.core.assign.TaskAssignProperties(),
                new RobotAvailabilityService(ownershipRegistry, new InMemoryTrafficRegistry())),
                new com.zioneer.robotscheduler.core.assign.TaskAssignProperties(),
                new com.zioneer.robotscheduler.core.traffic.WaitIntentRegistry(),
                new com.zioneer.robotscheduler.core.traffic.ActiveRouteRegistry(mapManager),
                org.mockito.Mockito.mock(com.zioneer.robotscheduler.core.fleet.RobotEventMonitor.class), ownershipRegistry,
                new RobotAvailabilityService(ownershipRegistry, new InMemoryTrafficRegistry()));
        fleetManager = new FleetManager(stateSource, mirror,
                new com.zioneer.robotscheduler.core.task.TaskStateMirror(), mapManager,
                new InMemoryTrafficRegistry(),
                new com.zioneer.robotscheduler.core.traffic.ActiveRouteRegistry(mapManager),
                org.mockito.Mockito.mock(com.zioneer.robotscheduler.core.fleet.RobotEventMonitor.class),
                pollingProperties, commandExecutor,
                new com.zioneer.robotscheduler.core.charger.InMemoryChargerPool(),
                new RobotAvailabilityService(ownershipRegistry, new InMemoryTrafficRegistry()),
                new com.zioneer.robotscheduler.core.fleet.ExternalActivityMirror());
        // 真实轮询一帧，建立机器人镜像
        fleetManager.pollStates();
        // 状态轮询线程：回执闭环依赖 completed_commands 持续喂给命令执行器
        poller = java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "live-status-poller");
            thread.setDaemon(true);
            return thread;
        });
        poller.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    fleetManager.pollStates();
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception ignored) {
                    // 单帧失败忽略，下轮重试
                }
            }
        });
    }

    @AfterAll
    void tearDown() {
        if (poller != null) {
            poller.shutdownNow();
        }
    }

    @Test
    void liveTask_goToElevator_entireLoopReachesTerminalState() {
        TaskRecord record = new TaskRecord();
        record.setSchedulerRequestId("live-req-001");
        record.setBusinessTaskId("live-task-001");
        record.setCategory("go_to_place");
        record.setRobotCode(ROBOT);

        engine.submit(record, ROBOT, new TaskGoal("go_to_place", null, "E1", null, null,
                Map.of("task_id", "live-task-001", "action_timeout_seconds", 180)));

        // 终态闭环验证：completed（机器人完成导航）或 failed（异常拒绝）。
        await().atMost(150, TimeUnit.SECONDS).pollInterval(2, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(events).anyMatch(event ->
                    "completed".equals(event.status()) || "failed".equals(event.status()));
        });
        System.out.println("[LIVE-TASK] 事件序列: " + events.stream()
                .map(event -> event.status()).toList());
        var terminal = events.stream()
                .filter(event -> "completed".equals(event.status()) || "failed".equals(event.status()))
                .findFirst().orElseThrow();
        System.out.println("[LIVE-TASK] 终态: " + terminal.status() + " task=" + terminal.taskId());
        System.out.println("[LIVE-TASK] 机器人最终位置: "
                + mirror.list().stream()
                        .filter(robot -> ROBOT.equals(robot.getRobotCode()))
                        .findFirst().map(robot -> "(" + robot.getX() + "," + robot.getY() + ") dispatchable=" + robot.isDispatchable())
                        .orElse("unknown"));
    }
}
