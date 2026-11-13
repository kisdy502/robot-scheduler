package com.zioneer.robotscheduler.core.dispatch;

import com.zioneer.robotscheduler.core.fleet.RobotAvailabilityService;
import com.zioneer.robotscheduler.core.traffic.InMemoryTrafficRegistry;
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

    // 网络重试复用同一业务任务 ID 时返回原请求，不能再次提交机器人移动。
    @Test
    void repeatedMoveRequest_reusesOriginalRequestAndSubmitsOnce() {
        var first = facade.gotoPlace("1001", "2001", "R001", "W1", null, null, null, null, Map.of("idempotent", true));
        var retry = facade.gotoPlace("1001", "2001", "R001", "W1", null, null, null, null, Map.of("idempotent", true));
        assertThat(retry.get("scheduler_request_id")).isEqualTo(first.get("scheduler_request_id"));
        org.mockito.Mockito.verify(executionEngine, org.mockito.Mockito.times(1))
                .submit(any(), org.mockito.ArgumentMatchers.eq("R001"), any());
    }

    // 已有任务 ID 不能被换车或改目标复用，否则返回 409 且保留原记录。
    @Test
    void reusedTaskIdWithDifferentRequest_isConflict() {
        facade.gotoPlace("1001", "2001", "R001", "W1", null, null, null, null, Map.of("idempotent", true));
        assertThatThrownBy(() -> facade.gotoPlace("1001", "2001", "R002", "W1",
                null, null, null, null, Map.of("idempotent", true)))
                .isInstanceOf(SchedulerHttpException.class)
                .satisfies(e -> assertThat(((SchedulerHttpException) e).getHttpStatus()).isEqualTo(409));
        assertThat(repository.findByBusinessTaskId("2001").orElseThrow().getRobotCode()).isEqualTo("R001");
    }

    // 终态后的重发只能查询原结果，不能把已完成的移动重新执行一遍。
    @Test
    void completedRequestReplay_returnsCompletedWithoutResubmission() {
        facade.gotoPlace("1001", "2001", "R001", "W1", null, null, null, null, Map.of("idempotent", true));
        var record = repository.findByBusinessTaskId("2001").orElseThrow();
        record.setRmfTaskId("move.done");
        taskStateMirror.onTaskStateEvent(new TaskStateEvent("move.done", "completed", "go_to_place",
                Map.of(), null, "fleet", "R001", 1L));
        var retry = facade.gotoPlace("1001", "2001", "R001", "W1", null, null, null, null, Map.of("idempotent", true));
        assertThat(retry).containsEntry("task_status", "completed").containsEntry("execution_final", true);
        org.mockito.Mockito.verify(executionEngine, org.mockito.Mockito.times(1)).submit(any(), any(), any());
    }

    // 并发重试同一请求时，单实例原子准入只生成一个调度请求并下发一次。
    @Test
    void thirtyConcurrentRetries_admitOneTask() throws Exception {
        try (var callers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.List<java.util.concurrent.Callable<String>> requests = java.util.stream.IntStream.range(0, 30)
                    .mapToObj(i -> (java.util.concurrent.Callable<String>) () ->
                            (String) facade.gotoPlace("1001", "2001", "R001", "W1", null,
                                    null, null, null, Map.of("idempotent", true)).get("scheduler_request_id")).toList();
            var replies = callers.invokeAll(requests, 10, java.util.concurrent.TimeUnit.SECONDS);
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (var reply : replies) ids.add(reply.get());
            assertThat(ids).hasSize(1);
        }
        org.mockito.Mockito.verify(executionEngine, org.mockito.Mockito.times(1)).submit(any(), any(), any());
    }

    // 内核受理异常必须收口为失败记录，相同请求重试不能返回永久等待或再次执行。
    @Test
    void admissionFailure_isRecordedAndReplayDoesNotResubmit() {
        org.mockito.Mockito.doThrow(new IllegalStateException("executor closed"))
                .when(executionEngine).submit(any(), any(), any());
        assertThatThrownBy(() -> facade.gotoPlace("1001", "2001", "R001", "W1", null,
                null, null, null, Map.of("idempotent", true))).isInstanceOf(IllegalStateException.class);
        var replay = facade.gotoPlace("1001", "2001", "R001", "W1", null, null, null, null, Map.of("idempotent", true));
        assertThat(replay).containsEntry("task_status", "failed").containsEntry("execution_final", true);
        org.mockito.Mockito.verify(executionEngine, org.mockito.Mockito.times(1)).submit(any(), any(), any());
    }

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

    // 指定机器人已被其他任务流占用时返回 409，且不能先保存一条未实际受理的任务记录。
    @Test
    void specifiedDispatchRejectsForeignClaimBeforeSavingRecord() {
        assignmentRegistry.assign("owner", "R001");
        assertThatThrownBy(() -> facade.gotoPlace("other", "blocked", "R001", "inspection_start_1",
                "default", null, null, null, Map.of()))
                .isInstanceOf(SchedulerHttpException.class)
                .satisfies(e -> assertThat(((SchedulerHttpException) e).getHttpStatus()).isEqualTo(409));
        assertThat(repository.findByBusinessTaskId("blocked")).isEmpty();
    }

    // 外部作业通知返回毫秒时间，结束后查询应清除作业状态但保留任务流使用权。
    @Test
    void operationCallbacksKeepOwnershipAfterFinishAndExposeMillis() {
        assignmentRegistry.assign("owner", "R001");
        var started = facade.startExternalOperation("owner", "R001", "op");
        assertThat(started.get("started_at")).isInstanceOf(Long.class);
        assertThat(facade.robotUse("R001").get("external_operation")).isNotNull();
        var finished = facade.finishExternalOperation("owner", "R001", "op");
        assertThat(finished.get("finished_at")).isInstanceOf(Long.class);
        assertThat(facade.robotUse("R001")).containsEntry("taskflow_id", "owner")
                .containsEntry("external_operation", null);
    }

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
                executionEngine, new RobotAvailabilityService(
                        assignmentRegistry, new InMemoryTrafficRegistry()));
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

    // assign-task 指定 robot_code：分配成功，data 回带 robot_code 且任务流占用登记生效
    @Test
    void assignTask_withRobotCode_returnsRobotCodeData() {
        Map<String, Object> data = facade.assignTask("1001", null, "R009", null, null, null, null);

        assertThat(data).containsEntry("robot_code", "R009");
        assertThat(assignmentRegistry.isAssigned("1001")).isTrue();
    }

    // assign-task 起始导航点缺省：仅传 robot_code 也能正常分配并返回 robot_code
    @Test
    void assignTask_allowsMissingNavPointStart() {
        Map<String, Object> data = facade.assignTask("1001", null, "R001", null, null, null, null);

        assertThat(data).containsEntry("robot_code", "R001");
    }

    // release-robot 缺省 robot_code：释放该任务流下全部机器人指派，data 为 null 且占用登记清空
    @Test
    void releaseRobot_withoutRobotCode_releasesTaskflowAssignments() {
        facade.assignTask("1001", null, "R001", null, null, "P001", null);
        facade.assignTask("1001", null, "R001", null, null, "P001", null);

        Map<String, Object> data = facade.releaseRobot("1001", null);

        assertThat(data).isNull();
        assertThat(assignmentRegistry.isAssigned("1001")).isFalse();
    }

    // release-robot 缺少 taskflow_id：返回 400
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

    // goto-place 导航目标点为空白：返回 400
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

    // dispatch_task 传已废弃的 replace_battery/replaceBattery 类别名：返回 400，detail 提示改用 swap_battery
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

    // dispatch_task 传非法 end_action（invalid）：返回 400
    @Test
    void dispatchTask_rejectsInvalidEndAction() {
        assertThatThrownBy(() -> facade.dispatchTask("tf", "task-1", null, null, null, null, null,
                "quality_inspection", null, null, "invalid", null, Map.of()))
                .isInstanceOf(SchedulerHttpException.class)
                .satisfies(e -> assertThat(((SchedulerHttpException) e).getHttpStatus()).isEqualTo(400));
    }

    // dispatch_task 的 earliest_start_time 非数字：返回 400
    @Test
    void dispatchTask_rejectsInvalidEarliestStartTime() {
        assertThatThrownBy(() -> facade.dispatchTask("tf", "task-1", null, null, null, null, null,
                "quality_inspection", null, null, "standby", "not-a-number", Map.of()))
                .isInstanceOf(SchedulerHttpException.class)
                .satisfies(e -> assertThat(((SchedulerHttpException) e).getHttpStatus()).isEqualTo(400));
    }

    // dispatch_task 同时传 fleet_name 与 robot_group 且不一致：返回 400，detail 提示二者必须匹配
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

    // dispatch_task return_home 未指定 robot_code：返回 400，detail 固定为 robot_code is required for return_home
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

    // dispatch_task return_home 缺 home_waypoint 且缺 nav_end_point：返回 400，detail 提示回家点参数必填
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

    // dispatch_task returnHome 别名的 home_waypoint 与 nav_end_point 指向不同点：返回 409 冲突
    @Test
    void dispatchTask_returnHome_rejectsHomeWaypointConflict() {
        assertThatThrownBy(() -> facade.dispatchTask("tf", "task-home", "R001", null, null, null, null,
                "returnHome", null, "home_B", "standby", null, Map.of("home_waypoint", "home_A")))
                .isInstanceOf(SchedulerHttpException.class)
                .satisfies(e -> assertThat(((SchedulerHttpException) e).getHttpStatus()).isEqualTo(409));
    }

    // dispatch_task return_home 仅传 nav_end_point 作为回家点：受理成功，发布 published 且类别归档为 return_home
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

    // stop-task 任务记录不存在：返回 404，detail 固定为 task status record not found
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

    // stop-task 任务尚未收到 rmf_task_id（内核未受理）：返回 409，提示不可取消
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

    // stop-task 按任务 ID 取消进行中任务：task/dispatch_status 均置 cancel_requested，落库取消原因并回带 rmf_task_id
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

    // stop-task 缺任务 ID、按 robot_code 取消：命中该机器人最新一条未终态任务（跳过已完成任务）
    @Test
    void stopTask_byRobotCode_usesLatestActiveTask() {
        dispatchAndInjectKernelTask("task-old", "R001", "qi.dispatch-old", "completed");
        dispatchAndInjectKernelTask("task-new", "R001", "qi.dispatch-new", "underway");

        Map<String, Object> data = facade.stopTask(null, null, "R001", null);

        assertThat(data).containsEntry("task_id", "task-new");
        assertThat(data).containsEntry("rmf_task_id", "qi.dispatch-new");
    }

    // stop-task 重复取消已 cancel_requested 的任务：幂等，任务仍可寻址且状态保持 cancel_requested
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

    // 查询任务状态：返回最新快照，rmf_task_id/task_status/execution_final/raw.task_state 均正确回显
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

    // 查询任务状态但记录不存在：返回 404，detail 携带任务 ID
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

    // 按 scheduler_request_id 查询任务状态：同一 business_task_id 重复派发的两条请求相互隔离，仅已受理记录回填 rmf_task_id
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

    // 查询已发布但内核尚未受理的任务：task_status=queued、dispatch_status=published、execution_final=false
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

    // 按空白 scheduler_request_id 查询任务状态：返回 400
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

    // list-tasks 运行中任务心跳超时（>10s）：标记为 stale，message 提示 timeout
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

    // list-tasks 按 robot_code 过滤：仅返回该机器人的任务
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

    // list-tasks execution_final=true：包含终态任务、回显 execution_final_included=true 且不返回 raw 字段
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

    // list-tasks limit 非法（0）：返回 400
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

    // assign-charger 无任何可用充电桩：返回 409，detail 固定为 no charger is available
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

    // assign-charger 指定候选桩但候选全部被占：返回 409，detail 固定为 no candidate charger is available
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

    // assign-charger 缺少 robot_code：返回 400
    @Test
    void assignCharger_requiresRobotCode_returns400() {
        assertThatThrownBy(() -> facade.assignCharger(" ", null, null))
                .isInstanceOf(SchedulerHttpException.class)
                .satisfies(e -> assertThat(((SchedulerHttpException) e).getHttpStatus()).isEqualTo(400));
    }

    // assign-charger 按 resource_type=swap_station 过滤：跳过普通充电桩，分配到换电站点位
    @Test
    void assignSwapStation_withResourceTypeFilter() {
        chargerPool.register(charger("charger_A", "charger_A", "charger"));
        chargerPool.register(charger("swap_1", "swap_1", "swap_station"));

        Map<String, Object> data = facade.assignCharger("R001", null, "swap_station");

        assertThat(data).containsEntry("charger_waypoint", "swap_1");
    }

    // release-charger 释放充电桩：data 为 null，且释放后该桩可被其他机器人重新分配
    @Test
    void releaseCharger_thenReassignable() {
        chargerPool.register(charger("charger_A", "charger_A", "charger"));
        facade.assignCharger("R001", null, null);

        Map<String, Object> releaseData = facade.releaseCharger("R001", null, null);

        assertThat(releaseData).isNull();
        Map<String, Object> second = facade.assignCharger("R002", null, null);
        assertThat(second).containsEntry("charger_waypoint", "charger_A");
    }

    // release-charger 缺少释放目标（robot_code/桩标识空白）：返回 400
    @Test
    void releaseCharger_requiresTarget_returns400() {
        assertThatThrownBy(() -> facade.releaseCharger(null, " ", null))
                .isInstanceOf(SchedulerHttpException.class)
                .satisfies(e -> assertThat(((SchedulerHttpException) e).getHttpStatus()).isEqualTo(400));
    }

    // robot-resources/release-resources 机器人无占用资源：查询返回空 Map，释放返回 null
    @Test
    void robotResources_returnsEmptyMap_andReleaseResourcesReturnsNull() {
        assertThat(facade.robotResources("R001")).isEqualTo(Map.of());
        assertThat(facade.releaseResources("R001")).isNull();
    }

    // release-resources 缺少 robot_code：返回 400
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

    // scene-resource-status 未加载地图（空 MapManager）：返回 configured=false、status=not_loaded 结构，含 loaded_at/rmf_levels
    @Test
    void sceneResourceStatus_returnsNotLoadedStructure() {
        // 无图专属 facade（setUp 默认加载了类型校验测试地图）
        MapManager emptyManager = new MapManager(new MapMetadataParser(), mapArchiveRepository);
        DefaultSchedulerFacade bareFacade = new DefaultSchedulerFacade(repository, chargerPool, taskStateMirror,
                fleetStateMirror, assignmentRegistry, new SchedulerEventBus(publisher), emptyManager,
                executionEngine, new RobotAvailabilityService(
                        assignmentRegistry, new InMemoryTrafficRegistry()));
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

    // dispatch_task 自动分配且 RMS 无任何可见机器人：fail-fast 返回 409，detail 含 no robot is visible
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

    // dispatch_task 自动分配时机器人可见但瞬时不可调度（充电中）：不拦截，任务照常发布进入排队
    @Test
    void dispatchTask_autoAssign_robotsPresentButNotDispatchable_staysQueued() {
        // 有机器人但全部忙碌/离线：瞬时不可用，不拦截，走排队等待语义
        seedRobot("R001", "RG001", "charging");

        Map<String, Object> data = facade.dispatchTask("tf", "task-1", null, null, "RG001", null, null,
                "quality_inspection", null, null, "standby", null, Map.of());

        assertThat(data).containsEntry("dispatch_status", "published");
        assertThat(data).containsEntry("task_status", "queued");
    }

    // goto-place 自动分配准入按 robot_group 过滤：目标组无可见机器人时 fail-fast 返回 409
    @Test
    void gotoPlace_autoAssign_usesRobotGroupFilterForAdmission() {
        seedRobot("R001", "RG001", "idle");

        assertThatThrownBy(() -> facade.gotoPlace("1001", "2001", null, "inspection_start_1",
                "RG_MISSING", null, null, null, Map.of()))
                .isInstanceOf(SchedulerHttpException.class)
                .satisfies(e -> assertThat(((SchedulerHttpException) e).getHttpStatus()).isEqualTo(409));
    }

    // dispatch_task charge 目标为机器人侧 SLAM 名（调度图不可解析）：自动分配充电位并以分配结果作为目标点提交内核
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

    // dispatch_task charge 目标充电位写在 nav_start_point（历史命名）而 nav_end_point 为空：仍识别为目标点并提交内核
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

    // dispatch_task 顶层 task_template_code（新版架构）：移动命令不注入模板字段——
    // 真机对 description.task_template_code 强校验（未下发模板拒绝 navigate），模板由上层 start-activity 自带
    @Test
    void dispatchTask_chargeTopLevelTemplateCode_injectedIntoGoalParams() {
        // 真实任务流样例：task_template_code 为顶层字段（auto-goto-charge101），parameters 为空——
        // 断言：目标充电位正常解析，且模板 code 不再进入 goal params（navigate 描述保持纯移动对账字段）
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
        assertThat(captor.getValue().parameters()).doesNotContainKey("task_template_code");
    }

    // dispatch_task quality_inspection 目标非作业点（P1 为 path 类型）：fail-fast 返回 400 且任务不落库
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

    // dispatch_task charge 目标非充电位（W1 为作业点）：fail-fast 返回 400，detail 指出 W1 非 charge 类型
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

    // dispatch_task 目标类型匹配：质检→作业点、充电→充电位，均正常发布 published
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
