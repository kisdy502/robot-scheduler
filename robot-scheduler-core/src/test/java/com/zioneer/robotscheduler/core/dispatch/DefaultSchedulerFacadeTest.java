package com.zioneer.robotscheduler.core.dispatch;

import com.zioneer.robotscheduler.common.scheduler.task.DispatchStatus;
import com.zioneer.robotscheduler.common.scheduler.task.TaskCategories;
import com.zioneer.robotscheduler.core.charger.ChargerInfo;
import com.zioneer.robotscheduler.core.charger.InMemoryChargerPool;
import com.zioneer.robotscheduler.core.event.SchedulerEventBus;
import com.zioneer.robotscheduler.core.fleet.FleetStateMirror;
import com.zioneer.robotscheduler.core.fleet.RobotSnapshot;
import com.zioneer.robotscheduler.core.fleet.TaskAssignmentRegistry;
import com.zioneer.robotscheduler.core.map.MapManager;
import com.zioneer.robotscheduler.core.map.MapMetadataParser;
import com.zioneer.robotscheduler.core.repository.MapArchiveRepository;
import com.zioneer.robotscheduler.core.repository.TaskRecord;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 调度门面行为测试（断言逐条转译自旧栈 test_rest_api.py 的对应场景）。
 */
@ExtendWith(MockitoExtension.class)
class DefaultSchedulerFacadeTest {

    @Mock
    private ApplicationEventPublisher publisher;

    @Mock
    private MapArchiveRepository mapArchiveRepository;

    @Mock
    private TaskExecutionEngine executionEngine;

    private InMemoryTaskRecordRepository repository;
    private InMemoryChargerPool chargerPool;
    private TaskStateMirror taskStateMirror;
    private FleetStateMirror fleetStateMirror;
    private TaskAssignmentRegistry assignmentRegistry;
    private DefaultSchedulerFacade facade;

    private static final String TYPE_MAP = """
            {
              "versionInfo": {"version": "v1", "status": "published"},
              "mapInfo": {"mapName": "TypeMap", "floors": [{
                "floorId": "F1", "defaultMaxSpeed": 1.0,
                "waypoints": [
                  {"id": "W1", "name": "作业点1", "position": [0, 0, 0], "type": "workstation"},
                  {"id": "C1", "name": "充电位1", "position": [6, 0, 0], "type": "charge"},
                  {"id": "P1", "name": "通行点1", "position": [3, 0, 0], "type": "path"}
                ],
                "paths": []
              }]}
            }
            """;

    @BeforeEach
    void setUp() throws Exception {
        repository = new InMemoryTaskRecordRepository();
        chargerPool = new InMemoryChargerPool();
        taskStateMirror = new TaskStateMirror();
        fleetStateMirror = new FleetStateMirror();
        assignmentRegistry = new TaskAssignmentRegistry();
        MapManager mapManager = new MapManager(new MapMetadataParser(), mapArchiveRepository);
        mapManager.activateFromJson(TYPE_MAP, null, null);
        facade = new DefaultSchedulerFacade(repository, chargerPool, taskStateMirror,
                fleetStateMirror, assignmentRegistry, new SchedulerEventBus(publisher), mapManager,
                executionEngine);
    }

    // ==================== assign-task / release-robot（旧 2084/2093/1304/2102） ====================

    @Test
    void assignTask_withoutRobotCode_returns501WithFixedDetail() {
        assertThatThrownBy(() -> facade.assignTask("1001", null, null, null, null, "P001", null))
                .isInstanceOf(SchedulerHttpException.class)
                .satisfies(e -> {
                    SchedulerHttpException http = (SchedulerHttpException) e;
                    assertThat(http.getHttpStatus()).isEqualTo(501);
                    assertThat(http.getMessage()).isEqualTo(
                            "automatic robot allocation requires fleet state integration; "
                                    + "robot_code is required for now");
                });
    }

    @Test
    void assignTask_withRobotCode_returnsRobotCodeData() {
        Map<String, Object> data = facade.assignTask("1001", null, "R009", null, null, null, null);

        assertThat(data).containsEntry("robot_code", "R009");
        assertThat(assignmentRegistry.isAssigned("1001")).isTrue();
    }

    @Test
    void assignTask_allowsMissingNavPointStart() {
        Map<String, Object> data = facade.assignTask("1001", null, "R001", null, null, null, null);

        assertThat(data).containsEntry("robot_code", "R001");
    }

    @Test
    void releaseRobot_withoutRobotCode_releasesTaskflowAssignments() {
        facade.assignTask("1001", null, "R001", null, null, "P001", null);
        facade.assignTask("1001", null, "R002", null, null, "P002", null);

        Map<String, Object> data = facade.releaseRobot("1001", null);

        assertThat(data).isNull();
        assertThat(assignmentRegistry.isAssigned("1001")).isFalse();
    }

    @Test
    void releaseRobot_withoutTaskflowId_returns400() {
        assertThatThrownBy(() -> facade.releaseRobot("", "R001"))
                .isInstanceOf(SchedulerHttpException.class)
                .satisfies(e -> assertThat(((SchedulerHttpException) e).getHttpStatus()).isEqualTo(400));
    }

    // ==================== goto-place（旧 1339） ====================

    @Test
    void gotoPlace_returnsPendingDispatchData() {
        Map<String, Object> data = facade.gotoPlace("1001", "2001", "R001", "inspection_start_1",
                "default", null, "NAV_SUPPLIES", null, Map.of());

        assertThat(data.get("scheduler_request_id")).isNotNull();
        assertThat(data).containsEntry("business_task_id", "2001");
        assertThat(data).containsEntry("dispatch_status", "published");
        assertThat(data).containsEntry("task_status", "queued");
        assertThat(data.get("rmf_response_received")).isEqualTo(false);
        assertThat(data.get("task_state_received")).isEqualTo(false);
        assertThat(data.get("execution_final")).isEqualTo(false);
        assertThat((String) data.get("message")).contains("正在等待调度方确认");
    }

    @Test
    void gotoPlace_withoutNavTargetPoint_returns400() {
        assertThatThrownBy(() -> facade.gotoPlace("1001", "2001", "R001", " ",
                null, null, null, null, Map.of()))
                .isInstanceOf(SchedulerHttpException.class)
                .satisfies(e -> assertThat(((SchedulerHttpException) e).getHttpStatus()).isEqualTo(400));
    }

    // ==================== dispatch_task 校验（旧 2989/3026/3036/1755/1519/1535/1717） ====================

    @Test
    void dispatchTask_normalizesBatterySwapAlias() {
        facade.dispatchTask("tf", "task-1", "R001", null, null, null, null,
                "battery_swap", null, "swap_station_1", "standby", null, Map.of());

        TaskRecord record = repository.findByBusinessTaskId("task-1").orElseThrow();
        assertThat(record.getCategory()).isEqualTo(TaskCategories.SWAP_BATTERY);
    }

    @Test
    void dispatchTask_rejectsRemovedReplaceBatteryNames() {
        for (String removed : List.of("replace_battery", "replaceBattery")) {
            assertThatThrownBy(() -> facade.dispatchTask("tf", "task-x", "R001", null, null, null, null,
                    removed, null, "swap_station_1", "standby", null, Map.of()))
                    .isInstanceOf(SchedulerHttpException.class)
                    .satisfies(e -> {
                        SchedulerHttpException http = (SchedulerHttpException) e;
                        assertThat(http.getHttpStatus()).isEqualTo(400);
                        assertThat(http.getMessage()).contains("swap_battery");
                    });
        }
    }

    @Test
    void dispatchTask_rejectsInvalidEndAction() {
        assertThatThrownBy(() -> facade.dispatchTask("tf", "task-1", null, null, null, null, null,
                "quality_inspection", null, null, "invalid", null, Map.of()))
                .isInstanceOf(SchedulerHttpException.class)
                .satisfies(e -> assertThat(((SchedulerHttpException) e).getHttpStatus()).isEqualTo(400));
    }

    @Test
    void dispatchTask_rejectsInvalidEarliestStartTime() {
        assertThatThrownBy(() -> facade.dispatchTask("tf", "task-1", null, null, null, null, null,
                "quality_inspection", null, null, "standby", "not-a-number", Map.of()))
                .isInstanceOf(SchedulerHttpException.class)
                .satisfies(e -> assertThat(((SchedulerHttpException) e).getHttpStatus()).isEqualTo(400));
    }

    @Test
    void dispatchTask_rejectsMismatchedFleetAndRobotGroup() {
        assertThatThrownBy(() -> facade.dispatchTask("tf", "task-1", null, "RG001", "RG002", null, null,
                "quality_inspection", null, null, "standby", null, Map.of()))
                .isInstanceOf(SchedulerHttpException.class)
                .satisfies(e -> {
                    SchedulerHttpException http = (SchedulerHttpException) e;
                    assertThat(http.getHttpStatus()).isEqualTo(400);
                    assertThat(http.getMessage()).contains("fleet_name and robot_group must match");
                });
    }

    @Test
    void dispatchTask_returnHome_rejectsMissingRobotCode() {
        assertThatThrownBy(() -> facade.dispatchTask("tf", "task-home", null, null, "RG001", null, null,
                "return_home", null, null, "standby", null, Map.of("home_waypoint", "home_A")))
                .isInstanceOf(SchedulerHttpException.class)
                .satisfies(e -> {
                    SchedulerHttpException http = (SchedulerHttpException) e;
                    assertThat(http.getHttpStatus()).isEqualTo(400);
                    assertThat(http.getMessage()).isEqualTo("robot_code is required for return_home");
                });
    }

    @Test
    void dispatchTask_returnHome_rejectsMissingHomeWaypoint() {
        assertThatThrownBy(() -> facade.dispatchTask("tf", "task-home", "R001", null, "RG001", null, null,
                "return_home", null, null, "standby", null, Map.of("dock_at_home", false)))
                .isInstanceOf(SchedulerHttpException.class)
                .satisfies(e -> {
                    SchedulerHttpException http = (SchedulerHttpException) e;
                    assertThat(http.getHttpStatus()).isEqualTo(400);
                    assertThat(http.getMessage()).isEqualTo(
                            "parameters.home_waypoint or nav_end_point is required for return_home");
                });
    }

    @Test
    void dispatchTask_returnHome_rejectsHomeWaypointConflict() {
        assertThatThrownBy(() -> facade.dispatchTask("tf", "task-home", "R001", null, null, null, null,
                "returnHome", null, "home_B", "standby", null, Map.of("home_waypoint", "home_A")))
                .isInstanceOf(SchedulerHttpException.class)
                .satisfies(e -> assertThat(((SchedulerHttpException) e).getHttpStatus()).isEqualTo(409));
    }

    @Test
    void dispatchTask_returnHome_usesNavEndPointAsHomeWaypoint() {
        Map<String, Object> data = facade.dispatchTask("tf", "task-home", "R001", null, null, null, null,
                "return_home", null, "home_A", "standby", null, Map.of());

        assertThat(data).containsEntry("dispatch_status", "published");
        assertThat(repository.findByBusinessTaskId("task-home").orElseThrow().getCategory())
                .isEqualTo(TaskCategories.RETURN_HOME);
    }

    // ==================== stop-task（旧 1768/1819/1853/2008/2016/2052） ====================

    @Test
    void stopTask_requiresTaskIdOrRobotCode() {
        assertThatThrownBy(() -> facade.stopTask(null, null, null, "cleanup"))
                .isInstanceOf(SchedulerHttpException.class)
                .satisfies(e -> assertThat(((SchedulerHttpException) e).getHttpStatus()).isEqualTo(400));
    }

    @Test
    void stopTask_missingRecord_returns404() {
        assertThatThrownBy(() -> facade.stopTask("missing-task", null, null, null))
                .isInstanceOf(SchedulerHttpException.class)
                .satisfies(e -> {
                    SchedulerHttpException http = (SchedulerHttpException) e;
                    assertThat(http.getHttpStatus()).isEqualTo(404);
                    assertThat(http.getMessage()).isEqualTo("task status record not found");
                });
    }

    @Test
    void stopTask_requiresRmfTaskIdBeforeCancel() {
        facade.dispatchTask("tf", "task-1", "R001", null, null, null, null,
                "quality_inspection", null, null, "standby", null, Map.of());

        assertThatThrownBy(() -> facade.stopTask("task-1", null, null, null))
                .isInstanceOf(SchedulerHttpException.class)
                .satisfies(e -> {
                    SchedulerHttpException http = (SchedulerHttpException) e;
                    assertThat(http.getHttpStatus()).isEqualTo(409);
                    assertThat(http.getMessage()).isEqualTo(
                            "task has not received an RMF task id yet and cannot be canceled");
                });
    }

    @Test
    void stopTask_byTaskId_marksCancelRequested() {
        String requestId = dispatchAndInjectKernelTask("task-1", "R001", "qi.dispatch-1", "underway");

        Map<String, Object> data = facade.stopTask("task-1", null, null, "operator request");

        assertThat(data).containsEntry("task_id", "task-1");
        assertThat(data.get("stop_request_id")).isNotNull();
        assertThat(data).containsEntry("task_status", "cancel_requested");
        assertThat(data).containsEntry("dispatch_status", "cancel_requested");
        assertThat(data).containsEntry("rmf_task_id", "qi.dispatch-1");
        assertThat(data.get("execution_final")).isEqualTo(false);
        TaskRecord record = repository.findBySchedulerRequestId(requestId).orElseThrow();
        assertThat(record.getDispatchStatus()).isEqualTo(DispatchStatus.CANCEL_REQUESTED);
        assertThat(record.getCancelRequest()).containsEntry("reason", "operator request");
    }

    @Test
    void stopTask_byRobotCode_usesLatestActiveTask() {
        dispatchAndInjectKernelTask("task-old", "R001", "qi.dispatch-old", "completed");
        dispatchAndInjectKernelTask("task-new", "R001", "qi.dispatch-new", "underway");

        Map<String, Object> data = facade.stopTask(null, null, "R001", null);

        assertThat(data).containsEntry("task_id", "task-new");
        assertThat(data).containsEntry("rmf_task_id", "qi.dispatch-new");
    }

    @Test
    void stopTask_cancelRequestedTask_staysAddressable() {
        dispatchAndInjectKernelTask("task-1", "R001", "qi.dispatch-1", "underway");
        facade.stopTask("task-1", null, null, "first");

        Map<String, Object> second = facade.stopTask("task-1", null, null, "second");

        assertThat(second).containsEntry("task_status", "cancel_requested");
    }

    // ==================== 内核事件回填与状态查询（旧 3046/3096/3254） ====================

    @Test
    void taskStateEvent_refillsPublishedRecord() {
        Map<String, Object> data = facade.dispatchTask("tf", "task-1", "R001", null, null, null, null,
                "quality_inspection", null, null, "standby", null, Map.of());
        String requestId = (String) data.get("scheduler_request_id");

        injectKernelEvent(requestId, "qi.dispatch-42", "queued");

        TaskRecord record = repository.findBySchedulerRequestId(requestId).orElseThrow();
        assertThat(record.getRmfTaskId()).isEqualTo("qi.dispatch-42");
        assertThat(record.getDispatchStatus()).isEqualTo(DispatchStatus.RMF_RESPONSE_RECEIVED);
    }

    @Test
    void queryTaskStatus_returnsLatestTaskStateSnapshot() {
        seedRobot("R001", "RG001", "idle");
        Map<String, Object> dispatchData = facade.dispatchTask("123456", "task-001", null, null, null, null,
                null, "quality_inspection", null, null, "standby", null, Map.of());
        String requestId = (String) dispatchData.get("scheduler_request_id");
        injectKernelEvent(requestId, "quality_inspection.dispatch-42", "queued");
        taskStateMirror.upsert(new TaskStateEvent("quality_inspection.dispatch-42", "completed",
                "quality_inspection", Map.of(), Map.of(), null, null, requestId, System.currentTimeMillis()));

        Map<String, Object> data = facade.queryTaskStatus("task-001");

        assertThat(data).containsEntry("task_id", "task-001");
        assertThat(data).containsEntry("business_task_id", "task-001");
        assertThat(data).containsEntry("scheduler_request_id", requestId);
        assertThat(data).containsEntry("rmf_task_id", "quality_inspection.dispatch-42");
        assertThat(data).containsEntry("task_status", "completed");
        assertThat(data.get("execution_final")).isEqualTo(true);
        assertThat((Map<String, Object>) ((Map<String, Object>) data.get("raw")).get("task_state"))
                .containsEntry("status", "completed");
    }

    @Test
    void queryTaskStatus_missingRecord_returns404WithDetail() {
        assertThatThrownBy(() -> facade.queryTaskStatus("missing"))
                .isInstanceOf(SchedulerHttpException.class)
                .satisfies(e -> {
                    SchedulerHttpException http = (SchedulerHttpException) e;
                    assertThat(http.getHttpStatus()).isEqualTo(404);
                    assertThat(http.getMessage()).isEqualTo("task status record not found: missing");
                });
    }

    @Test
    void queryTaskStatus_byRequestId_keepsDuplicateBusinessTasksIsolated() {
        seedRobot("R001", "RG001", "idle");
        String first = (String) facade.dispatchTask("tf", "task-duplicate", null, null, null, null, null,
                "quality_inspection", null, null, "standby", null, Map.of()).get("scheduler_request_id");
        String second = (String) facade.dispatchTask("tf", "task-duplicate", null, null, null, null, null,
                "quality_inspection", null, null, "standby", null, Map.of()).get("scheduler_request_id");
        injectKernelEvent(first, "qi.dispatch-first", "queued");

        Map<String, Object> firstStatus = facade.queryTaskStatusByRequestId(first);
        Map<String, Object> secondStatus = facade.queryTaskStatusByRequestId(second);

        assertThat(firstStatus).containsEntry("rmf_task_id", "qi.dispatch-first");
        assertThat(firstStatus).containsEntry("rmf_response_received", true);
        assertThat(secondStatus.get("rmf_task_id")).isNull();
        assertThat(secondStatus).containsEntry("rmf_response_received", false);
    }

    @Test
    void queryTaskStatus_publishedWithoutKernelTask_showsQueued() {
        seedRobot("R001", "RG001", "idle");
        facade.dispatchTask("tf", "task-1", null, null, null, null, null,
                "quality_inspection", null, null, "standby", null, Map.of());

        Map<String, Object> data = facade.queryTaskStatus("task-1");

        assertThat(data).containsEntry("task_status", "queued");
        assertThat(data).containsEntry("dispatch_status", "published");
        assertThat(data.get("execution_final")).isEqualTo(false);
    }

    @Test
    void queryTaskStatus_byEmptyRequestId_returns400() {
        assertThatThrownBy(() -> facade.queryTaskStatusByRequestId(" "))
                .isInstanceOf(SchedulerHttpException.class)
                .satisfies(e -> assertThat(((SchedulerHttpException) e).getHttpStatus()).isEqualTo(400));
    }

    // ==================== list tasks（旧 3144/3190/3217） ====================

    @Test
    void listTasks_returnsUnfinishedTaskStateSnapshots() {
        taskStateMirror.upsert(new TaskStateEvent("go_to_place.rmf-running-001", "running",
                "go_to_place", Map.of(), Map.of(), null, "R001", null, System.currentTimeMillis()));
        taskStateMirror.upsert(new TaskStateEvent("quality_inspection.rmf-completed-001", "completed",
                "quality_inspection", Map.of(), Map.of(), null, "R002", null, System.currentTimeMillis()));

        Map<String, Object> data = facade.listTasks(null, false, null, 100, true);

        assertThat(data).containsEntry("count", 1);
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) data.get("tasks");
        assertThat(tasks).hasSize(1);
        Map<String, Object> task = tasks.get(0);
        assertThat(task).containsEntry("rmf_task_id", "go_to_place.rmf-running-001");
        assertThat(task).containsEntry("robot_code", "R001");
        assertThat(task).containsEntry("task_status", "running");
        assertThat(task.get("execution_final")).isEqualTo(false);
        assertThat(task).containsEntry("category", "go_to_place");
        assertThat((Map<String, Object>) task.get("raw")).containsKey("task_state");
    }

    @Test
    void listTasks_marksStaleRunningTaskState() {
        taskStateMirror.upsert(new TaskStateEvent("go_to_place.rmf-stale-001", "running",
                "go_to_place", Map.of(), Map.of(), null, "R001", null,
                System.currentTimeMillis() - 10_001));

        Map<String, Object> data = facade.listTasks(null, false, null, 100, true);

        List<Map<String, Object>> tasks = (List<Map<String, Object>>) data.get("tasks");
        assertThat(tasks).hasSize(1);
        Map<String, Object> task = tasks.get(0);
        assertThat(task).containsEntry("task_status", "stale");
        assertThat(task.get("execution_final")).isEqualTo(false);
        assertThat((String) task.get("message")).contains("timeout");
    }

    @Test
    void listTasks_filtersByRobotCode() {
        taskStateMirror.upsert(new TaskStateEvent("go_to_place.rmf-robot-001", "running",
                "go_to_place", Map.of(), Map.of(), null, "R001", null, System.currentTimeMillis()));
        taskStateMirror.upsert(new TaskStateEvent("go_to_place.rmf-robot-002", "queued",
                "go_to_place", Map.of(), Map.of(), null, "R002", null, System.currentTimeMillis()));

        Map<String, Object> data = facade.listTasks("R002", false, null, 100, true);

        assertThat(data).containsEntry("count", 1);
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) data.get("tasks");
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0)).containsEntry("rmf_task_id", "go_to_place.rmf-robot-002");
        assertThat(tasks.get(0)).containsEntry("robot_code", "R002");
    }

    @Test
    void listTasks_executionFinalTrue_includesTerminal() {
        taskStateMirror.upsert(new TaskStateEvent("qi.rmf-completed-001", "completed",
                "quality_inspection", Map.of(), Map.of(), null, "R002", null, System.currentTimeMillis()));

        Map<String, Object> data = facade.listTasks(null, true, null, 100, false);

        assertThat(data).containsEntry("count", 1);
        assertThat(data).containsEntry("execution_final_included", true);
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) data.get("tasks");
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0)).doesNotContainKey("raw");
    }

    @Test
    void listTasks_invalidLimit_returns400() {
        assertThatThrownBy(() -> facade.listTasks(null, false, null, 0, true))
                .isInstanceOf(SchedulerHttpException.class)
                .satisfies(e -> assertThat(((SchedulerHttpException) e).getHttpStatus()).isEqualTo(400));
    }

    // ==================== 充电桩（旧 2118/2163/2064） ====================

    @Test
    void assignCharger_defaultsWhenCandidatesEmpty() {
        chargerPool.register(charger("charger_A", "charger_A", "charger"));

        Map<String, Object> data = facade.assignCharger("R001", null, null);

        assertThat(data).containsEntry("charger_waypoint", "charger_A");
    }

    @Test
    void assignCharger_noAvailable_returns409() {
        assertThatThrownBy(() -> facade.assignCharger("R001", null, null))
                .isInstanceOf(SchedulerHttpException.class)
                .satisfies(e -> {
                    SchedulerHttpException http = (SchedulerHttpException) e;
                    assertThat(http.getHttpStatus()).isEqualTo(409);
                    assertThat(http.getMessage()).isEqualTo("no charger is available");
                });
    }

    @Test
    void assignCharger_candidateUnavailable_returns409WithCandidateDetail() {
        chargerPool.register(charger("charger_A", "charger_A", "charger"));
        // 先占满候选桩，再请求分配应 409
        facade.assignCharger("R999", null, null);

        assertThatThrownBy(() -> facade.assignCharger("R001", List.of("charger_A"), null))
                .isInstanceOf(SchedulerHttpException.class)
                .satisfies(e -> {
                    SchedulerHttpException http = (SchedulerHttpException) e;
                    assertThat(http.getHttpStatus()).isEqualTo(409);
                    assertThat(http.getMessage()).isEqualTo("no candidate charger is available");
                });
    }

    @Test
    void assignCharger_requiresRobotCode_returns400() {
        assertThatThrownBy(() -> facade.assignCharger(" ", null, null))
                .isInstanceOf(SchedulerHttpException.class)
                .satisfies(e -> assertThat(((SchedulerHttpException) e).getHttpStatus()).isEqualTo(400));
    }

    @Test
    void assignSwapStation_withResourceTypeFilter() {
        chargerPool.register(charger("charger_A", "charger_A", "charger"));
        chargerPool.register(charger("swap_1", "swap_1", "swap_station"));

        Map<String, Object> data = facade.assignCharger("R001", null, "swap_station");

        assertThat(data).containsEntry("charger_waypoint", "swap_1");
    }

    @Test
    void releaseCharger_thenReassignable() {
        chargerPool.register(charger("charger_A", "charger_A", "charger"));
        facade.assignCharger("R001", null, null);

        Map<String, Object> releaseData = facade.releaseCharger("R001", null, null);

        assertThat(releaseData).isNull();
        Map<String, Object> second = facade.assignCharger("R002", null, null);
        assertThat(second).containsEntry("charger_waypoint", "charger_A");
    }

    @Test
    void releaseCharger_requiresTarget_returns400() {
        assertThatThrownBy(() -> facade.releaseCharger(null, " ", null))
                .isInstanceOf(SchedulerHttpException.class)
                .satisfies(e -> assertThat(((SchedulerHttpException) e).getHttpStatus()).isEqualTo(400));
    }

    @Test
    void robotResources_returnsEmptyMap_andReleaseResourcesReturnsNull() {
        assertThat(facade.robotResources("R001")).isEqualTo(Map.of());
        assertThat(facade.releaseResources("R001")).isNull();
    }

    @Test
    void releaseResources_requiresRobotCode_returns400() {
        assertThatThrownBy(() -> facade.releaseResources(""))
                .isInstanceOf(SchedulerHttpException.class)
                .satisfies(e -> assertThat(((SchedulerHttpException) e).getHttpStatus()).isEqualTo(400));
    }

    // ==================== 场景资源固定响应（旧 2305） ====================

    @Test
    void reloadSceneResources_returnsFixedSkippedResponse() {
        Map<String, Object> data = facade.reloadSceneResources(List.of("chargers"), true);

        assertThat(data).containsEntry("status", "skipped");
        assertThat(data).containsEntry("resource_update_enabled", false);
        assertThat((String) data.get("message")).contains("temporarily disabled");
        assertThat(data.get("runtime_reload")).isNotNull();
    }

    @Test
    void sceneResourceStatus_returnsNotLoadedStructure() {
        // 无图专属 facade（setUp 默认加载了类型校验测试地图）
        MapManager emptyManager = new MapManager(new MapMetadataParser(), mapArchiveRepository);
        DefaultSchedulerFacade bareFacade = new DefaultSchedulerFacade(repository, chargerPool, taskStateMirror,
                fleetStateMirror, assignmentRegistry, new SchedulerEventBus(publisher), emptyManager,
                executionEngine);
        Map<String, Object> data = bareFacade.sceneResourceStatus();

        assertThat(data).containsEntry("configured", false);
        assertThat(data).containsEntry("status", "not_loaded");
        assertThat(data).containsKey("loaded_at");
        assertThat(data).containsKey("rmf_levels");
    }

    // ==================== 自动分配准入 fail-fast（车队组无机器人直接拒绝） ====================

    @Test
    void dispatchTask_autoAssign_fleetGroupWithoutRobots_returns409Fast() {
        seedRobot("R001", "RG001", "idle");

        assertThatThrownBy(() -> facade.dispatchTask("tf", "task-1", null, null, "RG_EMPTY", null, null,
                "quality_inspection", null, null, "standby", null, Map.of()))
                .isInstanceOf(SchedulerHttpException.class)
                .satisfies(e -> {
                    SchedulerHttpException http = (SchedulerHttpException) e;
                    assertThat(http.getHttpStatus()).isEqualTo(409);
                    assertThat(http.getMessage()).contains("RG_EMPTY").contains("RMS").contains("group_no");
                });
        // 拒绝的任务不应落库排队
        assertThat(repository.findByBusinessTaskId("task-1")).isEmpty();
    }

    @Test
    void dispatchTask_autoAssign_noRobotsAtAll_returns409Fast() {
        assertThatThrownBy(() -> facade.dispatchTask("tf", "task-1", null, null, null, null, null,
                "quality_inspection", null, null, "standby", null, Map.of()))
                .isInstanceOf(SchedulerHttpException.class)
                .satisfies(e -> {
                    SchedulerHttpException http = (SchedulerHttpException) e;
                    assertThat(http.getHttpStatus()).isEqualTo(409);
                    assertThat(http.getMessage()).contains("no robot is visible").contains("RMS");
                });
    }

    @Test
    void dispatchTask_autoAssign_robotsPresentButNotDispatchable_staysQueued() {
        // 有机器人但全部忙碌/离线：瞬时不可用，不拦截，走排队等待语义
        seedRobot("R001", "RG001", "charging");

        Map<String, Object> data = facade.dispatchTask("tf", "task-1", null, null, "RG001", null, null,
                "quality_inspection", null, null, "standby", null, Map.of());

        assertThat(data).containsEntry("dispatch_status", "published");
        assertThat(data).containsEntry("task_status", "queued");
    }

    @Test
    void gotoPlace_autoAssign_usesRobotGroupFilterForAdmission() {
        seedRobot("R001", "RG001", "idle");

        assertThatThrownBy(() -> facade.gotoPlace("1001", "2001", null, "inspection_start_1",
                "RG_MISSING", null, null, null, Map.of()))
                .isInstanceOf(SchedulerHttpException.class)
                .satisfies(e -> assertThat(((SchedulerHttpException) e).getHttpStatus()).isEqualTo(409));
    }

    @Test
    void dispatchTask_chargeWithSlamGoalName_autoAllocatesCharger() {
        // 生产协议兼容：任务流充电目标为机器人侧 SLAM 名（Goal_uX0Ef），调度图不可解析 → 自动分配充电位
        chargerPool.register(charger("C1", "C1", "charger"));

        facade.dispatchTask("tf", "task-charge", "R001", null, null, null, null,
                "charge", null, "Goal_uX0Ef", null, null, Map.of());

        org.mockito.ArgumentCaptor<com.zioneer.robotscheduler.core.task.TaskGoal> captor =
                org.mockito.ArgumentCaptor.forClass(com.zioneer.robotscheduler.core.task.TaskGoal.class);
        org.mockito.Mockito.verify(executionEngine)
                .submit(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("R001"), captor.capture());
        assertThat(captor.getValue().goalWaypointId()).isEqualTo("C1");
    }

    @Test
    void dispatchTask_chargeTargetInNavStartPoint_honored() {
        // 任务流充电契约：目标充电位在 nav_start_point（历史命名），nav_end_point 为空
        seedRobot("R001", "RG001", "idle");
        chargerPool.register(charger("C1", "C1", "charger"));

        facade.dispatchTask("tf", "task-charge2", "R001", null, null, null, null,
                "charge", "C1", "", null, null, Map.of());

        org.mockito.ArgumentCaptor<com.zioneer.robotscheduler.core.task.TaskGoal> captor =
                org.mockito.ArgumentCaptor.forClass(com.zioneer.robotscheduler.core.task.TaskGoal.class);
        org.mockito.Mockito.verify(executionEngine)
                .submit(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("R001"), captor.capture());
        assertThat(captor.getValue().goalWaypointId()).isEqualTo("C1");
    }

    @Test
    void dispatchTask_chargeTopLevelTemplateCode_injectedIntoGoalParams() {
        // 真实任务流样例：task_template_code 为顶层字段（auto-goto-charge101），parameters 为空——
        // 机器人侧 start-activity 按模板 code 查模板，顶层 code 必须注入 params 透传到 description
        seedRobot("R001", "RG001", "idle");
        chargerPool.register(charger("C1", "C1", "charger"));

        facade.dispatchTask("241870150773227520", "tf-241870150773227520-241870151515619328", "R001",
                null, "RG_20260407_00001", "241850112506839040", "auto-goto-charge101",
                "charge", null, "C1", null, null, Map.of());

        org.mockito.ArgumentCaptor<com.zioneer.robotscheduler.core.task.TaskGoal> captor =
                org.mockito.ArgumentCaptor.forClass(com.zioneer.robotscheduler.core.task.TaskGoal.class);
        org.mockito.Mockito.verify(executionEngine)
                .submit(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("R001"), captor.capture());
        assertThat(captor.getValue().goalWaypointId()).isEqualTo("C1");
        assertThat(captor.getValue().parameters().get("task_template_code")).isEqualTo("auto-goto-charge101");
    }

    @Test
    void dispatchTask_qualityInspection_nonWorkstationTarget_rejectedFast() {
        seedRobot("R001", "RG001", "idle");
        assertThatThrownBy(() -> facade.dispatchTask("tf", "task-1", "R001", null, null, null, null,
                "quality_inspection", null, "P1", "standby", null, Map.of()))
                .isInstanceOf(SchedulerHttpException.class)
                .satisfies(e -> {
                    SchedulerHttpException http = (SchedulerHttpException) e;
                    assertThat(http.getHttpStatus()).isEqualTo(400);
                    assertThat(http.getMessage()).contains("workstation").contains("P1");
                });
        assertThat(repository.findByBusinessTaskId("task-1")).isEmpty();
    }

    @Test
    void dispatchTask_charge_nonChargeTarget_rejectedFast() {
        seedRobot("R001", "RG001", "idle");
        assertThatThrownBy(() -> facade.dispatchTask("tf", "task-2", "R001", null, null, null, null,
                "charge", null, "W1", null, null, Map.of()))
                .isInstanceOf(SchedulerHttpException.class)
                .satisfies(e -> {
                    SchedulerHttpException http = (SchedulerHttpException) e;
                    assertThat(http.getHttpStatus()).isEqualTo(400);
                    assertThat(http.getMessage()).contains("charge").contains("W1");
                });
    }

    @Test
    void dispatchTask_correctTargetTypes_accepted() {
        seedRobot("R001", "RG001", "idle");
        // 质检→作业点、充电→充电位：类型匹配正常受理
        Map<String, Object> qi = facade.dispatchTask("tf", "task-3", "R001", null, null, null, null,
                "quality_inspection", null, "W1", "standby", null, Map.of());
        assertThat(qi).containsEntry("dispatch_status", "published");
        Map<String, Object> charge = facade.dispatchTask("tf", "task-4", "R001", null, null, null, null,
                "charge", null, "C1", null, null, Map.of());
        assertThat(charge).containsEntry("dispatch_status", "published");
    }

    // ==================== helpers ====================

    /** 注入一台在线机器人到车队镜像（自动分配准入检查输入）。 */
    private RobotSnapshot seedRobot(String robotCode, String fleetName, String mode) {
        RobotSnapshot snapshot = new RobotSnapshot();
        snapshot.setRobotCode(robotCode);
        snapshot.setFleetName(fleetName);
        snapshot.setMode(mode);
        snapshot.setOnline(true);
        snapshot.setDispatchable(true);
        snapshot.setBattery(80.0);
        snapshot.setUpdatedAtMillis(System.currentTimeMillis());
        fleetStateMirror.upsert(snapshot);
        return snapshot;
    }

    /** dispatch 并注入内核受理事件（模拟旧栈 api_response + task_state_update 链路）。 */
    private String dispatchAndInjectKernelTask(String taskId, String robotCode, String rmfTaskId, String status) {
        Map<String, Object> data = facade.dispatchTask("tf", taskId, robotCode, null, null, null, null,
                "quality_inspection", null, null, "standby", null, Map.of());
        String requestId = (String) data.get("scheduler_request_id");
        injectKernelEvent(requestId, rmfTaskId, status);
        return requestId;
    }

    private void injectKernelEvent(String requestId, String rmfTaskId, String status) {
        TaskStateEvent event = new TaskStateEvent(rmfTaskId, status, "quality_inspection",
                Map.of(), Map.of(), null, null, requestId, System.currentTimeMillis());
        taskStateMirror.upsert(event);
        facade.onTaskStateEvent(event);
    }

    private ChargerInfo charger(String chargerId, String waypoint, String resourceType) {
        ChargerInfo info = new ChargerInfo();
        info.setChargerId(chargerId);
        info.setName(chargerId);
        info.setRmfWaypoint(waypoint);
        info.setResourceType(resourceType);
        info.setStatus(0);
        info.setEnabled(true);
        return info;
    }
}
