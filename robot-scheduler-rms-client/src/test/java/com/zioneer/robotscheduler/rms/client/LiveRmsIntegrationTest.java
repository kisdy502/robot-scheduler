package com.zioneer.robotscheduler.rms.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zioneer.robotscheduler.core.fleet.FleetManager;
import com.zioneer.robotscheduler.core.fleet.FleetPollingProperties;
import com.zioneer.robotscheduler.core.fleet.FleetStateMirror;
import com.zioneer.robotscheduler.core.fleet.RobotSnapshot;
import com.zioneer.robotscheduler.core.map.MapManager;
import com.zioneer.robotscheduler.core.map.MapMetadataParser;
import com.zioneer.robotscheduler.core.repository.MapArchiveRepository;
import com.zioneer.robotscheduler.core.task.TaskStateMirror;
import com.zioneer.robotscheduler.core.traffic.InMemoryTrafficRegistry;
import com.zioneer.robotscheduler.rms.config.RobotManagementProperties;
import com.zioneer.robotscheduler.rms.gateway.RmsRobotStateSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 RMS 联调验证（设置 RMS_LIVE_TEST=true 激活；CI 默认跳过）。
 *
 * <p>验证链路：HTTP 客户端 → RmsRobotStateSource 适配 → FleetManager 帧融合（含真实地图离图判定与楼层映射）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveRmsIntegrationTest {

    private static final String RMS_BASE_URL = System.getenv()
            .getOrDefault("RMS_LIVE_BASE_URL", "http://127.0.0.1:18083");
    private static final String FLEET = System.getenv()
            .getOrDefault("RMS_LIVE_FLEET", "RG_20260407_00001");
    private static final Path MAP_JSON = Path.of("..", "local-map", "map_info.json");

    private FleetManager fleetManager;
    private FleetStateMirror mirror;

    private HttpRobotManagementClient client;
    private RmsRobotStateSource stateSource;

    @BeforeAll
    void setUp() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("RMS_LIVE_TEST")),
                "设置 RMS_LIVE_TEST=true 激活真实 RMS 联调测试");
        RobotManagementProperties properties = new RobotManagementProperties();
        properties.setBaseUrl(RMS_BASE_URL);
        properties.setFleetName(FLEET);
        client = new HttpRobotManagementClient(properties,
                RestClient.builder(), new ObjectMapper());
        stateSource = new RmsRobotStateSource(client, properties);

        MapArchiveRepository archiveRepository = Mockito.mock(MapArchiveRepository.class);
        Mockito.when(archiveRepository.findActive()).thenReturn(java.util.Optional.empty());
        Mockito.when(archiveRepository.findByMapNameAndVersion(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(java.util.Optional.empty());
        MapManager mapManager = new MapManager(new MapMetadataParser(), archiveRepository);
        if (Files.exists(MAP_JSON)) {
            mapManager.loadFromFiles(MAP_JSON.toString(), null, null);
        }

        FleetPollingProperties pollingProperties = new FleetPollingProperties();
        pollingProperties.setMapNameMapping(Map.of("L1", "F1"));
        mirror = new FleetStateMirror();
        fleetManager = new FleetManager(stateSource, mirror, new TaskStateMirror(), mapManager,
                new InMemoryTrafficRegistry(),
                new com.zioneer.robotscheduler.core.traffic.ActiveRouteRegistry(mapManager),
                org.mockito.Mockito.mock(com.zioneer.robotscheduler.core.fleet.RobotEventMonitor.class),
                pollingProperties,
                new com.zioneer.robotscheduler.core.fleet.RobotCommandExecutor(
                        Mockito.mock(com.zioneer.robotscheduler.core.fleet.RobotGateway.class)),
                new com.zioneer.robotscheduler.core.charger.InMemoryChargerPool());
    }

    @Test
    void liveRms_statePolling_feedsMirror() {
        // 先直连暴露真实错误（无日志实现环境下 FleetManager 的 catch 会静默）
        try {
            System.out.println("[LIVE] direct fetchStatus robots=" + client.fetchStatus(FLEET, null).size());
        } catch (Exception e) {
            System.out.println("[LIVE] direct fetchStatus failed: " + e);
            throw e;
        }
        fleetManager.pollStates();

        List<RobotSnapshot> robots = mirror.list();
        assertThat(robots).isNotEmpty();
        RobotSnapshot robot = robots.get(0);
        System.out.println("[LIVE] robot=" + robot.getRobotCode()
                + " online=" + robot.isOnline()
                + " mode=" + robot.getMode()
                + " dispatchable=" + robot.isDispatchable()
                + " floor=" + robot.getFloorId()
                + " battery=" + robot.getBattery()
                + " pos=(" + robot.getX() + "," + robot.getY() + ")");
        // 在线仿真机应完成楼层映射（L1→F1）
        assertThat(robot.getFloorId()).isEqualTo("F1");
        assertThat(robot.isOnline()).isTrue();
    }

    @Test
    void liveRms_inventoryPolling_returnsFleet() {
        fleetManager.pollInventory();

        assertThat(mirror.list()).isNotEmpty();
        System.out.println("[LIVE] inventory robots=" + mirror.list().size());
    }
}
