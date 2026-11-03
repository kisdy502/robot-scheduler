package com.zioneer.robotscheduler.core.assign;

import com.zioneer.robotscheduler.core.fleet.TaskAssignmentRegistry;
import com.zioneer.robotscheduler.core.fleet.RobotAvailabilityService;
import com.zioneer.robotscheduler.core.traffic.InMemoryTrafficRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zioneer.robotscheduler.core.fleet.FleetStateMirror;
import com.zioneer.robotscheduler.core.fleet.RobotSnapshot;
import com.zioneer.robotscheduler.core.map.MapManager;
import com.zioneer.robotscheduler.core.map.MapMetadataParser;
import com.zioneer.robotscheduler.core.repository.MapArchiveRepository;
import com.zioneer.robotscheduler.core.task.TaskGoal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * 代价分配器测试：候选筛选（fleet 过滤/可派发/忙排除）、电量惩罚、ETA 就近选择。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CostBasedTaskAssignerTest {
    private final TaskAssignmentRegistry ownershipRegistry = new TaskAssignmentRegistry();

    private static final String MAP = """
            {
              "versionInfo": {"version": "v1", "status": "published"},
              "mapInfo": {"mapName": "AssignMap", "floors": [{
                "floorId": "F1", "defaultMaxSpeed": 1.0,
                "waypoints": [
                  {"id": "WP_A", "position": [0, 0, 0], "type": "charge"},
                  {"id": "WP_B", "position": [10, 0, 0], "type": "pickup"}
                ],
                "paths": [{"id": "P1", "from": "WP_A", "to": "WP_B",
                  "waypoints": ["WP_A", "WP_B"], "isBidirectional": true}]
              }]}
            }
            """;

    @Mock
    private MapArchiveRepository mapArchiveRepository;

    private FleetStateMirror mirror;
    private CostBasedTaskAssigner assigner;

    // 已取得使用权的机器人仅允许持有者自动选中，外部作业期间连持有者也应跳过。
    @Test
    void claimedRobotIsOnlySelectableByItsOwnerEvenWhenGloballyUnavailable() {
        var claimed = robot("R1", "fleet", 0, 80.0, "idle");
        claimed.setNavigationReady(true);
        claimed.setDispatchable(false);
        mirror.upsert(claimed);
        ownershipRegistry.assign("flow1", "R1");
        var goal = new TaskGoal("go_to_place", null, "WP_B", null, null, java.util.Map.of());
        assertThat(assigner.selectWithTrace(goal, null, null).selected()).isEmpty();
        assertThat(assigner.selectWithTrace(goal, null, null, "flow1").selected()).isPresent();
        ownershipRegistry.startOperation("flow1", "R1", "op");
        assertThat(assigner.selectWithTrace(goal, null, null, "flow1").selected()).isEmpty();
    }

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(mapArchiveRepository.findActive()).thenReturn(java.util.Optional.empty());
        lenient().when(mapArchiveRepository.findByMapNameAndVersion(anyString(), anyString()))
                .thenReturn(java.util.Optional.empty());
        MapManager mapManager = new MapManager(new MapMetadataParser(), mapArchiveRepository);
        mapManager.activateFromJson(MAP, null, null);
        mirror = new FleetStateMirror();
        assigner = new CostBasedTaskAssigner(mirror, mapManager, new TaskAssignProperties(),
                new RobotAvailabilityService(ownershipRegistry, new InMemoryTrafficRegistry()));
    }

    private RobotSnapshot robot(String code, String fleet, double x, Double battery, String mode) {
        RobotSnapshot snapshot = new RobotSnapshot();
        snapshot.setRobotCode(code);
        snapshot.setRobotName(code);
        snapshot.setFleetName(fleet);
        snapshot.setFloorId("F1");
        snapshot.setX(x);
        snapshot.setY(0);
        snapshot.setBattery(battery);
        snapshot.setMode(mode);
        snapshot.setOnline(true);
        snapshot.setDispatchable(true);
        snapshot.setUpdatedAtMillis(System.currentTimeMillis());
        return snapshot;
    }

    private TaskGoal goalToB() {
        return new TaskGoal("go_to_place", null, "WP_B", null, null, Map.of());
    }

    // 无任何候选机器人：select 返回空
    @Test
    void select_noCandidates_returnsEmpty() {
        assertThat(assigner.select(goalToB(), null, null)).isEmpty();
    }

    // 两台空闲车电量相同：选 ETA 更近（离目标更近）的那台
    @Test
    void select_prefersCloserRobot() {
        mirror.upsert(robot("R_FAR", "fleet_A", 0.5, 80.0, "idle"));
        mirror.upsert(robot("R_NEAR", "fleet_A", 9.5, 80.0, "idle"));

        Optional<RobotSnapshot> selected = assigner.select(goalToB(), null, null);

        assertThat(selected).map(RobotSnapshot::getRobotCode).contains("R_NEAR");
    }

    // 低电量(8%)候选：电量惩罚压过分距离优势，选远但电量健康的车
    @Test
    void select_lowBatteryHeavilyPenalized() {
        // 近但低电量 vs 远但电量健康：BatteryAware 下选远者
        mirror.upsert(robot("R_NEAR_LOW", "fleet_A", 9.5, 8.0, "idle"));
        mirror.upsert(robot("R_FAR_OK", "fleet_A", 0.5, 90.0, "idle"));

        Optional<RobotSnapshot> selected = assigner.select(goalToB(), null, null);

        assertThat(selected).map(RobotSnapshot::getRobotCode).contains("R_FAR_OK");
    }

    // running 忙碌机器人被排除出候选：只能选中空闲的 R_IDLE
    @Test
    void select_busyRobotExcluded() {
        mirror.upsert(robot("R_BUSY", "fleet_A", 9.5, 90.0, "running"));
        mirror.upsert(robot("R_IDLE", "fleet_A", 0.5, 90.0, "idle"));

        Optional<RobotSnapshot> selected = assigner.select(goalToB(), null, null);

        assertThat(selected).map(RobotSnapshot::getRobotCode).contains("R_IDLE");
    }

    // 不可派发（dispatchable=false）机器人被排除：即使更近也不参选
    @Test
    void select_undispatchableRobotExcluded() {
        RobotSnapshot offline = robot("R_OFF", "fleet_A", 9.5, 90.0, "idle");
        offline.setDispatchable(false);
        mirror.upsert(offline);
        mirror.upsert(robot("R_ON", "fleet_A", 0.5, 90.0, "idle"));

        Optional<RobotSnapshot> selected = assigner.select(goalToB(), null, null);

        assertThat(selected).map(RobotSnapshot::getRobotCode).contains("R_ON");
    }

    // 指定车队过滤：只从 fleet_A 候选中选择，其他车队机器人不参选
    @Test
    void select_fleetFilterApplies() {
        mirror.upsert(robot("R_OTHER_FLEET", "fleet_B", 9.5, 90.0, "idle"));
        mirror.upsert(robot("R_TARGET_FLEET", "fleet_A", 0.5, 90.0, "idle"));

        Optional<RobotSnapshot> selected = assigner.select(goalToB(), "fleet_A", null);

        assertThat(selected).map(RobotSnapshot::getRobotCode).contains("R_TARGET_FLEET");
    }

    // 指定车队内无任何机器人：select 返回空
    @Test
    void select_noRobotInFleet_returnsEmpty() {
        mirror.upsert(robot("R_B", "fleet_B", 9.5, 90.0, "idle"));

        assertThat(assigner.select(goalToB(), "fleet_A", null)).isEmpty();
    }

    // 唯一候选处于忙碌：全部排除后 select 返回空
    @Test
    void select_allBusy_returnsEmpty() {
        mirror.upsert(robot("R1", "fleet_A", 9.5, 90.0, "running"));

        assertThat(assigner.select(goalToB(), null, null)).isEmpty();
    }

    // 电量充足(85%)的充电机可中断参选但带打断代价：同等条件下空闲机胜出
    @Test
    void select_chargingSafeToInterrupt_candidatesWithInterruptPenalty() {
        // 电量充足（85%，预计任务后仍远高于返充线+余量=40%）：可中断参选，但带打断代价——
        // 同等条件（同位置同 ETA）下空闲机胜
        mirror.upsert(robot("R_CHARGING", "fleet_A", 9.5, 85.0, "charging"));
        mirror.upsert(robot("R_IDLE_SAME", "fleet_A", 9.5, 85.0, "idle"));

        Optional<RobotSnapshot> selected = assigner.select(goalToB(), null, null);

        assertThat(selected).map(RobotSnapshot::getRobotCode).contains("R_IDLE_SAME");
    }

    // 打断代价(5s)小于距离优势：中断近处充电机胜过派远处空闲机
    @Test
    void select_chargingSafe_beatsFarIdleWhenPenaltySmall() {
        // 打断代价注入 5s：近充机的距离优势（ETA 1s vs 19s）超过代价 → 中断充电响应
        TaskAssignProperties props = new TaskAssignProperties();
        props.setChargingInterruptPenaltySeconds(5);
        MapManager mapManager = new MapManager(new MapMetadataParser(), mapArchiveRepository);
        mapManager.activateFromJson(MAP, null, null);
        assigner = new CostBasedTaskAssigner(mirror, mapManager, props,
                new RobotAvailabilityService(ownershipRegistry, new InMemoryTrafficRegistry()));
        mirror.upsert(robot("R_CHARGING_NEAR", "fleet_A", 9.5, 85.0, "charging"));
        mirror.upsert(robot("R_IDLE_FAR", "fleet_A", 0.5, 85.0, "idle"));

        Optional<RobotSnapshot> selected = assigner.select(goalToB(), null, null);

        assertThat(selected).map(RobotSnapshot::getRobotCode).contains("R_CHARGING_NEAR");
    }

    // 电量38%预计任务后低于返充线+余量：中断充电不安全，直接排除（返回空）
    @Test
    void select_chargingUnsafeToInterrupt_excluded() {
        // 电量 38%：预计任务后（38% - 耗电）< 返充线 30% + 余量 10% = 40% → 中断不安全，不参选
        mirror.upsert(robot("R_CHARGING_LOW", "fleet_A", 9.5, 38.0, "charging"));

        assertThat(assigner.select(goalToB(), null, null)).isEmpty();
    }
}
