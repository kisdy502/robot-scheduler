package com.zioneer.robotscheduler.core.fleet;

import com.zioneer.robotscheduler.core.map.MapManager;
import com.zioneer.robotscheduler.core.map.MapMetadataParser;
import com.zioneer.robotscheduler.core.repository.MapArchiveRepository;
import com.zioneer.robotscheduler.core.task.TaskStateEvent;
import com.zioneer.robotscheduler.core.task.TaskStateMirror;
import com.zioneer.robotscheduler.core.traffic.InMemoryTrafficRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * 车队管理器测试：状态帧融合/mode 推导/楼层映射/stale 下线/decommission/离图检测/清单同步。
 */
@ExtendWith(MockitoExtension.class)
class FleetManagerTest {

    /** 测试地图：A(0,0) 充电位、B(6,0) 取货位，双向路径。 */
    private static final String SIMPLE_MAP = """
            {
              "versionInfo": {"version": "v1", "status": "published"},
              "mapInfo": {
                "mapName": "FleetTestMap",
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
    private RobotStateSource stateSource;

    @Mock
    private RobotGateway stateGateway;

    @Mock
    private com.zioneer.robotscheduler.core.charger.ChargerPoolPort chargerPoolMock;

    @Mock
    private MapArchiveRepository mapArchiveRepository;

    private FleetStateMirror mirror;
    private TaskStateMirror taskStateMirror;
    private InMemoryTrafficRegistry trafficRegistry;
    private FleetPollingProperties properties;
    private MapManager mapManager;
    private RobotCommandExecutor commandExecutor;
    private FleetManager fleetManager;

    @BeforeEach
    void setUp() {
        mirror = new FleetStateMirror();
        taskStateMirror = new TaskStateMirror();
        trafficRegistry = new InMemoryTrafficRegistry();
        properties = new FleetPollingProperties();
        mapManager = new MapManager(new MapMetadataParser(), mapArchiveRepository);
        commandExecutor = new RobotCommandExecutor(stateGateway);
        fleetManager = new FleetManager(stateSource, mirror, taskStateMirror, mapManager,
                trafficRegistry, new com.zioneer.robotscheduler.core.traffic.ActiveRouteRegistry(mapManager),
                org.mockito.Mockito.mock(com.zioneer.robotscheduler.core.fleet.RobotEventMonitor.class),
                properties, commandExecutor, chargerPoolMock);
        lenient().when(mapArchiveRepository.findByMapNameAndVersion(anyString(), anyString()))
                .thenReturn(java.util.Optional.empty());
    }

    // 失联机器人（stale 清扫后保留交通）恢复：首帧触发 stop，请求送达本身不解除占用
    // 预期：stop 被接受且连续 3 帧静止后才释放 future-route，转占物理路段 P1、不再占 WP_B
    @Test
    void lostRobot_recoversOnlyAfterStopAcceptedAndThreeStableFrames() {
        mapManager.activateFromJson(SIMPLE_MAP, null, null);
        fleetManager.onStateTick(List.of(frame("R001", 0, 0, 80.0, "F1")));
        trafficRegistry.tryAcquire("R001", List.of("future-route"), java.util.Set.of("WP_B"), Duration.ofMinutes(1));
        mirror.list().get(0).setUpdatedAtMillis(System.currentTimeMillis() - 16_000);
        fleetManager.sweepStaleRobots();
        assertThat(trafficRegistry.occupiedSegments()).contains("future-route");
        assertThat(trafficRegistry.occupiedWaypoints()).contains("WP_A");
        org.mockito.Mockito.when(stateGateway.stop(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(RobotCmdResult.SUCCESS);
        // 恢复第一帧触发 stop；请求送达本身不能解除占用。
        fleetManager.onStateTick(List.of(frame("R001", 3, 0, 80.0, "F1")));
        assertThat(trafficRegistry.isAwaitingStop("R001")).isTrue();
        assertThat(mirror.list().get(0).isDispatchable()).isFalse();
        for (int i = 0; i < 2; i++) fleetManager.onStateTick(List.of(frame("R001", 3, 0, 80.0, "F1")));
        assertThat(trafficRegistry.isAwaitingStop("R001")).isTrue();
        fleetManager.onStateTick(List.of(frame("R001", 3, 0, 80.0, "F1")));
        assertThat(trafficRegistry.isAwaitingStop("R001")).isFalse();
        assertThat(trafficRegistry.occupiedSegments()).doesNotContain("future-route");
        assertThat(trafficRegistry.occupiedSegments()).contains("P1#WP_A-WP_B");
        assertThat(trafficRegistry.occupiedWaypoints()).doesNotContain("WP_B");
    }

    // 慢速微移不算停稳：awaiting stop 保持不释放，真正静止满 3 帧后才恢复可派
    @Test
    void slowMovement_doesNotCountAsStop_andStableFramesResumeDispatch() {
        mapManager.activateFromJson(SIMPLE_MAP, null, null);
        trafficRegistry.tryAcquire("R001", List.of("future-route"), java.util.Set.of("WP_B"), Duration.ofMinutes(1));
        trafficRegistry.retainUntilStopped("R001", "timeout");
        trafficRegistry.markStopAccepted("R001");
        for (double x : new double[]{2, 2.1, 2.2, 2.3}) {
            fleetManager.onStateTick(List.of(frame("R001", x, 0, 80.0, "F1")));
        }
        assertThat(trafficRegistry.isAwaitingStop("R001")).isTrue();
        for (int i = 0; i < 3; i++) fleetManager.onStateTick(List.of(frame("R001", 2.3, 0, 80.0, "F1")));
        assertThat(trafficRegistry.isAwaitingStop("R001")).isFalse();
        assertThat(mirror.list().get(0).isDispatchable()).isTrue();
    }

    // stop 被拒绝（IMPOSSIBLE）：位置再稳也保留交通占用且不可派，stop 仅下发一次不重试
    @Test
    void stopRejected_keepsTrafficEvenWhenPositionIsStable() {
        mapManager.activateFromJson(SIMPLE_MAP, null, null);
        trafficRegistry.tryAcquire("R001", List.of("future-route"), java.util.Set.of("WP_B"), Duration.ofMinutes(1));
        trafficRegistry.retainUntilStopped("R001", "timeout");
        org.mockito.Mockito.when(stateGateway.stop(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(RobotCmdResult.IMPOSSIBLE);
        for (int i = 0; i < 5; i++) fleetManager.onStateTick(List.of(frame("R001", 3, 0, 80.0, "F1")));
        assertThat(trafficRegistry.isAwaitingStop("R001")).isTrue();
        assertThat(trafficRegistry.occupiedSegments()).contains("future-route");
        assertThat(mirror.list().get(0).isDispatchable()).isFalse();
        org.mockito.Mockito.verify(stateGateway, org.mockito.Mockito.times(1)).stop(anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.isNull());
    }

    // 在线空闲机器人状态帧：镜像更新基础字段，推导 mode=idle 且可派发
    @Test
    void onStateTick_onlineIdleRobot_updatesMirrorWithDerivedMode() {
        fleetManager.onStateTick(List.of(frame("R001", 0.5, 0.1, 80.0, null)));

        RobotSnapshot snapshot = mirror.list().get(0);
        assertThat(snapshot.getRobotCode()).isEqualTo("R001");
        assertThat(snapshot.isOnline()).isTrue();
        assertThat(snapshot.getMode()).isEqualTo("idle");
        assertThat(snapshot.isDispatchable()).isTrue();
        assertThat(snapshot.getBattery()).isEqualTo(80.0);
        assertThat(snapshot.getX()).isEqualTo(0.5);
        assertThat(snapshot.getFleetName()).isEqualTo("RG_20260407_00001");
    }

    // RMS 地图名映射：按配置 L1→F1 写入楼层，同时保留原始地图名
    @Test
    void onStateTick_mapsRmsMapNameToFloorId() {
        properties.setMapNameMapping(Map.of("L1", "F1"));

        fleetManager.onStateTick(List.of(frame("R001", 0.5, 0.1, 80.0, "L1")));

        assertThat(mirror.list().get(0).getFloorId()).isEqualTo("F1");
        assertThat(mirror.list().get(0).getMap()).isEqualTo("L1");
    }

    // 机器人有进行中质检任务：下一状态帧推导 mode=running
    @Test
    void onStateTick_activeTaskDerivesRunningMode() {
        fleetManager.onStateTick(List.of(frame("R001", 1.0, 0.0, 80.0, null)));
        taskStateMirror.upsert(new TaskStateEvent("qi.task-1", "running", "quality_inspection",
                Map.of(), Map.of(), null, "R001", null, System.currentTimeMillis()));

        fleetManager.onStateTick(List.of(frame("R001", 2.0, 0.0, 80.0, null)));

        assertThat(mirror.list().get(0).getMode()).isEqualTo("running");
    }

    // 机器人有进行中充电任务：下一状态帧推导 mode=charging
    @Test
    void onStateTick_chargeTaskDerivesChargingMode() {
        fleetManager.onStateTick(List.of(frame("R001", 0.0, 0.0, 20.0, null)));
        taskStateMirror.upsert(new TaskStateEvent("charge.task-1", "running", "charge",
                Map.of(), Map.of(), null, "R001", null, System.currentTimeMillis()));

        fleetManager.onStateTick(List.of(frame("R001", 0.0, 0.0, 25.0, null)));

        assertThat(mirror.list().get(0).getMode()).isEqualTo("charging");
    }

    // 任务完成且位置连续静止：mode 回退为 idle
    @Test
    void onStateTick_completedTaskFallsBackToIdle() {
        fleetManager.onStateTick(List.of(frame("R001", 1.0, 0.0, 80.0, null)));
        taskStateMirror.upsert(new TaskStateEvent("qi.task-1", "completed", "quality_inspection",
                Map.of(), Map.of(), null, "R001", null, System.currentTimeMillis()));

        // 位置不变（连续静止帧）→ 回到 idle；移动检测生效期（位置变化）应保持 running
        fleetManager.onStateTick(List.of(frame("R001", 1.0, 0.0, 80.0, null)));
        fleetManager.onStateTick(List.of(frame("R001", 1.0, 0.0, 80.0, null)));
        fleetManager.onStateTick(List.of(frame("R001", 1.0, 0.0, 80.0, null)));

        assertThat(mirror.list().get(0).getMode()).isEqualTo("idle");
    }

    // 无任务记录但位置变化（上位机直发）：标记 running 不可派，静止满 3 帧后恢复 idle 可派
    @Test
    void onStateTick_externalMovementMarksRunning() {
        // 上位机直发任务（调度无任务记录），位置持续变化 → running 不可派（保底防冲突）
        fleetManager.onStateTick(List.of(frame("R001", 0.0, 0.0, 80.0, null)));
        fleetManager.onStateTick(List.of(frame("R001", 1.0, 0.0, 80.0, null)));

        RobotSnapshot snapshot = mirror.list().get(0);
        assertThat(snapshot.getMode()).isEqualTo("running");
        assertThat(snapshot.isDispatchable()).isFalse();

        // 位置停稳（连续静止帧）→ 恢复 idle 可派
        fleetManager.onStateTick(List.of(frame("R001", 1.0, 0.0, 80.0, null)));
        fleetManager.onStateTick(List.of(frame("R001", 1.0, 0.0, 80.0, null)));
        fleetManager.onStateTick(List.of(frame("R001", 1.0, 0.0, 80.0, null)));

        RobotSnapshot settled = mirror.list().get(0);
        assertThat(settled.getMode()).isEqualTo("idle");
        assertThat(settled.isDispatchable()).isTrue();
    }

    // 移动中占物理路段不占点；停稳在 waypoint（0.5m 阈值内）后转为占点
    @Test
    void onStateTick_movingRobot_dwellsOnPhysicalSegmentOnly() {
        activateMap();

        // 移动中（帧间位移超阈值）：沿 A→B 路段行驶，占段不占点（上位机直发任务交通可见）
        fleetManager.onStateTick(List.of(frame("R001", 0.5, 0.0, 80.0, "F1")));
        fleetManager.onStateTick(List.of(frame("R001", 1.5, 0.0, 80.0, "F1")));
        fleetManager.onStateTick(List.of(frame("R001", 2.5, 0.0, 80.0, "F1")));

        assertThat(trafficRegistry.occupiedSegments()).containsExactly("P1#WP_A-WP_B");
        assertThat(trafficRegistry.occupiedWaypoints()).isEmpty();

        // 停在 B 点（连续静止帧清除移动标记）：占点（点在 0.5m 阈值内优先占点）
        fleetManager.onStateTick(List.of(frame("R001", 6.0, 0.0, 80.0, "F1")));
        fleetManager.onStateTick(List.of(frame("R001", 6.0, 0.0, 80.0, "F1")));
        fleetManager.onStateTick(List.of(frame("R001", 6.0, 0.0, 80.0, "F1")));
        fleetManager.onStateTick(List.of(frame("R001", 6.0, 0.0, 80.0, "F1")));

        assertThat(trafficRegistry.occupiedWaypoints()).containsExactly("WP_B");
    }

    // 已知失联机器人占用充电位：清扫不释放，且离线帧坐标不作"已离桩"证据
    @Test
    void sweepStaleRobots_retainsChargerOccupancyForKnownOfflineRobot() {
        com.zioneer.robotscheduler.core.charger.ChargerInfo charger =
                new com.zioneer.robotscheduler.core.charger.ChargerInfo();
        charger.setChargerId("WP_A");
        charger.setRmfWaypoint("WP_A");
        charger.setOccupiedBy("R001");
        org.mockito.Mockito.when(chargerPoolMock.list(null)).thenReturn(java.util.List.of(charger));

        fleetManager.onStateTick(List.of(frame("R001", 0.5, 0.0, 80.0, null)));
        RobotSnapshot snapshot = mirror.list().get(0);
        snapshot.setUpdatedAtMillis(System.currentTimeMillis() - 16_000);

        fleetManager.sweepStaleRobots();
        activateMap();
        // 离线帧坐标不应被用作“已离开充电位”的证据。
        RobotSnapshot offline = frame("R001", 6, 0, 80.0, "F1");
        offline.setRmsOffline(true);
        fleetManager.onStateTick(List.of(offline));

        // 已知失联机器人仍可能在桩上，不能仅因离线释放。
        org.mockito.Mockito.verify(chargerPoolMock,
                org.mockito.Mockito.never()).release("R001", "WP_A", null);
    }

    @Test
    void sweepStaleRobots_releasesChargerLeftByUnknownOccupier() {
        // 重启后 DB 残留占用（占用者不在镜像/不在线）→ 对账自动释放
        com.zioneer.robotscheduler.core.charger.ChargerInfo stale =
                new com.zioneer.robotscheduler.core.charger.ChargerInfo();
        stale.setChargerId("WP_A");
        stale.setRmfWaypoint("WP_A");
        stale.setOccupiedBy("GONE_RBOT");
        org.mockito.Mockito.when(chargerPoolMock.list(null)).thenReturn(java.util.List.of(stale));

        fleetManager.onStateTick(List.of(frame("R001", 0.5, 0.0, 80.0, null)));

        fleetManager.sweepStaleRobots();

        org.mockito.Mockito.verify(chargerPoolMock).release("GONE_RBOT", "WP_A", null);
    }

    // 退役（decommissioned）帧：机器人下线、mode=decommissioned，交通保留并等待 stop
    @Test
    void onStateTick_decommissionedRobot_goesOfflineAndRetainsTraffic() {
        fleetManager.onStateTick(List.of(frame("R001", 0.5, 0.0, 80.0, null)));
        trafficRegistry.tryAcquire("R001", List.of("P1#WP_A->WP_B"), java.util.Set.of("WP_B"),
                Duration.ofSeconds(60));

        RobotSnapshot decommissioned = frame("R001", 0.5, 0.0, 80.0, null);
        decommissioned.setDecommissioned(true);
        fleetManager.onStateTick(List.of(decommissioned));

        RobotSnapshot snapshot = mirror.list().get(0);
        assertThat(snapshot.isOnline()).isFalse();
        assertThat(snapshot.isDispatchable()).isFalse();
        assertThat(snapshot.getMode()).isEqualTo("decommissioned");
        assertThat(trafficRegistry.occupiedSegments()).isNotEmpty();
        assertThat(trafficRegistry.isAwaitingStop("R001")).isTrue();
    }

    // RMS 侧离线标志帧：机器人标记下线且不可派发
    @Test
    void onStateTick_rmsOfflineFlag_marksOffline() {
        RobotSnapshot offlineFrame = frame("R001", 0.5, 0.0, 80.0, null);
        offlineFrame.setRmsOffline(true);
        fleetManager.onStateTick(List.of(offlineFrame));

        RobotSnapshot snapshot = mirror.list().get(0);
        assertThat(snapshot.isOnline()).isFalse();
        assertThat(snapshot.isDispatchable()).isFalse();
    }

    // 位置远离拓扑图（离图）：在线但不可派发
    @Test
    void onStateTick_offGraphPosition_notDispatchable() {
        activateMap();

        fleetManager.onStateTick(List.of(frame("R001", 50.0, 50.0, 80.0, "F1")));

        RobotSnapshot snapshot = mirror.list().get(0);
        assertThat(snapshot.isOnline()).isTrue();
        assertThat(snapshot.isDispatchable()).isFalse();
    }

    // 位置在拓扑图附近（吸附成功）：可派发
    @Test
    void onStateTick_onGraphPosition_dispatchable() {
        activateMap();

        fleetManager.onStateTick(List.of(frame("R001", 2.5, 0.2, 80.0, "F1")));

        assertThat(mirror.list().get(0).isDispatchable()).isTrue();
    }

    // 楼层与激活地图不符（F2）：不可派发
    @Test
    void onStateTick_wrongFloor_notDispatchable() {
        activateMap();

        fleetManager.onStateTick(List.of(frame("R001", 2.5, 0.0, 80.0, "F2")));

        assertThat(mirror.list().get(0).isDispatchable()).isFalse();
    }

    // 未加载地图（无图模式）：跳过离图判定，仍可派发
    @Test
    void onStateTick_noMapLoaded_skipGraphCheck() {
        // 无图模式：不做离图判定，可派发
        fleetManager.onStateTick(List.of(frame("R001", 100.0, 100.0, 80.0, null)));

        assertThat(mirror.list().get(0).isDispatchable()).isTrue();
    }

    // 超过 stale 阈值被清扫：机器人下线、mode=unknown，交通保留并等待 stop
    @Test
    void sweepStaleRobots_offlineAfterThresholdAndRetainsTraffic() {
        fleetManager.onStateTick(List.of(frame("R001", 0.5, 0.0, 80.0, null)));
        trafficRegistry.tryAcquire("R001", List.of("P1#WP_A->WP_B"), java.util.Set.of("WP_B"),
                Duration.ofSeconds(60));
        RobotSnapshot snapshot = mirror.list().get(0);
        snapshot.setUpdatedAtMillis(System.currentTimeMillis() - 16_000);

        fleetManager.sweepStaleRobots();

        RobotSnapshot swept = mirror.list().get(0);
        assertThat(swept.isOnline()).isFalse();
        assertThat(swept.isDispatchable()).isFalse();
        assertThat(swept.getMode()).isEqualTo("unknown");
        assertThat(trafficRegistry.occupiedSegments()).isNotEmpty();
        assertThat(trafficRegistry.isAwaitingStop("R001")).isTrue();
    }

    // 清单同步缺失机器人：标记下线不可派，但仍保留在镜像中可见
    @Test
    void onInventorySync_missingRobot_goesOfflineButStaysVisible() {
        fleetManager.onStateTick(List.of(frame("R001", 0.5, 0.0, 80.0, null)));

        fleetManager.onInventorySync(List.of());

        RobotSnapshot snapshot = mirror.list().get(0);
        assertThat(snapshot.isOnline()).isFalse();
        assertThat(snapshot.isDispatchable()).isFalse();
    }

    // 清单同步合并静态信息：home 点与兼容充电位写入镜像，在线状态不受影响
    @Test
    void onInventorySync_mergesStaticInfo() {
        fleetManager.onStateTick(List.of(frame("R001", 0.5, 0.0, 80.0, null)));
        RobotSnapshot inventoryItem = frame("R001", 0.5, 0.0, 80.0, null);
        inventoryItem.setHomeWaypoint("WP_A");
        inventoryItem.setCompatibleChargers(List.of("WP_A"));

        fleetManager.onInventorySync(List.of(inventoryItem));

        RobotSnapshot snapshot = mirror.list().get(0);
        assertThat(snapshot.getHomeWaypoint()).isEqualTo("WP_A");
        assertThat(snapshot.getCompatibleChargers()).containsExactly("WP_A");
        assertThat(snapshot.isOnline()).isTrue();
    }

    // 状态源拉取异常：镜像保留现有快照不被清空
    @Test
    void pollStates_sourceFailure_keepsExistingMirror() {
        fleetManager.onStateTick(List.of(frame("R001", 0.5, 0.0, 80.0, null)));
        org.mockito.Mockito.when(stateSource.fetchStates())
                .thenThrow(new RobotStateSourceException("rms down", new RuntimeException()));

        fleetManager.pollStates();

        assertThat(mirror.list()).hasSize(1);
        assertThat(mirror.list().get(0).isOnline()).isTrue();
    }

    // ==================== helpers ====================

    private void activateMap() {
        try {
            mapManager.activateFromJson(SIMPLE_MAP, null, null);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private RobotSnapshot frame(String robotCode, double x, double y, double battery, String mapName) {
        RobotSnapshot snapshot = new RobotSnapshot();
        snapshot.setRobotCode(robotCode);
        snapshot.setRobotName(robotCode);
        snapshot.setFleetName("RG_20260407_00001");
        snapshot.setX(x);
        snapshot.setY(y);
        snapshot.setYaw(0);
        snapshot.setBattery(battery);
        snapshot.setMap(mapName);
        return snapshot;
    }
}
