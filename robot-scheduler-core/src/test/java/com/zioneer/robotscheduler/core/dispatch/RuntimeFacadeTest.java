package com.zioneer.robotscheduler.core.dispatch;

import com.zioneer.robotscheduler.core.charger.InMemoryChargerPool;
import com.zioneer.robotscheduler.core.event.SchedulerEventBus;
import com.zioneer.robotscheduler.core.fleet.FleetStateMirror;
import com.zioneer.robotscheduler.core.fleet.RobotSnapshot;
import com.zioneer.robotscheduler.core.fleet.TaskAssignmentRegistry;
import com.zioneer.robotscheduler.core.map.MapManager;
import com.zioneer.robotscheduler.core.map.MapMetadataParser;
import com.zioneer.robotscheduler.core.repository.MapArchiveRepository;
import com.zioneer.robotscheduler.core.task.TaskStateEvent;
import com.zioneer.robotscheduler.core.task.TaskStateMirror;
import com.zioneer.robotscheduler.core.task.TaskExecutionEngine;
import com.zioneer.robotscheduler.core.testsupport.InMemoryTaskRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运行态快照与切换预检测试（断言转译自旧栈 runtime_snapshot/pre_switch_check 场景）。
 */
@ExtendWith(MockitoExtension.class)
class RuntimeFacadeTest {

    @Mock
    private ApplicationEventPublisher publisher;

    @Mock
    private MapArchiveRepository mapArchiveRepository;

    @Mock
    private TaskExecutionEngine executionEngine;

    private TaskStateMirror taskStateMirror;
    private FleetStateMirror fleetStateMirror;
    private DefaultSchedulerFacade facade;

    @BeforeEach
    void setUp() {
        taskStateMirror = new TaskStateMirror();
        fleetStateMirror = new FleetStateMirror();
        MapManager mapManager = new MapManager(new MapMetadataParser(), mapArchiveRepository);
        facade = new DefaultSchedulerFacade(new InMemoryTaskRecordRepository(), new InMemoryChargerPool(),
                taskStateMirror, fleetStateMirror, new TaskAssignmentRegistry(),
                new SchedulerEventBus(publisher), mapManager, executionEngine);
    }

    // ==================== runtime snapshot（旧 950/1021/1049/1074/1088） ====================

    @Test
    void runtimeSnapshot_usesFleetStateForLiveRobotRows() {
        fleetStateMirror.upsert(robot("R001", "fleet_A", "idle", 42.0, System.currentTimeMillis()));

        Map<String, Object> data = facade.runtimeSnapshot(null, null, null, 200);

        List<Map<String, Object>> robots = (List<Map<String, Object>>) data.get("robots");
        assertThat(robots).hasSize(1);
        Map<String, Object> row = robots.get(0);
        assertThat(row).containsEntry("robot_code", "R001");
        assertThat(row).containsEntry("online_status", "online");
        assertThat(row).containsEntry("mode", "idle");
        assertThat(row.get("dispatchable")).isEqualTo(true);
        // battery 保留 1 位小数（旧栈 0.42 → 42.0）
        assertThat(row.get("battery")).isEqualTo(42.0);
    }

    @Test
    void runtimeSnapshot_marksStaleLiveRobotOffline() {
        fleetStateMirror.upsert(robot("R001", "fleet_A", "idle", 80.0,
                System.currentTimeMillis() - 16_000));

        Map<String, Object> data = facade.runtimeSnapshot(null, null, null, 200);

        Map<String, Object> row = ((List<Map<String, Object>>) data.get("robots")).get(0);
        assertThat(row).containsEntry("online_status", "offline");
        assertThat(row.get("dispatchable")).isEqualTo(false);
    }

    @Test
    void runtimeSnapshot_countsIdleLiveRobotAsAvailable() {
        fleetStateMirror.upsert(robot("R001", "fleet_A", "idle", 80.0, System.currentTimeMillis()));

        Map<String, Object> data = facade.runtimeSnapshot(null, null, null, 200);

        Map<String, Object> fleet = ((List<Map<String, Object>>) data.get("fleets")).get(0);
        assertThat(fleet).containsEntry("fleet_name", "fleet_A");
        assertThat(fleet).containsEntry("robot_total", 1);
        assertThat(fleet).containsEntry("online_count", 1);
        assertThat(fleet).containsEntry("available_count", 1);
        assertThat(fleet).containsEntry("idle_count", 1);
        Map<String, Object> planning = (Map<String, Object>) data.get("planning");
        assertThat(planning).containsEntry("robot_count", 1);
        assertThat(planning).containsEntry("available_robot_count", 1L);
    }

    @Test
    void runtimeSnapshot_marksErrorRobotNotDispatchable() {
        fleetStateMirror.upsert(robot("R001", "fleet_A", "error", 80.0, System.currentTimeMillis()));

        Map<String, Object> data = facade.runtimeSnapshot(null, null, null, 200);

        Map<String, Object> row = ((List<Map<String, Object>>) data.get("robots")).get(0);
        assertThat(row.get("dispatchable")).isEqualTo(false);
        Map<String, Object> fleet = ((List<Map<String, Object>>) data.get("fleets")).get(0);
        assertThat(fleet).containsEntry("error_count", 1);
    }

    @Test
    void runtimeSnapshot_canFilterLiveRobotsByRmfLevel() {
        fleetStateMirror.upsert(robot("R001", "fleet_A", "L1", "idle", 80.0, System.currentTimeMillis()));
        fleetStateMirror.upsert(robot("R002", "fleet_A", "L2", "idle", 80.0, System.currentTimeMillis()));

        Map<String, Object> data = facade.runtimeSnapshot(null, null, "L2", 200);

        List<Map<String, Object>> robots = (List<Map<String, Object>>) data.get("robots");
        assertThat(robots).hasSize(1);
        assertThat(robots.get(0)).containsEntry("robot_code", "R002");
    }

    @Test
    void runtimeSnapshot_filtersByFleetName() {
        fleetStateMirror.upsert(robot("R001", "fleet_A", "idle", 80.0, System.currentTimeMillis()));
        fleetStateMirror.upsert(robot("R002", "fleet_B", "idle", 80.0, System.currentTimeMillis()));

        Map<String, Object> data = facade.runtimeSnapshot(null, "fleet_B", null, 200);

        assertThat((List<?>) data.get("robots")).hasSize(1);
        assertThat((List<Map<String, Object>>) data.get("fleets")).hasSize(1);
        assertThat(((List<Map<String, Object>>) data.get("fleets")).get(0))
                .containsEntry("fleet_name", "fleet_B");
    }

    @Test
    void runtimeSnapshot_runningTaskAttachesToRobotRowAndFleetCounters() {
        fleetStateMirror.upsert(robot("R001", "fleet_A", "running", 80.0, System.currentTimeMillis()));
        taskStateMirror.upsert(new TaskStateEvent("go_to_place.dispatch-001", "running", "go_to_place",
                Map.of(), Map.of(), "fleet_A", "R001", null, System.currentTimeMillis()));

        Map<String, Object> data = facade.runtimeSnapshot(null, null, null, 200);

        Map<String, Object> row = ((List<Map<String, Object>>) data.get("robots")).get(0);
        assertThat(row).containsEntry("rmf_task_id", "go_to_place.dispatch-001");
        assertThat(row).containsEntry("task_status", "running");
        Map<String, Object> fleet = ((List<Map<String, Object>>) data.get("fleets")).get(0);
        assertThat(fleet).containsEntry("queued_task_count", 1);
        assertThat(fleet).containsEntry("running_task_count", 1);
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) data.get("tasks");
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0)).containsEntry("task_id", "go_to_place.dispatch-001");
        assertThat(tasks.get(0)).containsEntry("robot_code", "R001");
    }

    @Test
    void runtimeSnapshot_excludesTerminalTasksAndClampsTaskLimit() {
        taskStateMirror.upsert(new TaskStateEvent("qi.completed-001", "completed", "quality_inspection",
                Map.of(), Map.of(), null, "R001", null, System.currentTimeMillis()));

        Map<String, Object> data = facade.runtimeSnapshot(null, null, null, 200);

        assertThat((List<?>) data.get("tasks")).isEmpty();
        assertThat(data).containsEntry("task_total", 0);

        Map<String, Object> clamped = facade.runtimeSnapshot(null, null, null, 10_000);
        assertThat(clamped).containsEntry("task_list_limit", 1000);
    }

    @Test
    void runtimeSnapshot_chargerSummaryCounts() {
        Map<String, Object> data = facade.runtimeSnapshot(null, null, null, 200);

        Map<String, Object> chargers = (Map<String, Object>) data.get("chargers");
        assertThat(chargers).containsEntry("total", 0);
        assertThat(chargers).containsEntry("available", 0);
        assertThat(chargers).containsKey("items");
        assertThat(chargers).containsKey("last_error");
    }

    @Test
    void runtimeSnapshot_containsComponentsSection() {
        Map<String, Object> data = facade.runtimeSnapshot(null, null, null, 200);

        List<Map<String, Object>> components = (List<Map<String, Object>>) data.get("components");
        assertThat(components).isNotEmpty();
        assertThat(components.get(0)).containsKey("component");
        assertThat(components.get(0)).containsKey("status");
    }

    // ==================== pre-switch-check（旧测试第 8 节场景） ====================

    @Test
    void preSwitchCheck_noFleetStates_returnsUnknownAndAllowed() {
        Map<String, Object> data = facade.preSwitchCheck("scene-2", null);

        assertThat(data).containsEntry("allowed", true);
        assertThat(data).containsEntry("check_result", "unknown");
        assertThat((List<?>) data.get("reasons")).isEmpty();
    }

    @Test
    void preSwitchCheck_idleRobotsNoTasks_returnsReady() {
        fleetStateMirror.upsert(robot("R001", "fleet_A", "idle", 80.0, System.currentTimeMillis()));
        fleetStateMirror.upsert(robot("R002", "fleet_A", "charging", 20.0, System.currentTimeMillis()));

        Map<String, Object> data = facade.preSwitchCheck("scene-2", "fleet_A");

        assertThat(data).containsEntry("allowed", true);
        assertThat(data).containsEntry("check_result", "ready");
        Map<String, Object> robots = (Map<String, Object>) data.get("robots");
        assertThat(robots).containsEntry("total", 2);
        assertThat(robots).containsEntry("idle", 2);
        assertThat(robots).containsEntry("busy", 0);
        assertThat(data).containsKey("checked_at");
    }

    @Test
    void preSwitchCheck_runningRobotBlocks() {
        fleetStateMirror.upsert(robot("R001", "fleet_A", "running", 80.0, System.currentTimeMillis()));

        Map<String, Object> data = facade.preSwitchCheck("scene-2", "fleet_A");

        assertThat(data).containsEntry("allowed", false);
        assertThat(data).containsEntry("check_result", "robots_running");
        Map<String, Object> robots = (Map<String, Object>) data.get("robots");
        assertThat((List<?>) robots.get("busy_robots")).hasSize(1);
    }

    @Test
    void preSwitchCheck_pendingTaskBlocksWithoutBusyRobots() {
        fleetStateMirror.upsert(robot("R001", "fleet_A", "idle", 80.0, System.currentTimeMillis()));
        taskStateMirror.upsert(new TaskStateEvent("qi.dispatch-001", "queued", "quality_inspection",
                Map.of(), Map.of(), "fleet_A", "R001", null, System.currentTimeMillis()));

        Map<String, Object> data = facade.preSwitchCheck("scene-2", "fleet_A");

        assertThat(data).containsEntry("allowed", false);
        assertThat(data).containsEntry("check_result", "tasks_pending");
        Map<String, Object> tasks = (Map<String, Object>) data.get("tasks");
        assertThat(tasks).containsEntry("pending", 1);
        assertThat((List<?>) tasks.get("pending_tasks")).hasSize(1);
    }

    @Test
    void preSwitchCheck_pausedRobotDoesNotBlock() {
        fleetStateMirror.upsert(robot("R001", "fleet_A", "paused", 80.0, System.currentTimeMillis()));

        Map<String, Object> data = facade.preSwitchCheck("scene-2", "fleet_A");

        assertThat(data).containsEntry("allowed", true);
        assertThat(data).containsEntry("check_result", "ready");
    }

    @Test
    void preSwitchCheck_completedTasksDoNotBlock() {
        fleetStateMirror.upsert(robot("R001", "fleet_A", "idle", 80.0, System.currentTimeMillis()));
        taskStateMirror.upsert(new TaskStateEvent("qi.dispatch-001", "completed", "quality_inspection",
                Map.of(), Map.of(), "fleet_A", "R001", null, System.currentTimeMillis()));

        Map<String, Object> data = facade.preSwitchCheck("scene-2", "fleet_A");

        assertThat(data).containsEntry("allowed", true);
        Map<String, Object> tasks = (Map<String, Object>) data.get("tasks");
        assertThat(tasks).containsEntry("pending", 0);
    }

    // ==================== helpers ====================

    private RobotSnapshot robot(String code, String fleet, String mode, double battery, long updatedAt) {
        return robot(code, fleet, "L1", mode, battery, updatedAt);
    }

    private RobotSnapshot robot(String code, String fleet, String level, String mode, double battery,
                                long updatedAt) {
        RobotSnapshot snapshot = new RobotSnapshot();
        snapshot.setRobotCode(code);
        snapshot.setRobotName(code);
        snapshot.setFleetName(fleet);
        snapshot.setMap(level);
        snapshot.setMode(mode);
        snapshot.setBattery(battery);
        snapshot.setOnline(true);
        // 模拟 FleetManager 的引擎判定（生产路径由 evaluateDispatchable 设置，含地图/离图检查）
        snapshot.setDispatchable(!"error".equals(mode) && !"unknown".equals(mode)
                && !"paused".equals(mode) && !"decommissioned".equals(mode));
        snapshot.setUpdatedAtMillis(updatedAt);
        return snapshot;
    }
}
