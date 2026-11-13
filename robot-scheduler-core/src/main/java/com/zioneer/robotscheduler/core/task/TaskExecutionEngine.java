package com.zioneer.robotscheduler.core.task;

import com.zioneer.robotscheduler.core.fleet.TaskAssignmentRegistry;
import com.zioneer.robotscheduler.core.fleet.RobotAvailabilityService;
import com.zioneer.robotscheduler.common.scheduler.task.TaskCategories;
import com.zioneer.robotscheduler.core.fleet.RobotCommandExecutor;
import com.zioneer.robotscheduler.core.fleet.RobotSnapshot;
import com.zioneer.robotscheduler.core.map.MapManager;
import com.zioneer.robotscheduler.core.map.MapSnapshot;
import com.zioneer.robotscheduler.core.routing.NavigationGraph;
import com.zioneer.robotscheduler.core.routing.GeometryUtil;
import com.zioneer.robotscheduler.core.map.WaypointMetadata;
import com.zioneer.robotscheduler.core.repository.TaskRecord;
import com.zioneer.robotscheduler.core.traffic.TrafficRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 内核任务执行引擎：受理 → 排队（per-robot 串行）→ 执行（导航 + 动作）→ 终态事件。
 *
 * <p>事件契约（SchedulerEventBus → TaskStateMirror）：
 * queued（受理，含 schedulerRequestId 回填记录）→ underway → completed / failed；
 * 取消：执行中任务 = completed + cancellation 标记 + 机器人 stop（fork 语义）；排队任务 = canceled。</p>
 *
 * <p>执行策略（对齐旧栈）：
 * 机器人就绪等待（dispatchable，30s 上限）→ 交通占用（整路径 tryAcquire，失败重试 3 次）→
 * 导航（只发目标点，回执等待超时 = ETA*2 + 60s）→ 动作（回执等待默认 300s，参数 action_timeout_seconds 覆盖）→
 * 终态后释放交通占用。</p>
 */
@Slf4j
@Component
public class TaskExecutionEngine {

    /** 机器人就绪等待上限（秒，测试可注入缩短）。 */
    long robotReadyTimeoutSeconds = 30;

    /** 交通占用重试次数与间隔。 */
    private static final int TRAFFIC_RETRY_TIMES = 3;
    private static final long TRAFFIC_RETRY_INTERVAL_SECONDS = 2;

    /** 交通等待上限（秒）：冲突时持续等待资源释放，超时才判失败。 */
    private static final long TRAFFIC_WAIT_LIMIT_SECONDS = 300;

    /** 交通等待过程事件间隔（毫秒）：8s < 状态 TTL 10s——等待中的任务保持"活跃"判定，
     *  不再被误标 stale（30s 间隔会让交通等待任务在两次事件之间全部显示 stale，压测实测误导排障）。
     *  性能：100 台全堵 = 12.5 事件/s = 12.5 次小行 upsert/s，可忽略。 */
    private static final long TRAFFIC_WAIT_EVENT_INTERVAL_MILLIS = 8_000;

    /** 仅用于识别数值上相同的姿态，不作为实际导航的到位精度或拓扑吸附阈值。 */
    private static final double POSE_EQUALITY_EPSILON = 1e-6;

    /** 导航回执超时基础值（秒，测试可注入缩短；兼作段超时余量）。 */
    long navigationTimeoutBaseSeconds = 60;

    /** 段超时估算的保守速度（m/s，测试可注入放大以缩短超时）。 */
    double segmentConservativeSpeedMps = 0.2;

    /** 实际到点位置误差上限为 20cm。 */
    private static final double ARRIVAL_TOLERANCE_METERS = 0.2;
    /** 途经点通过容差：中间点只需"大致通过"（交管释放由驻留 0.8m 逻辑兜底），真机停位精度 2-5 分米级。 */
    private static final double WAYPOINT_PASS_TOLERANCE_METERS = 0.5;
    /** 最终目标点容差：业务精度的最后一道闸（原 0.2 压线误杀 0.21-0.23m 的正常停位）。 */
    private static final double FINAL_ARRIVAL_TOLERANCE_METERS = 0.3;
    private static final long ARRIVAL_MAX_STATE_AGE_MILLIS = 8000;

    /** 成功回执后等待位置确认，默认最多约 10 秒；期间不释放后续交通占用。 */
    int arrivalConfirmAttempts = 11;

    /** 到达偏差复核采样间隔（毫秒，对齐状态轮询 1Hz；测试可注入缩短）。 */
    long arrivalConfirmIntervalMillis = 1000;

    /** 动作回执默认超时（秒，测试可注入缩短）。 */
    long actionTimeoutDefaultSeconds = 300;

    private final TaskAssignmentRegistry assignmentRegistry;
    private final RobotAvailabilityService availability;
    private final RobotCommandExecutor commandExecutor;
    private final TrafficRegistry trafficRegistry;
    private final MapManager mapManager;
    private final com.zioneer.robotscheduler.core.fleet.FleetStateMirror fleetStateMirror;
    private final com.zioneer.robotscheduler.core.event.SchedulerEventBus eventBus;
    private final com.zioneer.robotscheduler.core.assign.TaskAssigner taskAssigner;
    private final com.zioneer.robotscheduler.core.assign.TaskAssignProperties assignProperties;
    private final com.zioneer.robotscheduler.core.traffic.WaitIntentRegistry waitIntents;
    private final com.zioneer.robotscheduler.core.traffic.ActiveRouteRegistry routeRegistry;
    private final com.zioneer.robotscheduler.core.fleet.RobotEventMonitor eventMonitor;

    /** 每机器人执行信号量（同机任务串行）。 */
    private final Map<String, Semaphore> robotSemaphores = new ConcurrentHashMap<>();

    /** kernelTaskId → 执行状态（取消判定用）。 */
    private final Map<String, KernelTaskState> taskStates = new ConcurrentHashMap<>();

    /** 等待自动分配的任务（无 robot_code 的 dispatch 请求）。 */
    private final Map<String, PendingAssignment> pendingAssignments = new ConcurrentHashMap<>();

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public TaskExecutionEngine(RobotCommandExecutor commandExecutor, TrafficRegistry trafficRegistry,
                               MapManager mapManager,
                               com.zioneer.robotscheduler.core.fleet.FleetStateMirror fleetStateMirror,
                               com.zioneer.robotscheduler.core.event.SchedulerEventBus eventBus,
                               com.zioneer.robotscheduler.core.assign.TaskAssigner taskAssigner,
                               com.zioneer.robotscheduler.core.assign.TaskAssignProperties assignProperties,
                               com.zioneer.robotscheduler.core.traffic.WaitIntentRegistry waitIntents,
                               com.zioneer.robotscheduler.core.traffic.ActiveRouteRegistry routeRegistry,
                               com.zioneer.robotscheduler.core.fleet.RobotEventMonitor eventMonitor,
                               TaskAssignmentRegistry assignmentRegistry,
                               RobotAvailabilityService availability) {
        this.assignmentRegistry = assignmentRegistry;
        this.availability = availability;
        this.commandExecutor = commandExecutor;
        this.trafficRegistry = trafficRegistry;
        this.mapManager = mapManager;
        this.fleetStateMirror = fleetStateMirror;
        this.eventBus = eventBus;
        this.taskAssigner = taskAssigner;
        this.assignProperties = assignProperties;
        this.waitIntents = waitIntents;
        this.routeRegistry = routeRegistry;
        this.eventMonitor = eventMonitor;
    }

    /**
     * 受理任务：生成内核任务 ID，发布 queued 事件（异步），返回 kernelTaskId（即对外 rmf_task_id）。
     */
    public String submit(TaskRecord record, String robotCode, TaskGoal goal) {
        String kernelTaskId = generateKernelTaskId(record);
        String owner = record.getPayload() == null || record.getPayload().get("taskflow_id") == null
                ? null : String.valueOf(record.getPayload().get("taskflow_id"));
        if (owner != null && owner.isBlank()) owner = null;
        assignmentRegistry.registerTask(kernelTaskId, robotCode, owner);
        taskStates.put(kernelTaskId, new KernelTaskState(record.getSchedulerRequestId(), robotCode,
                goal, ExecutionPhase.QUEUED, mapManager.bindTask()));
        // queued 事件异步发布：保证 dispatch 响应先以 published 语义返回（旧栈回执链路行为）
        try {
            executor.submit(() -> {
                try {
                    publishState(kernelTaskId, "queued", record, robotCode, goal, null);
                    execute(kernelTaskId);
                } finally {
                    retireTask(kernelTaskId);
                }
            });
        } catch (RuntimeException e) {
            retireTask(kernelTaskId);
            throw e;
        }
        return kernelTaskId;
    }

    /**
     * 受理自动分配任务（无指定机器人）：登记 pending 队列并立即尝试分配；
     * 无可用机器人时保持排队（AssignmentScanner 周期重试，超时 failed）。
     */
    public String submitForAutoAssign(TaskRecord record, TaskGoal goal, String fleetName, String robotGroup) {
        String kernelTaskId = generateKernelTaskId(record);
        String owner = record.getPayload() == null || record.getPayload().get("taskflow_id") == null
                ? null : String.valueOf(record.getPayload().get("taskflow_id"));
        if (owner != null && owner.isBlank()) owner = null;
        assignmentRegistry.registerTask(kernelTaskId, null, owner);
        taskStates.put(kernelTaskId, new KernelTaskState(record.getSchedulerRequestId(), null,
                goal, ExecutionPhase.QUEUED, mapManager.bindTask()));
        long deadline = System.currentTimeMillis() + assignProperties.getAssignmentTimeoutSeconds() * 1000;
        pendingAssignments.put(kernelTaskId, new PendingAssignment(kernelTaskId, record, goal,
                fleetName, robotGroup, deadline));
        try {
            executor.submit(() -> {
                try {
                    publishState(kernelTaskId, "queued", record, null, goal, null);
                } catch (RuntimeException e) {
                    pendingAssignments.remove(kernelTaskId);
                    retireTask(kernelTaskId);
                    throw e;
                }
            });
            tryAssignOnce(kernelTaskId);
        } catch (RuntimeException e) {
            pendingAssignments.remove(kernelTaskId);
            retireTask(kernelTaskId);
            throw e;
        }
        return kernelTaskId;
    }

    /**
     * 自动分配扫描（周期 5s）：重试 pending 任务分配；超时发布 failed。
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 5000)
    public void retryPendingAssignments() {
        long now = System.currentTimeMillis();
        for (PendingAssignment pending : pendingAssignments.values()) {
            KernelTaskState state = taskStates.get(pending.kernelTaskId());
            if (state == null) {
                // 已取消
                pendingAssignments.remove(pending.kernelTaskId());
                continue;
            }
            if (now > pending.deadlineMillis()) {
                pendingAssignments.remove(pending.kernelTaskId());
                retireTask(pending.kernelTaskId());
                publishRaw(pending.kernelTaskId(), pending.record(), null, pending.goal(),
                        "failed", null, "自动分配超时：无可用机器人在 "
                                + assignProperties.getAssignmentTimeoutSeconds() + "s 内");
                log.warn("自动分配超时失败: task={} 无可用机器人在 {}s 内",
                        pending.kernelTaskId(), assignProperties.getAssignmentTimeoutSeconds());
                continue;
            }
            tryAssignOnce(pending.kernelTaskId());
        }
    }

    private void tryAssignOnce(String kernelTaskId) {
        PendingAssignment pending = pendingAssignments.get(kernelTaskId);
        if (pending == null) {
            return;
        }
        var decision = taskAssigner.selectWithTrace(pending.goal(), pending.fleetName(), pending.robotGroup(),
                assignmentRegistry.taskOwner(kernelTaskId));
        if (decision.selected().isEmpty()) {
            return;
        }
        String robotCode = decision.selected().get().robotCode();
        if (!assignmentRegistry.assignTask(kernelTaskId, robotCode)) return;
        if (!pendingAssignments.remove(kernelTaskId, pending)) return;
        KernelTaskState state = taskStates.get(kernelTaskId);
        if (state == null) {
            return;
        }
        state.assignRobot(robotCode);
        state.setAssignmentTrace(decision.traceJson());
        log.info("任务自动分配: task={} robot={} score={}", kernelTaskId, robotCode, decision.summary());
        // 分配透明化：发布带评分明细的 queued 事件（robot 已定，等待就绪）
        publishRaw(kernelTaskId, pending.record(), robotCode, pending.goal(), "queued",
                null, decision.summary(), Map.of("assignment_trace", decision.traceJson()));
        // 立即占用机器人：同步置 running（不等下一帧状态刷新），
        // 防止并发提交的后续任务因 mode 仍为 idle 而堆给同一台；
        // 不动 dispatchable（执行链路 waitForRobotReady 依赖它，FleetManager 帧刷新会正常维护）
        fleetStateMirror.list().stream()
                .filter(robot -> robotCode.equals(robot.getRobotCode()))
                .findFirst()
                .ifPresent(robot -> {
                    robot.setMode("running");
                    fleetStateMirror.upsert(robot);
                });
        try {
            executor.submit(() -> execute(kernelTaskId));
        } catch (RuntimeException e) {
            retireTask(kernelTaskId);
            throw e;
        }
    }

    /**
     * 取消任务：排队中 → canceled；执行中 → 机器人 stop + completed + cancellation（fork 语义）；
     * 内核态不存在（调度重启丢失的孤儿记录）→ 止损取消（发 stop + 发布终态，避免记录永久挂起）。
     */
    public boolean cancel(String kernelTaskId, TaskRecord record, String robotCode, String reason) {
        KernelTaskState state = taskStates.get(kernelTaskId);
        if (state == null) {
            // 区分两种 state==null：①正常终态已 retire（幂等返回，不触发孤儿止损）②调度重启丢失（真孤儿）。
            // 不区分会把已终态任务误标"孤儿取消"——覆盖真实失败原因（如到点确认失败）并给空闲机器人重复发 stop。
            Long retiredAt = retiredTerminalTasks.get(kernelTaskId);
            if (retiredAt != null) {
                log.info("任务已是终态（{} 前完成），取消请求幂等返回: task={}",
                        java.time.Instant.ofEpochMilli(retiredAt), kernelTaskId);
                return false;
            }
            return cancelOrphan(kernelTaskId, record, robotCode, reason);
        }
        if (state.phase() == ExecutionPhase.DONE) {
            return false;
        }
        pendingAssignments.remove(kernelTaskId);
        if (state.phase() == ExecutionPhase.QUEUED) {
            state.markCanceled();
            retireTask(kernelTaskId);
            publishRaw(kernelTaskId, record, robotCode, state.goal(), "canceled", null, "排队任务取消");
            log.info("排队任务已取消: task={} robot={}", kernelTaskId, robotCode);
            return true;
        }
        // 执行中：标记取消，执行线程在下个检查点停止
        state.markCanceled();
        routeRegistry.clear(robotCode);
        if (robotCode != null) {
            retainAndStop(robotCode, "执行中任务取消");
        }
        Map<String, Object> cancellation = new LinkedHashMap<>();
        cancellation.put("labels", List.of("source=robot_scheduler", "reason=" + (reason == null ? "unspecified" : reason)));
        publishRaw(kernelTaskId, record, robotCode, state.goal(), "completed", cancellation, "任务取消（fork 语义）",
                terminalDetail(state, Map.of()));
        log.info("执行中任务已取消（fork 语义 completed+cancellation）: task={} robot={}", kernelTaskId, robotCode);
        retireTask(kernelTaskId);
        return true;
    }

    /**
     * 孤儿任务取消：调度重启导致内核态丢失、DB 记录仍挂非终态（表现：取消请求永远 cancel_requested）。
     * 机器人侧若有残留任务一并 stop 止损（stop 不带 body_task_id，本体无任务时 RMS 回 task not found，无害）。
     */
    private boolean cancelOrphan(String kernelTaskId, TaskRecord record, String robotCode, String reason) {
        String robot = robotCode != null && !robotCode.isBlank() ? robotCode
                : (record == null ? null : record.getRobotCode());
        if (robot != null && !robot.isBlank()) {
            Object owner = record == null || record.getPayload() == null ? null : record.getPayload().get("taskflow_id");
            assignmentRegistry.assertCanStopOrphan(robot, owner == null ? null : String.valueOf(owner));
            retainAndStop(robot, "任务取消（孤儿止损）");
        }
        Map<String, Object> cancellation = new LinkedHashMap<>();
        cancellation.put("labels", List.of("source=robot_scheduler",
                "reason=" + (reason == null ? "unspecified" : reason)));
        publishRaw(kernelTaskId, record, robot, null, "completed", cancellation,
                "任务取消（调度重启后孤儿任务，fork 语义）");
        log.info("孤儿任务已取消（内核态已随重启丢失，仅记录止损）: task={} robot={}", kernelTaskId, robot);
        return true;
    }

    /** 普通常见异常的统一收口：冻结预约、停止旧命令并禁派，后续由状态帧接管。 */
    private void retainAndStop(String robotCode, String reason) {
        boolean first;
        try {
            first = trafficRegistry.retainUntilStopped(robotCode, reason);
        } catch (RuntimeException failure) {
            // 持久化实现已冻结本进程占用；仍尝试停止，但不能因写盘异常跳过任务终态收口。
            first = true;
            log.error("冻结占用持久化失败，继续停止止损: robot_code={}", robotCode, failure);
        }
        fleetStateMirror.list().stream().filter(robot -> robotCode.equals(robot.getRobotCode()))
                .findFirst().ifPresent(robot -> robot.setDispatchable(false));
        commandExecutor.abandon(robotCode, reason);
        if (!first) return;
        // null bodyTaskId 表示停止当前任务，避免未知业务任务 ID 导致 RMS 拒绝。
        if (commandExecutor.stop(robotCode, 0, null) == com.zioneer.robotscheduler.core.fleet.RobotCmdResult.SUCCESS) {
            try {
                trafficRegistry.markStopAccepted(robotCode);
            } catch (RuntimeException failure) {
                log.error("停止接受状态持久化失败，保持等待停止确认: robot_code={}", robotCode, failure);
            }
        }
        log.warn("保留交通占用，等待停止确认: robot={} reason={}", robotCode, reason);
    }

    /** 已终态任务登记（内核态 retire 时间戳）：区分"正常终态清理"与"重启孤儿"，防取消误触发孤儿止损。 */
    private final Map<String, Long> retiredTerminalTasks = new ConcurrentHashMap<>();

    private void retireTask(String kernelTaskId) {
        KernelTaskState removed = taskStates.remove(kernelTaskId);
        retiredTerminalTasks.put(kernelTaskId, System.currentTimeMillis());
        if (retiredTerminalTasks.size() > 4096) {
            retiredTerminalTasks.entrySet().removeIf(e ->
                    System.currentTimeMillis() - e.getValue() > 2 * 3600_000L);
        }
        if (removed != null) {
            waitIntents.clear(removed.robotCode(), kernelTaskId);
            removed.retire();
            synchronized (removed) {
                if (!removed.executionActive) assignmentRegistry.finishTask(kernelTaskId);
            }
        }
    }

    private void execute(String kernelTaskId) {
        KernelTaskState state = taskStates.get(kernelTaskId);
        if (state == null || !state.beginExecution()) {
            return;
        }
        try {
            executeBoundTask(kernelTaskId, state);
        } finally {
            retireTask(kernelTaskId);
            waitIntents.clear(state.robotCode(), kernelTaskId);
            state.finishExecution();
            try {
                assignmentRegistry.finishTask(kernelTaskId);
            } catch (RuntimeException failure) {
                retainAndStop(state.robotCode(), "执行结束登记未持久化，等待停止确认");
                log.error("执行登记收口失败，保留资源并后台重试: robot_code={} task_id={}",
                        state.robotCode(), kernelTaskId, failure);
            }
        }
    }

    private void executeBoundTask(String kernelTaskId, KernelTaskState state) {
        String robotCode = state.robotCode();
        Semaphore semaphore = robotSemaphores.computeIfAbsent(robotCode, k -> new Semaphore(1));
        try {
            // 同机串行：排队等待期间任务保持 queued
            if (!semaphore.tryAcquire(robotReadyTimeoutSeconds * 3, TimeUnit.SECONDS)) {
                publishTerminal(kernelTaskId, state, "failed",
                        "机器人 " + robotCode + " 执行队列繁忙超时（同机排队任务过多）");
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            if (state.isCanceled() || taskStates.get(kernelTaskId) == null) {
                return;
            }
            boolean ready;
            try {
                ready = waitForRobotReady(kernelTaskId, robotCode, state);
            } catch (RuntimeException failure) {
                publishTerminal(kernelTaskId, state, "failed", "机器人执行登记未能持久化，请稍后重试");
                return;
            }
            if (!ready) {
                if (state.isCanceled()) {
                    return;
                }
                String reason = fleetStateMirror.list().stream()
                        .filter(r -> robotCode.equals(r.getRobotCode()))
                        .findFirst()
                        .map(r -> {
                            if (Boolean.TRUE.equals(r.getDecommissioned())) {
                                return "机器人已停用（decommissioned），不参与调度";
                            }
                            if (!r.isOnline()) {
                                return "机器人离线（状态帧超时，检查 RMS 连接）";
                            }
                            if (trafficRegistry.isAwaitingStop(robotCode)) {
                                return "停止确认中，交通占用保留";
                            }
                            // 确定性根因优先（配置级问题不会 30s 自愈）：
                            // mode=running 可能是本任务自身造成的（受理事件即置 running），不能当根因
                            String activeMap = state.mapBinding.snapshot().map(MapSnapshot::metadata)
                                    .map(m -> m.getMapName()).orElse(null);
                            if (activeMap != null && r.getSlamMapCode() != null
                                    && !activeMap.equals(r.getSlamMapCode())) {
                                return "机器人地图与调度不一致（机器人: " + r.getSlamMapCode()
                                        + " vs 调度: " + activeMap + "），已禁止派发";
                            }
                            if ("paused".equals(r.getMode()) || "error".equals(r.getMode())
                                    || "unknown".equals(r.getMode())) {
                                return "机器人状态异常（mode=" + r.getMode() + "）";
                            }
                            if ("charging".equals(r.getMode())) {
                                return "机器人充电中，暂不可派发";
                            }
                            if ("running".equals(r.getMode())) {
                                return "机器人忙碌（外部任务执行中/移动中）";
                            }
                            return "机器人离图（位置无法吸附到地图拓扑，临时不可派发；回到图上自动恢复）";
                        })
                        .orElse("机器人不可见（车队镜像无此机器人）");
                publishTerminal(kernelTaskId, state, "failed",
                        "机器人 " + robotCode + " 不可派发（"
                                + (assignmentRegistry.activeOperation(robotCode) == null ? reason : "外部作业未确认结束")
                                + "），等待超时");
                return;
            }
            // underway 事件保留分配评分明细（mirror 只存最新事件，trace 需随状态迁移携带）
            Map<String, Object> underwayDetail = state.assignmentTrace() == null
                    ? Map.of() : Map.of("assignment_trace", state.assignmentTrace());
            publishRaw(kernelTaskId, null, robotCode, state.goal(), "underway", null,
                    "开始执行", underwayDetail);
            state.markRunning();
            executeGoal(kernelTaskId, state, robotCode);
        } finally {
            semaphore.release();
        }
    }

    private void executeGoal(String kernelTaskId, KernelTaskState state, String robotCode) {
        TaskGoal goal = state.goal();
        try {
            if (goal.requiresNavigation()) {
                WaypointMetadata target = resolveWaypoint(state, goal);
                if (target == null) {
                    publishTerminal(kernelTaskId, state, "failed",
                            "目标点位不存在: " + goal.goalWaypointId());
                    return;
                }
                // 充电位被其他机器人驻留占用 → 自动改派最近空闲充电位（9 桩场景不该死等一桩 5 分钟）
                if (TaskCategories.CHARGE.equals(goal.category())) {
                    var swapped = swapChargeTargetIfBlocked(kernelTaskId, state, robotCode, goal, target);
                    if (swapped != null) {
                        goal = swapped;
                        target = resolveWaypoint(state, goal);
                    }
                }
                NavigationLease lease = acquireTraffic(kernelTaskId, state, robotCode, target);
                if (lease == null) {
                    if (state.isCanceled()) {
                        return;
                    }
                    // 精准失败原因：区分目标点被驻留占用 vs 路径资源长期被占
                    String blockedDetail = describeTrafficBlock(state, robotCode, target);
                    publishTerminal(kernelTaskId, state, "failed",
                            state.staticRouteUnreachable ? blockedDetail
                                    : "交通资源占用失败（等待 " + TRAFFIC_WAIT_LIMIT_SECONDS + "s 超时）: " + blockedDetail);
                    return;
                }
                // 活动路线登记（可视化数据源）+ 任务审计（终态事件 detail.route_waypoints）
                // 路线随整个任务存活（终态清除）：作业/充电阶段机器人仍坐在目标环上
                routeRegistry.register(robotCode, kernelTaskId, resolveFloorId(state, goal),
                        lease.routeWaypointIds(), boundGraph(state).orElse(null));
                state.setRouteWaypointIds(lease.routeWaypointIds());
                log.info("导航路线规划完成: robot_code={} task={} route={}", robotCode, kernelTaskId,
                        String.join(" -> ", lease.routeWaypointIds()));
                publishRaw(kernelTaskId, null, robotCode, goal, "underway", null,
                        "导航路线已规划", withAssignmentTrace(state, Map.of()));
                boolean arrived = false;
                try {
                    arrived = navigateBySegments(kernelTaskId, state, robotCode, goal, lease, target);
                } finally {
                    // 终态原子切换：到达目标 → 释放租约同时登记终点驻留。
                    // 若先 releaseAll 再等状态帧（1Hz）登记驻留，中间存在幽灵窗口——
                    // 并发任务在窗口内拿到"空点"租约直达，与原地车辆物理叠车（压测实测 P4 双车同点）
                    if (arrived) {
                        trafficRegistry.releaseAllAndDwell(robotCode, target.getId());
                    } else {
                        if (state.navigationInFlight) retainAndStop(robotCode, "导航未确认完成");
                        trafficRegistry.releaseAll(robotCode);
                    }
                }
                if (!arrived) {
                    return;
                }
            }
            if (goal.requiresAction()) {
                long timeoutSeconds = actionTimeoutSeconds(goal);
                Map<String, Object> description = buildDescription(kernelTaskId, goal, null);
                // 充电前已在充的场景（任务发起时机器人就在桩上充电）：直接接管等待，
                // 不再发 start-activity（契约：本体仅认 start-activity 触发充电，is_charging=true
                // 只可能是任务前已在充；不在充电位上的 is_charging = 异常状态 → 止损）
                if (TaskCategories.CHARGE.equals(goal.category()) && isBodyCharging(robotCode)) {
                    if (!onChargeWaypoint(state, robotCode)) {
                        failPrematureCharging(kernelTaskId, state, robotCode, goal);
                        return;
                    }
                    completeByBodyAutoCharge(kernelTaskId, state, robotCode, goal, timeoutSeconds);
                    return;
                }
                // 动作阶段可见性：不发事件的话状态停留在最后一条导航消息（stale），看不出在等动作回执
                publishRaw(kernelTaskId, null, robotCode, goal, "underway", null,
                        "开始" + zhAction(goal.action()) + "（回执等待最长 "
                                + (timeoutSeconds >= 3600 ? timeoutSeconds / 3600 + "h" : timeoutSeconds + "s") + "）",
                        withAssignmentTrace(state, Map.of()));
                RobotCommandExecutor.CommandHandle handle = commandExecutor.submitActivity(
                        robotCode, goal.action(), goal.actionLabel(), description);
                RobotCommandExecutor.CommandResult result = await(handle, timeoutSeconds,
                        kernelTaskId, robotCode, "动作 " + goal.action());
                // 本体忙拒绝恢复（动作命令同样可能撞在途任务）
                if (!result.completed() && isBodyBusyReject(result.detail())
                        && recoverFromBodyBusy(robotCode, "动作 " + goal.action(), result.detail())) {
                    handle = commandExecutor.submitActivity(
                            robotCode, goal.action(), goal.actionLabel(), description);
                    result = await(handle, timeoutSeconds,
                            kernelTaskId, robotCode, "动作(重试) " + goal.action());
                }
                Map<String, Object> extraDetail = new HashMap<>();
                if (result.receipt() != null && !result.receipt().isEmpty()) {
                    extraDetail.put("action_receipt", result.receipt());
                    // 质检结果提取（机器人回执 raw_result.data 里的业务数据）
                    Object rawResult = result.receipt().get("raw_result");
                    if (rawResult instanceof Map<?, ?> rawMap) {
                        Object data = rawMap.get("data");
                        if (data != null) {
                            extraDetail.put("inspection_result", data);
                        }
                    }
                }
                if (!result.completed()) {
                    if (state.isCanceled()) {
                        return;
                    }
                    if (result.receipt() == null) retainAndStop(robotCode, "业务动作未获得终态确认");
                    publishTerminal(kernelTaskId, state, "failed",
                            "业务动作未完成 [" + goal.action() + "]: " + safeDetail(result.detail()),
                            extraDetail);
                    return;
                }
                // 动作成功：若配置了停靠等待点（parameters.end_point），先让出作业点再完成
                if (goal.requiresStandby() && !state.isCanceled()) {
                    String standbyMsg = navigateToStandby(kernelTaskId, state, robotCode, goal);
                    if (standbyMsg == null) {
                        // 停靠失败：任务仍算完成（质检已成功），仅停靠段告警
                        extraDetail.put("standby_warning", "质检完成但停靠点导航失败");
                    } else {
                        extraDetail.put("standby_waypoint", goal.standbyWaypointId());
                    }
                }
                // 发布携带回执明细的 completed 事件（质检结果供上层/观测页读取）
                retireTask(kernelTaskId);
                routeRegistry.clear(robotCode);
                publishRaw(kernelTaskId, null, robotCode, goal, "completed", null,
                        "动作完成: " + goal.action()
                                + (extraDetail.containsKey("standby_waypoint")
                                        ? "，已停靠 " + extraDetail.get("standby_waypoint")
                                        : goal.requiresStandby() ? "，停靠未完成，请查看停靠告警" : ""),
                        terminalDetail(state, extraDetail));
                log.info("任务完成: task={} robot={} category={} receipt={}", kernelTaskId, robotCode,
                        goal.category(), result.receipt() == null ? "无" : "已携带");
                return;
            }
            if (state.isCanceled()) {
                return;
            }
            retireTask(kernelTaskId);
            routeRegistry.clear(robotCode);
            publishRaw(kernelTaskId, null, robotCode, goal, "completed", null, null,
                    terminalDetail(state, Map.of()));
            log.info("任务完成: task={} robot={} category={}", kernelTaskId, robotCode, goal.category());
        } catch (Exception e) {
            log.error("任务执行异常: task={} robot={}", kernelTaskId, robotCode, e);
            if (state.navigationInFlight) retainAndStop(robotCode, "导航执行异常");
            trafficRegistry.releaseAll(robotCode);
            routeRegistry.clear(robotCode);
            retireTask(kernelTaskId);
            // 兜底异常中文化（JGraphT 等三方异常对用户不友好）
            String friendlyMessage = e instanceof IllegalArgumentException
                    ? "路径规划异常（起点/终点不可用），请检查机器人位置与目标点位"
                    : "任务执行异常: " + e.getMessage();
            publishRaw(kernelTaskId, null, robotCode, goal, "failed", null, friendlyMessage,
                    terminalDetail(state, Map.of()));
        }
    }

    /**
     * 分段导航（对齐旧 RMF 行为）：按规划路径途经点逐段下发 navigate，
     * 每段到达后释放身后资源并继续下一段，直到最终目标点。
     * 长路径拆段可规避机器人导航栈的单次目标过远问题。
     *
     * @return true 表示已到达最终目标点；false 表示失败/取消（终态已发布或由取消链路发布）
     */
    private boolean navigateBySegments(String kernelTaskId, KernelTaskState state, String robotCode,
                                       TaskGoal goal, NavigationLease lease, WaypointMetadata target) {
        String floorId = resolveFloorId(state, goal);
        // 路径途经点（含起点）；无图/吸附失败时退化为单段直达
        List<String> routeWaypoints = lease.routeWaypointIds().isEmpty()
                ? List.of(target.getId())
                : lease.routeWaypointIds();
        // 吸附到目标拓扑点不等于本体已到位；零边路线仍下发最终坐标和朝向，等待真实完成回执。
        if (routeWaypoints.size() == 1) {
            RobotSnapshot snapshot = fleetStateMirror.list().stream()
                    .filter(robot -> robotCode.equals(robot.getRobotCode())).findFirst().orElse(null);
            double yawDelta = snapshot == null ? Double.POSITIVE_INFINITY
                    : Math.atan2(Math.sin(snapshot.getYaw() - target.getTheta()),
                            Math.cos(snapshot.getYaw() - target.getTheta()));
            boolean alreadyAtPose = validArrivalSnapshot(snapshot, 0)
                    && Math.hypot(snapshot.getX() - target.getX(), snapshot.getY() - target.getY())
                            < POSE_EQUALITY_EPSILON
                    && Math.abs(yawDelta) < POSE_EQUALITY_EPSILON;
            if (!alreadyAtPose) routeWaypoints = List.of(target.getId(), target.getId());
        }
        List<com.zioneer.robotscheduler.core.routing.NavigationRoute.Segment> segments =
                lease.segments();
        String currentWaypoint = routeWaypoints.get(0);
        for (int i = 1; i < routeWaypoints.size(); i++) {
            if (state.isCanceled()) return false;
            String next = routeWaypoints.get(i);
            WaypointMetadata waypoint = boundGraph(state)
                    .flatMap(graph -> graph.waypoint(floorId, next))
                    .orElse(null);
            if (waypoint == null) {
                publishTerminal(kernelTaskId, state, "failed", "路径途经点不存在: " + next);
                return false;
            }
            Map<String, Object> description = buildDescription(kernelTaskId, goal, next,
                    // 充电任务全程不带模板标记：本体对带标记任务会自充且行为不稳定（收到即充/到点才充
                    // 两种表现），导航统一为纯移动；到位后走 start-activity 显式充电（旧栈验证过的契约）
                    !TaskCategories.CHARGE.equals(goal.category()));
            double timeoutSeconds = segmentTimeoutSeconds(segments, i - 1, waypoint, target);
            Double speedLimit = i - 1 < segments.size() ? segments.get(i - 1).maxSpeedMetersPerSecond()
                    : finalApproachSpeed(state, robotCode, waypoint);
            if (speedLimit <= 0) {
                publishTerminal(kernelTaskId, state, "failed", "目标点附近的最终导航穿越禁行区域，拒绝下发");
                return false;
            }
            if (i - 1 >= segments.size()) {
                timeoutSeconds = Math.max(timeoutSeconds, Math.hypot(latestRobotX(robotCode) - waypoint.getX(),
                        latestRobotY(robotCode) - waypoint.getY()) / speedLimit + navigationTimeoutBaseSeconds);
            }
            // 段前复检（动态冲突防护）：全路线租约申请时世界是旧的——申请后外部移动的车、
            // 失联僵尸车、新驻留的车可能落在"下一段终点/路段"上。发车前复核，被占则原地等待让行，
            // 防止按过期租约放车直接穿过别人的车身（压测实测：静止车停在途经点上被直接穿越）
            if (!awaitSegmentEntryClear(kernelTaskId, state, robotCode, currentWaypoint, next,
                    i - 1 < segments.size() ? segments.get(i - 1).id() : null)) {
                return false;
            }
            state.navigationInFlight = true;
            long commandIssuedAt = System.currentTimeMillis();
            RobotCommandExecutor.CommandHandle handle = commandExecutor.submitNavigation(
                    robotCode, floorId, waypoint.getX(), waypoint.getY(), waypoint.getTheta(),
                    speedLimit, description);
            RobotCommandExecutor.CommandResult result = awaitChargeAware(handle, timeoutSeconds,
                    kernelTaskId, robotCode, "导航段 " + currentWaypoint + "->" + next, state);
            // 本体忙拒绝恢复：stop 清除卡死的在途任务后重试一次（真机任务完成不自动关闭的场景）
            if (!result.completed() && isBodyBusyReject(result.detail())
                    && recoverFromBodyBusy(robotCode, "导航段 " + currentWaypoint + "->" + next, result.detail())) {
                commandIssuedAt = System.currentTimeMillis();
                handle = commandExecutor.submitNavigation(
                        robotCode, floorId, waypoint.getX(), waypoint.getY(), waypoint.getTheta(),
                        speedLimit, description);
                result = awaitChargeAware(handle, timeoutSeconds,
                        kernelTaskId, robotCode, "导航段(重试) " + currentWaypoint + "->" + next, state);
            }
            // 本体自动充电兜底接管（兼容路径）：部分本体到位后自行开始充电（is_charging=true，回执不再来），
            // 检测到时若已在充电位上则跳过剩余导航段，交给动作阶段接管等待。
            // 防御：不在充电位上的 is_charging = 本体提前自充（未校验点位类型）→ 止损
            if (TaskCategories.CHARGE.equals(goal.category()) && isBodyCharging(robotCode)) {
                if (!onChargeWaypoint(state, robotCode)) {
                    failPrematureCharging(kernelTaskId, state, robotCode, goal);
                    return false;
                }
                publishRaw(kernelTaskId, null, robotCode, goal, "underway", null,
                        "本体已自动开始充电，调度接管充电阶段（剩余导航段跳过）",
                        withAssignmentTrace(state, Map.of()));
                return true;
            }
            if (!result.completed()) {
                if (state.isCanceled()) {
                    return false;
                }
                retainAndStop(robotCode, "导航分段未确认完成");
                publishTerminal(kernelTaskId, state, "failed",
                        "导航分段未完成 [" + currentWaypoint + "->" + next + "]: "
                                + safeDetail(result.detail()));
                return false;
            }
            if (state.isCanceled()) {
                return false;
            }
            // 回执成功不代表实际到点：分级容差复核（途经 0.5m / 终点 0.3m），位置滞后时有限等待后再决定失败。
            boolean finalLeg = i >= routeWaypoints.size() - 1;
            double tolerance = finalLeg ? FINAL_ARRIVAL_TOLERANCE_METERS : WAYPOINT_PASS_TOLERANCE_METERS;
            if (!withinTolerance(arrivalDeviation(robotCode, waypoint, commandIssuedAt), tolerance)) {
                publishRaw(kernelTaskId, null, robotCode, goal, "underway", null,
                        "导航回执成功，等待实际到点确认: " + next + String.format("（位置容差 %.2fm）", tolerance),
                        withAssignmentTrace(state, Map.of()));
            }
            double deviation = confirmArrivalDeviation(robotCode, waypoint, kernelTaskId, next,
                    commandIssuedAt, state, tolerance);
            if (state.isCanceled()) return false;
            if (!withinTolerance(deviation, tolerance)) {
                String detail = String.format(
                        "回执成功但实际未到点 %s：偏差 %.3fm（容差 %.2fm，目标 (%.4f,%.4f)，实际 (%.4f,%.4f)）；位置缺失、过期或离线同样不能确认到点，绑定地图 %s",
                        next, deviation, tolerance, waypoint.getX(), waypoint.getY(), latestRobotX(robotCode), latestRobotY(robotCode),
                        state.mapBinding.snapshot().map(MapSnapshot::metadata).map(m -> m.getMapName()).orElse("未知"));
                log.warn("导航到点确认失败: task={} robot_code={} cmd_id={} target={} {}",
                        kernelTaskId, robotCode, handle.cmdId(), next, detail);
                retainAndStop(robotCode, "导航到达位置异常");
                publishTerminal(kernelTaskId, state, "failed", "导航分段异常: " + detail);
                return false;
            }
            state.navigationInFlight = false;
            // 到达本段终点：释放已走过的路径段 + 身后途经点（交通资源渐进释放）
            if (segments != null && i - 1 < segments.size()) {
                trafficRegistry.releaseSegments(robotCode, List.of(segments.get(i - 1).id()));
            }
            // 零边路线的起点就是目标，目标预约须保留到 releaseAllAndDwell 原子接续物理驻留。
            if (!currentWaypoint.equals(next)) trafficRegistry.releaseBehind(robotCode, currentWaypoint);
            routeRegistry.advance(robotCode, next);
            // 分段进度事件（可观测：每段到达即更新；携带分配评分保持 trace 可见）
            publishRaw(kernelTaskId, null, robotCode, goal, "underway", null,
                    String.format("导航进度 %d/%d：已到达 %s", i, routeWaypoints.size() - 1, next),
                    withAssignmentTrace(state, Map.of()));
            currentWaypoint = next;
        }
        return true;
    }

    /** 零边路线也要遵守实际位置到目标之间的楼层限速及区域约束，不能直接使用全局最高速度。 */
    private double finalApproachSpeed(KernelTaskState state, String robotCode, WaypointMetadata target) {
        double speed = assignProperties.getNavigationMaxSpeedMps();
        String floorId = resolveFloorId(state, state.goal());
        var floor = state.mapBinding.snapshot().stream()
                .flatMap(snapshot -> snapshot.metadata().getFloors().stream())
                .filter(candidate -> floorId.equals(candidate.getFloorId()))
                .findFirst().orElse(null);
        if (floor == null) return speed;
        speed = Math.min(speed, floor.getDefaultMaxSpeed());
        for (var zone : floor.getZoneConstraints()) {
            if (GeometryUtil.segmentIntersectsPolygon(
                    latestRobotX(robotCode), latestRobotY(robotCode), target.getX(), target.getY(),
                    zone.getVertices())) {
                if ("forbidden".equals(zone.getType())) return 0;
                if (zone.getMaxSpeed() != null) speed = Math.min(speed, zone.getMaxSpeed());
            }
        }
        return speed;
    }

    /** 段级超时：段长/保守速度 + 基础余量，下限为基础超时。 */
    private double segmentTimeoutSeconds(List<com.zioneer.robotscheduler.core.routing.NavigationRoute.Segment> segments,
                                         int segmentIndex, WaypointMetadata waypoint, WaypointMetadata target) {
        double length = 0;
        double timeoutSpeed = segmentConservativeSpeedMps;
        if (segments != null && segmentIndex < segments.size()) {
            length = segments.get(segmentIndex).lengthMeters();
            // 限速可能低于保守巡航速度，超时预算不能短于按实际下发限速行驶的时间。
            timeoutSpeed = Math.min(timeoutSpeed, segments.get(segmentIndex).maxSpeedMetersPerSecond());
        } else {
            length = Math.hypot(waypoint.getX() - target.getX(), waypoint.getY() - target.getY());
        }
        return Math.max(navigationTimeoutBaseSeconds,
                length / timeoutSpeed + navigationTimeoutBaseSeconds);
    }

    /**
     * 本体自动充电接管完成路径：等待充电结束（is_charging 变 false = 充满/被停）或超时，
     * 不下发 start-activity（真机 charge 模板 body 任务自带自充语义，重复下发会被拒并误触 stop 防御）。
     */
    private void completeByBodyAutoCharge(String kernelTaskId, KernelTaskState state, String robotCode,
                                          TaskGoal goal, long timeoutSeconds) {
        publishRaw(kernelTaskId, null, robotCode, goal, "underway", null,
                "本体自动充电中，等待充电结束（最长 " + (timeoutSeconds >= 3600 ? timeoutSeconds / 3600 + "h" : timeoutSeconds + "s") + "）",
                withAssignmentTrace(state, Map.of()));
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000;
        while (System.currentTimeMillis() < deadline) {
            if (state.isCanceled()) {
                return;
            }
            if (!isBodyCharging(robotCode)) {
                break;
            }
            try {
                TimeUnit.SECONDS.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        retireTask(kernelTaskId);
        routeRegistry.clear(robotCode);
        Map<String, Object> detail = new HashMap<>();
        detail.put("body_auto_charge", true);
        publishRaw(kernelTaskId, null, robotCode, goal, "completed", null,
                "充电完成（本体自动充电，调度接管等待）", terminalDetail(state, detail));
        log.info("任务完成(本体自动充电): task={} robot={}", kernelTaskId, robotCode);
    }

    /** 动作中文名（状态事件文案）。 */
    private String zhAction(String action) {
        return switch (action == null ? "" : action) {
            case "charge" -> "充电";
            case "quality_inspection" -> "质检";
            case "swap_battery" -> "换电";
            case "pause_task" -> "暂停";
            case "resume_task" -> "恢复";
            default -> action == null ? "动作" : action;
        };
    }

    /** 本体忙拒绝识别（真机任务状态机卡死防御：任务完成/取消未自动关闭需 stop 清除）。 */
    private boolean isBodyBusyReject(String detail) {
        if (detail == null) {
            return false;
        }
        // 两种契约变体：中文"已有在途任务"[BODY_START_TASK_REJECTED]；
        // 英文"task_id in flight (status=idle), stop first"[BODY_ADD_TASK_REJECTED]（取消未清干净的残留任务）
        return detail.contains("已有在途任务")
                || detail.contains("BODY_START_TASK_REJECTED")
                || detail.contains("BODY_ADD_TASK_REJECTED")
                || detail.contains("task_id in flight")
                || detail.contains("stop first");
    }

    /** busy 拒绝恢复：stop 清除本体在途任务后等待其生效。 */
    private boolean recoverFromBodyBusy(String robotCode, String stage, String detail) {
        log.warn("本体忙拒绝，发送 stop 清除在途任务后重试: robot={} stage={} detail={}",
                robotCode, stage, detail);
        if (commandExecutor.stop(robotCode, 0, null) != com.zioneer.robotscheduler.core.fleet.RobotCmdResult.SUCCESS) {
            retainAndStop(robotCode, "本体忙拒绝后停止请求失败");
            return false;
        }
        try {
            TimeUnit.MILLISECONDS.sleep(3000);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 本体充电中（管理接口 battery.is_charging，30s 缓存新鲜度）。 */
    private boolean isBodyCharging(String robotCode) {
        return fleetStateMirror.list().stream()
                .filter(r -> robotCode.equals(r.getRobotCode()))
                .findFirst()
                .map(r -> Boolean.TRUE.equals(r.getBodyCharging()))
                .orElse(false);
    }

    /** 自充接管必须处于本任务指定充电点 20cm 内，不能通过拓扑吸附绕过到点核验。 */
    private boolean onChargeWaypoint(KernelTaskState state, String robotCode) {
        WaypointMetadata target = resolveWaypoint(state, state.goal());
        return target != null && target.getType() == com.zioneer.robotscheduler.core.map.WaypointType.CHARGE
                && withinArrivalTolerance(arrivalDeviation(robotCode, target, 0));
    }

    /**
     * 本体提前充电止损：充电任务导航途中 is_charging=true 但机器人不在任何充电位上
     * （本体对模板任务自充时未校验点位类型）→ 停掉本体任务并快速失败，
     * 防止机器人在普通点上"假充电"占着机器人 1 小时。
     */
    private void failPrematureCharging(String kernelTaskId, KernelTaskState state, String robotCode,
                                       TaskGoal goal) {
        var target = resolveWaypoint(state, goal);
        double distance = target == null ? -1
                : Math.hypot(latestRobotX(robotCode) - target.getX(), latestRobotY(robotCode) - target.getY());
        String detail = String.format(
                "本体提前充电：当前位置不在任何充电位上（距充电目标 %s %.1fm），疑似本体对充电任务在导航段完成时自充且未校验点位类型，已停止本体任务，请检查本体充电逻辑",
                goal.goalWaypointId(), distance);
        log.warn("本体提前充电止损: task={} robot={} distance={}m", kernelTaskId, robotCode,
                String.format("%.1f", distance));
        commandExecutor.stop(robotCode, 0, null);
        publishTerminal(kernelTaskId, state, "failed", detail);
    }

    /**
     * 充电任务感知等待：分片轮询（0.5s），本体自动开始充电时立即返回（不必等命令超时才发现）。
     * 非充电任务退化为阻塞等待。
     */
    private RobotCommandExecutor.CommandResult awaitChargeAware(RobotCommandExecutor.CommandHandle handle,
                                                                 double timeoutSeconds, String kernelTaskId,
                                                                 String robotCode, String stage,
                                                                 KernelTaskState state) {
        if (!TaskCategories.CHARGE.equals(state.goal().category())) {
            return await(handle, timeoutSeconds, kernelTaskId, robotCode, stage);
        }
        long deadline = System.currentTimeMillis() + (long) (timeoutSeconds * 1000);
        while (System.currentTimeMillis() < deadline) {
            if (handle.future().isDone()) {
                return await(handle, 0.1, kernelTaskId, robotCode, stage);
            }
            if (isBodyCharging(robotCode)) {
                return RobotCommandExecutor.CommandResult.failed("BODY_AUTO_CHARGING");
            }
            try {
                TimeUnit.MILLISECONDS.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return RobotCommandExecutor.CommandResult.failed("命令等待被中断");
            }
        }
        return await(handle, 0.1, kernelTaskId, robotCode, stage);
    }

    private RobotCommandExecutor.CommandResult await(RobotCommandExecutor.CommandHandle handle,
                                                      double timeoutSeconds, String kernelTaskId,
                                                      String robotCode, String stage) {
        try {
            return handle.future().get((long) (timeoutSeconds * 1000), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            String detail = "命令等待超时 " + timeoutSeconds + "s（阶段: " + stage + "）";
            log.warn("命令等待超时: task={} robot={} stage={} timeout={}s",
                    kernelTaskId, robotCode, stage, timeoutSeconds);
            retainAndStop(robotCode, detail);
            return RobotCommandExecutor.CommandResult.failed(detail);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RobotCommandExecutor.CommandResult.failed("命令等待被中断");
        } catch (java.util.concurrent.ExecutionException e) {
            return RobotCommandExecutor.CommandResult.failed("命令执行异常: " + e.getCause().getMessage());
        }
    }

    private boolean waitForRobotReady(String kernelTaskId, String robotCode, KernelTaskState state) {
        long deadline = System.currentTimeMillis() + robotReadyTimeoutSeconds * 1000;
        long externalDeadline = System.currentTimeMillis() + TRAFFIC_WAIT_LIMIT_SECONDS * 1000;
        long nextNotice = 0;
        while (System.currentTimeMillis() < deadline) {
            if (state.isCanceled()) {
                return false;
            }
            var external = assignmentRegistry.activeOperation(robotCode);
            if (external != null) {
                deadline = externalDeadline;
                long now = System.currentTimeMillis();
                if (now >= nextNotice) {
                    publishRaw(kernelTaskId, null, robotCode, state.goal(), "queued", null,
                            "等待外部作业结束: operation_id=" + external.operationId());
                    nextNotice = now + TRAFFIC_WAIT_EVENT_INTERVAL_MILLIS;
                }
            }
            RobotSnapshot snapshot = fleetStateMirror.list().stream()
                    .filter(robot -> robotCode.equals(robot.getRobotCode()))
                    .findFirst()
                    .orElse(null);
            if (snapshot != null && !trafficRegistry.isAwaitingStop(robotCode)
                    && availability.canExecute(snapshot, assignmentRegistry.taskOwner(kernelTaskId))) {
                if (assignmentRegistry.beginExecution(kernelTaskId)) return true;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** 充电位被其他机器人驻留占用时自动改派最近空闲充电位（返回 null = 维持原目标走等待语义）。 */
    private TaskGoal swapChargeTargetIfBlocked(String kernelTaskId, KernelTaskState state, String robotCode,
                                               TaskGoal goal, WaypointMetadata target) {
        String dwellOwner = dwellOwnerOf(target.getId());
        if (dwellOwner == null || dwellOwner.equals(robotCode)) {
            return null;
        }
        var graph = boundGraph(state).filter(g -> g.isLoaded()).orElse(null);
        if (graph == null) {
            return null;
        }
        String floorId = resolveFloorId(state, goal);
        java.util.Set<String> occupied = trafficRegistry.occupiedWaypointsExcluding(robotCode);
        WaypointMetadata best = null;
        double bestDist = Double.MAX_VALUE;
        double rx = latestRobotX(robotCode);
        double ry = latestRobotY(robotCode);
        for (var candidate : graph.waypointsByType(floorId,
                com.zioneer.robotscheduler.core.map.WaypointType.CHARGE)) {
            if (candidate.getId().equals(target.getId()) || occupied.contains(candidate.getId())) {
                continue;
            }
            double dist = Math.hypot(candidate.getX() - rx, candidate.getY() - ry);
            if (dist < bestDist) {
                best = candidate;
                bestDist = dist;
            }
        }
        if (best == null) {
            // 全部充电位被占：无桩可换，维持原目标走等待语义
            return null;
        }
        Map<String, Object> params = new HashMap<>(goal.parameters() == null ? Map.of() : goal.parameters());
        params.put("charger_waypoint", best.getId());
        TaskGoal swapped = new TaskGoal(goal.category(), goal.floorId(), best.getId(), goal.action(),
                goal.actionLabel(), goal.standbyWaypointId(), params);
        state.setGoal(swapped);
        log.info("充电位被驻留占用，自动改派: task={} robot={} {} 被 {} 占用 -> {}",
                kernelTaskId, robotCode, target.getId(), dwellOwner, best.getId());
        publishRaw(kernelTaskId, null, robotCode, swapped, "underway", null,
                "充电位 " + target.getId() + " 被 " + dwellOwner + " 驻留占用，自动改派最近空闲充电位 " + best.getId(),
                withAssignmentTrace(state, Map.of()));
        return swapped;
    }

    /** 从交通正本查询驻留占用者，内存与持久化实现保持相同的等待诊断语义。 */
    private String dwellOwnerOf(String waypointId) {
        return trafficRegistry.dwellOwner(waypointId);
    }

    /** 交通阻塞精准描述：机器人离图 / 目标点驻留占用者 / 目标点任务占用者 / 一般路径冲突。 */
    private String describeTrafficBlock(KernelTaskState state, String robotCode, WaypointMetadata target) {
        if (state.staticRouteUnreachable) {
            return "地图静态拓扑/方向/禁区约束下无路到达 " + target.getId() + "，不是机器人占用等待";
        }
        if (trafficRegistry.isAwaitingStop(robotCode)) return "机器人停止确认中，交通占用保留";
        // 离图优先标注（吸不上图时其余占用描述没有意义；路段中间不算离图）
        var graph = boundGraph(state).orElse(null);
        if (graph != null && graph.isLoaded()
                && !graph.attach(resolveFloorId(state, state.goal()), latestRobotX(robotCode), latestRobotY(robotCode),
                        com.zioneer.robotscheduler.common.scheduler.SchedulerConstants.MAX_MERGE_WAYPOINT_DISTANCE,
                        com.zioneer.robotscheduler.common.scheduler.SchedulerConstants.MAX_MERGE_LANE_DISTANCE)
                        .attached()) {
            return "机器人当前位置无法吸附到地图拓扑（离图），等待回到图上";
        }
        String blockers = describeWaitingOwners(robotCode);
        if (blockers != null) return blockers;
        return "目标点 " + target.getId() + " 或必经路径被其他机器人长期占用";
    }

    private String describeWaitingOwners(String robotCode) {
        var blockers = waitIntents.blockers(robotCode, trafficRegistry.occupationSnapshot());
        if (blockers.isEmpty()) return null;
        String description = blockers.stream().limit(4).map(blocker -> {
            String points = blocker.waypointIds().stream().sorted().limit(3)
                    .collect(java.util.stream.Collectors.joining(","));
            String segments = blocker.segmentIds().stream().sorted().limit(2)
                    .collect(java.util.stream.Collectors.joining(","));
            return blocker.robotCode() + " 占用" + (points.isEmpty() ? "" : "点[" + points + "]")
                    + (segments.isEmpty() ? "" : "路段[" + segments + "]");
        }).collect(java.util.stream.Collectors.joining("；"));
        return description + (blockers.size() > 4 ? "；另有 " + (blockers.size() - 4) + " 台阻塞机器人" : "");
    }

    private NavigationLease acquireTraffic(String kernelTaskId, KernelTaskState state, String robotCode,
                                           WaypointMetadata target) {
        try {
            // 持续等待模式（对齐旧栈"任务等资源"语义）：冲突时不快速失败，
            // 循环重试直到占用成功或等待上限（5 分钟，防死等）；
            // 周期等待事件（原因+进度，8s 间隔保持活跃判定），长等待不再黑箱
            long deadline = System.currentTimeMillis() + TRAFFIC_WAIT_LIMIT_SECONDS * 1000;
            long startedAt = System.currentTimeMillis();
            long lastEventAt = 0;
            while (System.currentTimeMillis() < deadline) {
                if (state.isCanceled()) {
                    return null;
                }
                NavigationLease lease = tryAcquireOnce(kernelTaskId, state, robotCode, target);
                if (lease != null) {
                    return lease;
                }
                if (state.staticRouteUnreachable) return null;
                long now = System.currentTimeMillis();
                if (now - lastEventAt >= TRAFFIC_WAIT_EVENT_INTERVAL_MILLIS) {
                    lastEventAt = now;
                    publishRaw(kernelTaskId, null, robotCode, state.goal(), "underway", null,
                            String.format("交通等待中: %s（已等待 %ds / 上限 %ds）",
                                    describeTrafficBlock(state, robotCode, target),
                                    (now - startedAt) / 1000, TRAFFIC_WAIT_LIMIT_SECONDS),
                            withAssignmentTrace(state, Map.of()));
                }
                try {
                    TimeUnit.SECONDS.sleep(TRAFFIC_RETRY_INTERVAL_SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            waitIntents.clear(robotCode, kernelTaskId);
            return null;
        } finally {
            waitIntents.clear(robotCode, kernelTaskId);
        }
    }

    /**
     * 段前复检：下一段终点/路段是否被其他机器人占用（活动路线或驻留）。
     * 租约申请与实际到达该段之间有时间差，期间外部移动/后到驻留的机器人可能已占道。
     * 被占 → 原地等待（周期事件可见）；清空 → 放行；超时 → 失败止损；取消 → 静默退出。
     *
     * @return true 可放行；false 不可放行（终态已发布或由取消链路发布）
     */
    private boolean awaitSegmentEntryClear(String kernelTaskId, KernelTaskState state, String robotCode,
                                           String fromWaypoint, String nextWaypoint, String segmentId) {
        try {
            long deadline = System.currentTimeMillis() + TRAFFIC_WAIT_LIMIT_SECONDS * 1000;
            long startedAt = System.currentTimeMillis();
            long lastEventAt = 0;
            while (System.currentTimeMillis() < deadline) {
                if (state.isCanceled()) return false;
                if (trafficRegistry.isAwaitingStop(robotCode)) {
                    publishTerminal(kernelTaskId, state, "failed", "机器人停止确认中，交通占用保留");
                    return false;
                }
                waitIntents.record(robotCode, kernelTaskId, segmentId == null ? List.of() : List.of(segmentId),
                        Set.of(nextWaypoint));
                String blocker = segmentEntryBlocker(robotCode, nextWaypoint, segmentId);
                if (blocker == null) {
                    return true;
                }
                if (state.isCanceled()) {
                    return false;
                }
                long now = System.currentTimeMillis();
                if (now - lastEventAt >= TRAFFIC_WAIT_EVENT_INTERVAL_MILLIS) {
                    lastEventAt = now;
                    publishRaw(kernelTaskId, null, robotCode, state.goal(), "underway", null,
                            String.format("段前让行等待: %s->%s 前方被占（%s，已等 %ds / 上限 %ds）",
                                    fromWaypoint, nextWaypoint, blocker,
                                    (now - startedAt) / 1000, TRAFFIC_WAIT_LIMIT_SECONDS),
                            withAssignmentTrace(state, Map.of()));
                }
                try {
                    TimeUnit.SECONDS.sleep(TRAFFIC_RETRY_INTERVAL_SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            String blocker = segmentEntryBlocker(robotCode, nextWaypoint, segmentId);
            publishTerminal(kernelTaskId, state, "failed",
                    String.format("段前复检超时: %s->%s 前方持续被占（%s，等待 %ds 超限）",
                            fromWaypoint, nextWaypoint, blocker == null ? "未知" : blocker,
                            TRAFFIC_WAIT_LIMIT_SECONDS));
            return false;
        } finally {
            waitIntents.clear(robotCode, kernelTaskId);
        }
    }

    /** 下一段终点/路段的占用途径：其他机器人活动路线（点/段租约）或驻留（点/段）。 */
    private String segmentEntryBlocker(String robotCode, String nextWaypoint, String segmentId) {
        String owners = describeWaitingOwners(robotCode);
        if (owners != null) return owners;
        if (trafficRegistry.occupiedWaypointsExcluding(robotCode).contains(nextWaypoint)) {
            return "点 " + nextWaypoint + " 被占用";
        }
        if (segmentId != null
                && trafficRegistry.occupiedSegmentsExcluding(robotCode).contains(segmentId)) {
            return "路段 " + segmentId + " 被占用";
        }
        return null;
    }

    private NavigationLease tryAcquireOnce(String kernelTaskId, KernelTaskState state, String robotCode,
                                             WaypointMetadata target) {
        state.staticRouteUnreachable = false;
        for (int attempt = 1; attempt <= TRAFFIC_RETRY_TIMES; attempt++) {
            var graph = boundGraph(state).orElse(null);
            if (graph == null || !graph.isLoaded()) {
                // 无图模式：只占用目标点位
                if (trafficRegistry.tryAcquire(robotCode, List.of(), Set.of(target.getId()),
                        Duration.ofMinutes(30))) {
                    waitIntents.clear(robotCode, kernelTaskId);
                    return NavigationLease.single(target.getId());
                }
                waitIntents.record(robotCode, kernelTaskId, List.of(), Set.of(target.getId()));
                continue;
            }
            var attachment = graph.attach(resolveFloorId(state, state.goal()), latestRobotX(robotCode), latestRobotY(robotCode),
                    com.zioneer.robotscheduler.common.scheduler.SchedulerConstants.MAX_MERGE_WAYPOINT_DISTANCE,
                    com.zioneer.robotscheduler.common.scheduler.SchedulerConstants.MAX_MERGE_LANE_DISTANCE);
            String fromWaypoint = attachment.waypointId();
            if (fromWaypoint == null && attachment.segmentId() != null) {
                // 路段中间（距点超阈值、距段在阈值内）：取该段离机器人较近端点作为规划起点
                fromWaypoint = graph.segmentEdge(resolveFloorId(state, state.goal()), attachment.segmentId())
                        .map(edge -> nearerSegmentEndpoint(state, robotCode, edge))
                        .orElse(null);
            }
            if (fromWaypoint == null) {
                // 吸附失败（机器人离图/位置丢失）：不降级为单点租约——单段直发远目标
                // 既无交通占用（对向不避让）也可能撞机器人导航栈单次目标过远限制。
                // 返回 null 由外层等待重试（回到图上自动恢复），超时以精准原因失败
                waitIntents.clear(robotCode, kernelTaskId);
                return null;
            }
            var route = graph.plan(resolveFloorId(state, state.goal()), fromWaypoint, target.getId(), assignProperties.getNavigationMaxSpeedMps(),
                    // 规划约束排除自身占用：机器人驻留在自家门口路段时，不能把直连边封死（绕路 bug 修复）
                    trafficRegistry.occupiedSegmentsExcluding(robotCode),
                    // 封锁点含静止机器人驻留点（场景：死端必经点被堵 → 无路可达 → 持续等待其离开）
                    trafficRegistry.occupiedWaypointsExcluding(robotCode));
            if (route.isEmpty()) {
                // 仅用于诊断：忽略动态占用求一条静态候选路线，绝不下发或申请该路线。
                var candidate = graph.plan(resolveFloorId(state, state.goal()), fromWaypoint, target.getId(),
                        assignProperties.getNavigationMaxSpeedMps(), Set.of(), Set.of());
                if (candidate.isEmpty()) {
                    state.staticRouteUnreachable = true;
                    waitIntents.clear(robotCode, kernelTaskId);
                    return null;
                }
                waitIntents.record(robotCode, kernelTaskId, candidate.get().segments().stream()
                        .map(NavigationRouteSegmentIdMapper::id).toList(), Set.copyOf(candidate.get().waypointIds()));
                continue;
            }
            List<String> segmentIds = route.get().segments().stream()
                    .map(NavigationRouteSegmentIdMapper::id)
                    .toList();
            // 途经点全量持有（不只目标点）：路线租约必须覆盖全部途经点——
            // 否则其他机器人可以把"我的途经点"当目标申请成功，两车对同一交点汇合相撞；
            // 身后点随行进 releaseBehind 渐进释放，不会过度锁路
            if (trafficRegistry.tryAcquire(robotCode, segmentIds,
                    new java.util.HashSet<>(route.get().waypointIds()),
                    Duration.ofMinutes(30))) {
                waitIntents.clear(robotCode, kernelTaskId);
                return NavigationLease.ofRoute(route.get().waypointIds(), route.get().segments());
            }
            // 路线点和段都可能被占，检测时重新解析实际占用者。
            waitIntents.record(robotCode, kernelTaskId, segmentIds, Set.copyOf(route.get().waypointIds()));
            return null;
        }
        return null;
    }

    /** 段两端点中离机器人较近者（路段中间起步的规划起点）。 */
    private String nearerSegmentEndpoint(KernelTaskState state, String robotCode,
            com.zioneer.robotscheduler.core.routing.NavigationGraph.SegmentEdge edge) {
        var graph = boundGraph(state).orElse(null);
        if (graph == null) {
            return edge.fromWaypointId();
        }
        String floorId = resolveFloorId(state, state.goal());
        var from = graph.waypoint(floorId, edge.fromWaypointId()).orElse(null);
        var to = graph.waypoint(floorId, edge.toWaypointId()).orElse(null);
        if (from == null || to == null) {
            return edge.fromWaypointId();
        }
        double x = latestRobotX(robotCode);
        double y = latestRobotY(robotCode);
        return Math.hypot(from.getX() - x, from.getY() - y) <= Math.hypot(to.getX() - x, to.getY() - y)
                ? edge.fromWaypointId() : edge.toWaypointId();
    }

    private java.util.Optional<NavigationGraph> boundGraph(KernelTaskState state) {
        return state.mapBinding.snapshot().map(MapSnapshot::graph);
    }

    private WaypointMetadata resolveWaypoint(KernelTaskState state, TaskGoal goal) {
        var graph = boundGraph(state).orElse(null);
        if (graph == null) {
            return null;
        }
        String floorId = resolveFloorId(state, goal);
        return graph.waypointByIdOrName(floorId, goal.goalWaypointId()).orElse(null);
    }

    private String resolveFloorId(KernelTaskState state, TaskGoal goal) {
        if (goal != null && goal.floorId() != null && !goal.floorId().isBlank()) {
            return goal.floorId();
        }
        return state.mapBinding.snapshot().map(MapSnapshot::defaultFloorId).orElse(null);
    }

    private Map<String, Object> buildDescription(String kernelTaskId, TaskGoal goal, String navTargetPoint) {
        return buildDescription(kernelTaskId, goal, navTargetPoint, true);
    }

    /**
     * @param includeTemplateType 是否携带 task_template_type 标记：充电任务的导航段一律不带——
     *                            本体对充电模板任务会自充且行为不稳定（收到即充/到点才充，均不校验
     *                            是否在充电位上）；充电统一"纯导航到位 + start-activity 显式充电"
     */
    private Map<String, Object> buildDescription(String kernelTaskId, TaskGoal goal, String navTargetPoint,
                                                 boolean includeTemplateType) {
        Map<String, Object> description = new HashMap<>(goal.parameters() == null
                ? Map.of() : goal.parameters());
        description.put("rmf_task_id", kernelTaskId);
        // 新版架构：移动命令剥离模板 code（真机对 description.task_template_code 强校验，
        // 模板未下发会拒绝 navigate）；模板上下文由上层 start-activity 自带，与移动无关
        description.remove("task_template_code");
        if (includeTemplateType) {
            description.put("task_template_type", goal.category());
        }
        // RMS 契约：navigate 校验 description.nav_target_point（分段时为当前段目标点位名）
        if (navTargetPoint != null) {
            description.put("nav_target_point", navTargetPoint);
        } else if (goal.requiresNavigation()) {
            description.put("nav_target_point", goal.goalWaypointId());
        }
        return description;
    }

    long actionTimeoutSeconds(TaskGoal goal) {
        Object configured = goal.parameters() == null ? null : goal.parameters().get("action_timeout_seconds");
        if (configured instanceof Number number) {
            return number.longValue();
        }
        // 充电动作默认放宽到 1h（充满回执慢，生产任务流不带超时参数，300s 必超时）
        if (TaskCategories.CHARGE.equals(goal.category())) {
            return 3600;
        }
        return actionTimeoutDefaultSeconds;
    }

    /**
     * 回执后核验实际位置，旧帧及无效帧不通过；超时则返回偏差，由调用方失败并保留占用。
     */
    private double confirmArrivalDeviation(String robotCode, WaypointMetadata waypoint,
                                           String kernelTaskId, String waypointId,
                                           long commandIssuedAt, KernelTaskState state) {
        return confirmArrivalDeviation(robotCode, waypoint, kernelTaskId, waypointId,
                commandIssuedAt, state, ARRIVAL_TOLERANCE_METERS);
    }

    private double confirmArrivalDeviation(String robotCode, WaypointMetadata waypoint,
                                           String kernelTaskId, String waypointId,
                                           long commandIssuedAt, KernelTaskState state, double tolerance) {
        double deviation = Double.MAX_VALUE;
        int attempts = Math.max(1, arrivalConfirmAttempts);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (attempt > 1) {
                try {
                    Thread.sleep(Math.max(0, arrivalConfirmIntervalMillis));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (state.isCanceled()) return Double.POSITIVE_INFINITY;
            deviation = arrivalDeviation(robotCode, waypoint, commandIssuedAt);
            if (withinTolerance(deviation, tolerance)) {
                if (attempt > 1) {
                    log.info("到达位置复核通过: task={} robot={} waypoint={} 首次偏差超阈，第 {} 次采样 {}m（状态帧滞后回执）",
                            kernelTaskId, robotCode, waypointId, attempt, String.format("%.1f", deviation));
                }
                return deviation;
            }
        }
        return deviation;
    }

    /** 仅消除坐标减法的浮点舍入误差（纳米级），不放宽业务精度。 */
    private boolean withinTolerance(double deviation, double tolerance) {
        return Double.isFinite(deviation) && deviation <= tolerance + 1e-9;
    }

    @SuppressWarnings("unused")
    private boolean withinArrivalTolerance(double deviation) {
        return withinTolerance(deviation, ARRIVAL_TOLERANCE_METERS);
    }

    /** 只能用本次指令下发后接收到的在线有效位置确认到点；缺失位置不得默认为原点。 */
    private double arrivalDeviation(String robotCode, WaypointMetadata waypoint, long commandIssuedAt) {
        RobotSnapshot snapshot = fleetStateMirror.list().stream()
                .filter(robot -> robotCode.equals(robot.getRobotCode())).findFirst().orElse(null);
        if (!validArrivalSnapshot(snapshot, commandIssuedAt)) return Double.POSITIVE_INFINITY;
        return Math.hypot(snapshot.getX() - waypoint.getX(), snapshot.getY() - waypoint.getY());
    }

    private boolean validArrivalSnapshot(RobotSnapshot snapshot, long commandIssuedAt) {
        if (snapshot == null) return false;
        long age = System.currentTimeMillis() - snapshot.getUpdatedAtMillis();
        return snapshot.isOnline() && snapshot.isPositionValid() && !Boolean.TRUE.equals(snapshot.getRmsOffline())
                && snapshot.getUpdatedAtMillis() >= commandIssuedAt
                && age >= 0 && age <= ARRIVAL_MAX_STATE_AGE_MILLIS
                && Double.isFinite(snapshot.getX()) && Double.isFinite(snapshot.getY());
    }

    private double latestRobotX(String robotCode) {
        return fleetStateMirror.list().stream()
                .filter(robot -> robotCode.equals(robot.getRobotCode()))
                .findFirst().map(RobotSnapshot::getX).orElse(0.0);
    }

    private double latestRobotY(String robotCode) {
        return fleetStateMirror.list().stream()
                .filter(robot -> robotCode.equals(robot.getRobotCode()))
                .findFirst().map(RobotSnapshot::getY).orElse(0.0);
    }

    /** 最新事件会覆盖明细，分配评分与规划路线必须随进度一起携带。 */
    private Map<String, Object> withAssignmentTrace(KernelTaskState state, Map<String, Object> detail) {
        List<Map<String, Object>> trace = state == null ? null : state.assignmentTrace();
        Map<String, Object> merged = new HashMap<>(detail == null ? Map.of() : detail);
        if (trace != null) merged.put("assignment_trace", trace);
        if (state != null && state.routeWaypointIds() != null && !state.routeWaypointIds().isEmpty()) {
            merged.put("route_waypoints", state.routeWaypointIds());
        }
        return merged;
    }

    /** 终态事件明细：分配评分 + 规划路线审计（detail.route_waypoints，随任务状态落库）。 */
    private Map<String, Object> terminalDetail(KernelTaskState state, Map<String, Object> detail) {
        Map<String, Object> merged = new HashMap<>(withAssignmentTrace(state, detail));
        if (state != null) {
            if (trafficRegistry.isAwaitingStop(state.robotCode())) merged.put("traffic_retained", true);
            state.mapBinding.snapshot().ifPresent(snapshot -> merged.put("map_binding", Map.of(
                    "map_name", snapshot.mapName(), "version", snapshot.version(),
                    "generation", snapshot.generation())));
        }
        List<String> route = state == null ? null : state.routeWaypointIds();
        if (route != null && !route.isEmpty()) {
            merged.put("route_waypoints", route);
        }
        return merged;
    }

    private void publishState(String kernelTaskId, String status, TaskRecord record, String robotCode,
                               TaskGoal goal, Map<String, Object> cancellation) {
        publishRaw(kernelTaskId, record, robotCode, goal, status, cancellation, null);
    }

    private void publishTerminal(String kernelTaskId, KernelTaskState state, String status, String message) {
        publishTerminal(kernelTaskId, state, status, message, Map.of());
    }

    private void publishTerminal(String kernelTaskId, KernelTaskState state, String status, String message,
                                   Map<String, Object> extraDetail) {
        retireTask(kernelTaskId);
        routeRegistry.clear(state.robotCode());
        publishRaw(kernelTaskId, null, state.robotCode(), state.goal(), status, null, message,
                terminalDetail(state, extraDetail));
        log.info("任务终态: task={} status={} message={}", kernelTaskId, status, message);
    }

    private void publishRaw(String kernelTaskId, TaskRecord record, String robotCode, TaskGoal goal,
                            String status, Map<String, Object> cancellation, String message) {
        publishRaw(kernelTaskId, record, robotCode, goal, status, cancellation, message, Map.of());
    }

    private void publishRaw(String kernelTaskId, TaskRecord record, String robotCode, TaskGoal goal,
                            String status, Map<String, Object> cancellation, String message,
                            Map<String, Object> extraDetail) {
        var fleet = fleetStateMirror.list().stream()
                .filter(robot -> robotCode != null && robotCode.equals(robot.getRobotCode()))
                .findFirst().map(RobotSnapshot::getFleetName).orElse(null);
        Map<String, Object> detail = new HashMap<>(goal == null ? Map.of() : goal.parameters());
        if (extraDetail != null && !extraDetail.isEmpty()) {
            detail.putAll(extraDetail);
        }
        TaskStateEvent event = TaskStateEvent.withMessage(kernelTaskId, status,
                goal == null ? null : goal.category(), detail, cancellation, fleet, robotCode,
                record == null ? null : record.getSchedulerRequestId(), message,
                System.currentTimeMillis());
        eventBus.publishTaskState(event);
    }

    /**
     * 停靠导航：质检动作完成后让出作业点，导航至停靠等待点（parameters.end_point）。
     *
     * @return 成功到达返回停靠点 id；失败返回 null（不阻断任务完成）
     */
    private String navigateToStandby(String kernelTaskId, KernelTaskState state, String robotCode,
                                     TaskGoal goal) {
        try {
            WaypointMetadata standbyTarget = resolveWaypoint(state,
                    new TaskGoal(goal.category(), goal.floorId(), goal.standbyWaypointId(),
                            null, null, null, goal.parameters()));
            if (standbyTarget == null) {
                log.warn("停靠点不存在，跳过停靠: task={} standby={}", kernelTaskId, goal.standbyWaypointId());
                return null;
            }
            NavigationLease lease = acquireTraffic(kernelTaskId, state, robotCode, standbyTarget);
            if (lease == null) {
                log.warn("停靠点交通占用超时，原地待命: task={} standby={}", kernelTaskId,
                        goal.standbyWaypointId());
                return null;
            }
            // 停靠段路线覆盖主路线（可视化跟随最后一段导航）
            routeRegistry.register(robotCode, kernelTaskId, resolveFloorId(state, goal),
                        lease.routeWaypointIds(), boundGraph(state).orElse(null));
            boolean standbyArrived = false;
            try {
                standbyArrived = navigateBySegments(kernelTaskId, state, robotCode, goal, lease, standbyTarget);
                return standbyArrived ? standbyTarget.getId() : null;
            } finally {
                // 同主流程：到达停靠点即原子登记驻留（停靠是最密集的并发场景——
                // 多任务同秒选同一停靠点，幽灵窗口下必然叠车）
                if (standbyArrived) {
                    trafficRegistry.releaseAllAndDwell(robotCode, standbyTarget.getId());
                } else {
                    if (state.navigationInFlight) retainAndStop(robotCode, "停靠导航未确认完成");
                    trafficRegistry.releaseAll(robotCode);
                }
                routeRegistry.clear(robotCode);
            }
        } catch (Exception e) {
            log.warn("停靠导航异常，原地待命: task={} error={}", kernelTaskId, e.getMessage());
            return null;
        }
    }

    private String safeDetail(String detail) {
        return detail == null || detail.isBlank() ? "未知原因" : detail;
    }

    private String generateKernelTaskId(TaskRecord record) {
        String category = record.getCategory() == null ? "task" : record.getCategory();
        return category + "." + Long.toString(Math.abs(UUID.randomUUID().getMostSignificantBits()), 36);
    }

    /** 导航占用信息：携带规划路径（途经点+段信息）供分段执行。 */
    private record NavigationLease(List<String> routeWaypointIds,
                                   List<com.zioneer.robotscheduler.core.routing.NavigationRoute.Segment> segments) {

        static NavigationLease single(String targetWaypointId) {
            return new NavigationLease(List.of(targetWaypointId), List.of());
        }

        static NavigationLease ofRoute(List<String> waypointIds,
                                       List<com.zioneer.robotscheduler.core.routing.NavigationRoute.Segment> segments) {
            return new NavigationLease(waypointIds, segments);
        }
    }

    /** 段 id 映射辅助。 */
    private static final class NavigationRouteSegmentIdMapper {

        private static String id(com.zioneer.robotscheduler.core.routing.NavigationRoute.Segment segment) {
            return segment.id();
        }
    }

    /** 内核任务执行状态。 */
    private static final class KernelTaskState {

        private final String schedulerRequestId;
        private final MapManager.MapBinding mapBinding;
        private boolean executionActive;
        private boolean retired;
        private volatile TaskGoal goal;
        private volatile String robotCode;
        private volatile ExecutionPhase phase = ExecutionPhase.QUEUED;
        private volatile boolean canceled;
        /** 发出导航后直到确认到达前，异常退出不能释放道路预约。 */
        private volatile boolean navigationInFlight;
        /** 静态图无路时直接报告配置/拓扑问题，不伪装成占用超时。 */
        private volatile boolean staticRouteUnreachable;
        /** 自动分配评分明细（wire JSON 列表，underway 事件携带；指定机器人时为 null）。 */
        private volatile List<Map<String, Object>> assignmentTrace;
        /** 规划路线途经点（租约获取时登记；终态事件审计携带）。 */
        private volatile List<String> routeWaypointIds;

        private KernelTaskState(String schedulerRequestId, String robotCode, TaskGoal goal,
                                ExecutionPhase phase, MapManager.MapBinding mapBinding) {
            this.schedulerRequestId = schedulerRequestId;
            this.mapBinding = mapBinding;
            this.robotCode = robotCode;
            this.goal = goal;
            this.phase = phase;
        }

        synchronized boolean beginExecution() {
            if (executionActive || retired || canceled) {
                return false;
            }
            executionActive = true;
            return true;
        }

        synchronized void retire() {
            retired = true;
            if (!executionActive) {
                mapBinding.close();
            }
        }

        synchronized void finishExecution() {
            executionActive = false;
            mapBinding.close();
        }

        String schedulerRequestId() {
            return schedulerRequestId;
        }

        String robotCode() {
            return robotCode;
        }

        void assignRobot(String robot) {
            this.robotCode = robot;
        }

        TaskGoal goal() {
            return goal;
        }

        /** 目标更新（充电位被占自动改派场景）。 */
        void setGoal(TaskGoal goal) {
            this.goal = goal;
        }

        ExecutionPhase phase() {
            return phase;
        }

        List<Map<String, Object>> assignmentTrace() {
            return assignmentTrace;
        }

        void setAssignmentTrace(List<Map<String, Object>> trace) {
            this.assignmentTrace = trace;
        }

        List<String> routeWaypointIds() {
            return routeWaypointIds;
        }

        void setRouteWaypointIds(List<String> routeWaypointIds) {
            this.routeWaypointIds = routeWaypointIds;
        }

        boolean isCanceled() {
            return canceled;
        }

        void markCanceled() {
            this.canceled = true;
        }

        void markRunning() {
            this.phase = ExecutionPhase.RUNNING;
        }
    }

    /** 等待自动分配的任务。 */
    private record PendingAssignment(String kernelTaskId, TaskRecord record, TaskGoal goal,
                                     String fleetName, String robotGroup, long deadlineMillis) {
    }

    private enum ExecutionPhase {
        QUEUED, RUNNING, DONE
    }
}
