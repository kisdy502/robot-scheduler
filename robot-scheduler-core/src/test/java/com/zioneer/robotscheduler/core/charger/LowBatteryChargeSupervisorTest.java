package com.zioneer.robotscheduler.core.charger;

import com.zioneer.robotscheduler.core.fleet.TaskAssignmentRegistry;
import com.zioneer.robotscheduler.core.fleet.RobotAvailabilityService;
import com.zioneer.robotscheduler.core.traffic.InMemoryTrafficRegistry;
import com.zioneer.robotscheduler.core.fleet.FleetStateMirror;
import com.zioneer.robotscheduler.core.fleet.RobotSnapshot;
import com.zioneer.robotscheduler.core.task.TaskExecutionEngine;
import com.zioneer.robotscheduler.core.task.TaskStateEvent;
import com.zioneer.robotscheduler.core.task.TaskStateMirror;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 低电量返充监督测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LowBatteryChargeSupervisorTest {
    private final TaskAssignmentRegistry ownershipRegistry = new TaskAssignmentRegistry();

    @Mock
    private TaskExecutionEngine executionEngine;

    private FleetStateMirror mirror;
    private TaskStateMirror taskStateMirror;
    private InMemoryChargerPool chargerPool;
    private LowBatteryChargeSupervisor supervisor;

    // 低电量机器人持有使用权或执行外部作业时不自动返充，结束作业并释放使用权后才恢复资格。
    @Test
    void claimedAndExternallyWorkingRobotNeverRetreatsAutomatically() {
        var robot = robot("R1", 5.0, "idle");
        ownershipRegistry.assign("flow1", "R1");
        assertThat(supervisor.shouldRetreat(robot)).isFalse();
        ownershipRegistry.startOperation("flow1", "R1", "op");
        assertThat(supervisor.shouldRetreat(robot)).isFalse();
        ownershipRegistry.finishOperation("flow1", "R1", "op");
        assertThat(supervisor.shouldRetreat(robot)).isFalse();
        ownershipRegistry.release("flow1", "R1");
        assertThat(supervisor.shouldRetreat(robot)).isTrue();
    }

    @BeforeEach
    void setUp() {
        mirror = new FleetStateMirror();
        taskStateMirror = new TaskStateMirror();
        chargerPool = new InMemoryChargerPool();
        LowBatteryChargeSupervisor.ChargeProperties properties =
                new LowBatteryChargeSupervisor.ChargeProperties();
        supervisor = new LowBatteryChargeSupervisor(mirror, taskStateMirror, chargerPool,
                new com.zioneer.robotscheduler.core.map.MapManager(
                        new com.zioneer.robotscheduler.core.map.MapMetadataParser(),
                        org.mockito.Mockito.mock(
                                com.zioneer.robotscheduler.core.repository.MapArchiveRepository.class)),
                executionEngine,
                org.mockito.Mockito.mock(
                        com.zioneer.robotscheduler.core.repository.TaskRecordRepository.class),
                properties,
                new RobotAvailabilityService(ownershipRegistry, new InMemoryTrafficRegistry()));
    }

    private RobotSnapshot robot(String code, Double battery, String mode) {
        RobotSnapshot snapshot = new RobotSnapshot();
        snapshot.setRobotCode(code);
        snapshot.setFleetName("fleet_A");
        snapshot.setBattery(battery);
        snapshot.setMode(mode);
        snapshot.setOnline(true);
        snapshot.setDispatchable(true);
        snapshot.setUpdatedAtMillis(System.currentTimeMillis());
        return snapshot;
    }

    @Test
    void scan_lowBatteryIdleRobot_submitsChargeTask() {
        mirror.upsert(robot("R_LOW", 15.0, "idle"));
        var charger = new ChargerInfo();
        charger.setChargerId("CHG_1");
        charger.setRmfWaypoint("CHG_1");
        charger.setEnabled(true);
        charger.setStatus(0);
        chargerPool.register(charger);

        supervisor.scan();

        verify(executionEngine).submit(any(), eq("R_LOW"), any());
    }

    @Test
    void scan_healthyBattery_noTask() {
        mirror.upsert(robot("R_OK", 80.0, "idle"));

        supervisor.scan();

        verify(executionEngine, org.mockito.Mockito.never()).submit(any(), anyString(), any());
    }

    @Test
    void scan_runningRobot_notRetreated() {
        mirror.upsert(robot("R_BUSY", 15.0, "running"));

        supervisor.scan();

        verify(executionEngine, org.mockito.Mockito.never()).submit(any(), anyString(), any());
    }

    @Test
    void scan_offlineRobot_notRetreated() {
        RobotSnapshot offline = robot("R_OFF", 15.0, "idle");
        offline.setOnline(false);
        mirror.upsert(offline);

        supervisor.scan();

        verify(executionEngine, org.mockito.Mockito.never()).submit(any(), anyString(), any());
    }

    @Test
    void scan_retreatInFlight_notDuplicated() {
        registerCharger();
        mirror.upsert(robot("R_LOW", 15.0, "idle"));
        supervisor.scan();

        supervisor.scan();

        verify(executionEngine, org.mockito.Mockito.times(1)).submit(any(), eq("R_LOW"), any());
    }

    @Test
    void scan_retreatFinished_canRetreatAgain() {
        registerCharger();
        mirror.upsert(robot("R_LOW", 15.0, "idle"));
        supervisor.scan();
        // 模拟充电任务已失败（终态）：可再次发起
        var record = org.mockito.Mockito.mock(com.zioneer.robotscheduler.core.repository.TaskRecord.class);
        ArgumentCaptor<com.zioneer.robotscheduler.core.repository.TaskRecord> recordCaptor =
                ArgumentCaptor.forClass(com.zioneer.robotscheduler.core.repository.TaskRecord.class);
        verify(executionEngine).submit(recordCaptor.capture(), eq("R_LOW"), any());
        taskStateMirror.upsert(new TaskStateEvent("charge.x", "failed", "charge",
                Map.of(), Map.of(), null, "R_LOW", recordCaptor.getValue().getSchedulerRequestId(),
                System.currentTimeMillis()));

        supervisor.scan();

        verify(executionEngine, org.mockito.Mockito.times(2)).submit(any(), eq("R_LOW"), any());
    }

    @Test
    void shouldRetreat_nullBattery_false() {
        assertThat(supervisor.shouldRetreat(robot("R_NA", null, "idle"))).isFalse();
    }

    private void registerCharger() {
        var charger = new ChargerInfo();
        charger.setChargerId("CHG_1");
        charger.setRmfWaypoint("CHG_1");
        charger.setEnabled(true);
        charger.setStatus(0);
        chargerPool.register(charger);
    }
}
