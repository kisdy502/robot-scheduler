package com.zioneer.robotscheduler.core.fleet;

import com.zioneer.robotscheduler.common.scheduler.SchedulerConstants;
import com.zioneer.robotscheduler.common.scheduler.task.TaskStatus;
import com.zioneer.robotscheduler.core.charger.ChargerInfo;
import com.zioneer.robotscheduler.core.map.MapManager;
import com.zioneer.robotscheduler.core.routing.GraphAttachment;
import com.zioneer.robotscheduler.core.task.TaskStateEvent;
import com.zioneer.robotscheduler.core.task.TaskStateMirror;
import com.zioneer.robotscheduler.core.traffic.TrafficRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 车队管理器：RMS 状态/清单轮询 → 机器人镜像实时驱动（替代旧栈 poller + availability supervisor + synchronizer）。
 *
 * <p>职责：
 * 1. 状态帧融合（坐标/电量/楼层映射/mode 推导/可派发判定）；
 * 2. stale 超时下线（15s 无有效帧 → offline + 保留占用，恢复后确认停止再转为驻留）；
 * 3. decommission 处理（停用标记/清单消失 → 下线）；
 * 4. 离图检测（位置吸附失败 → 不可派发 + 30s 限频告警，回到图上自动恢复）；
 * 5. 外部任务保底（位置移动检测：上位机直发任务时调度侧判 busy，防止派发冲突）；
 * 6. 充电桩基于位置自动释放（机器人离开充电位 > 阈值即释放，不依赖任务终态）。</p>
 */
@Slf4j
@Component
public class FleetManager {

    /** 帧间位移超过此值（米）视为移动中（1Hz 轮询下 0.3m/s 起步）。 */
    static final double MOVING_DELTA_METERS = 0.3;

    /** 连续静止帧数达到此值后解除移动标记。 */
    static final int STATIONARY_FRAMES_TO_CLEAR = 3;

    /** 停止确认：连续 3 帧位置在 5cm、朝向在 0.1rad 内，避免复用普通移动检测的宽阈值。 */
    static final double STOP_POSITION_TOLERANCE_METERS = 0.05;
    static final double STOP_YAW_TOLERANCE_RADIANS = 0.1;
    static final long STOP_RETRY_INTERVAL_MILLIS = 5000;
    private final Map<String, StopObservation> stopObservations = new ConcurrentHashMap<>();
    private final Map<String, Long> nextStopRetryAt = new ConcurrentHashMap<>();

    private record StopObservation(double x, double y, double yaw, int frames) { }

    /** 机器人离开充电位超过此距离（米）自动释放充电桩。 */
    static final double CHARGER_RELEASE_DISTANCE_METERS = 2.0;

    private final RobotStateSource stateSource;
    private final FleetStateMirror fleetStateMirror;
    private final TaskStateMirror taskStateMirror;
    private final MapManager mapManager;
    private final TrafficRegistry trafficRegistry;
    private final com.zioneer.robotscheduler.core.traffic.ActiveRouteRegistry routeRegistry;
    private final RobotEventMonitor eventMonitor;
    private final FleetPollingProperties properties;
    private final RobotCommandExecutor commandExecutor;
    private final com.zioneer.robotscheduler.core.charger.ChargerPoolPort chargerPool;

    /** 离图告警限频：robotCode → 上次告警时间戳。 */
    private final Map<String, Long> offGraphWarnAt = new ConcurrentHashMap<>();

    /** 移动检测：robotCode → 上帧坐标 {x, y}。 */
    private final Map<String, double[]> lastPositions = new ConcurrentHashMap<>();

    /** 移动检测：robotCode → 连续静止帧计数。 */
    private final Map<String, Integer> stationaryFrames = new ConcurrentHashMap<>();

    /** 移动检测：robotCode → 当前是否移动中（外部任务保底）。 */
    private final Map<String, Boolean> movingFlags = new ConcurrentHashMap<>();

    public FleetManager(RobotStateSource stateSource, FleetStateMirror fleetStateMirror,
                        TaskStateMirror taskStateMirror, MapManager mapManager,
                        TrafficRegistry trafficRegistry,
                        com.zioneer.robotscheduler.core.traffic.ActiveRouteRegistry routeRegistry,
                        RobotEventMonitor eventMonitor,
                        FleetPollingProperties properties,
                        RobotCommandExecutor commandExecutor,
                        com.zioneer.robotscheduler.core.charger.ChargerPoolPort chargerPool) {
        this.stateSource = stateSource;
        this.fleetStateMirror = fleetStateMirror;
        this.taskStateMirror = taskStateMirror;
        this.mapManager = mapManager;
        this.trafficRegistry = trafficRegistry;
        this.routeRegistry = routeRegistry;
        this.eventMonitor = eventMonitor;
        this.properties = properties;
        this.commandExecutor = commandExecutor;
        this.chargerPool = chargerPool;
    }

    /**
     * 状态轮询（默认 1Hz）：失败时保持上一帧镜像并告警（对齐旧栈 stale 语义：由 sweep 判定过期）。
     */
    @Scheduled(fixedDelayString = "${robot.scheduler.fleet.state-interval-millis:1000}")
    public void pollStates() {
        List<RobotSnapshot> frames;
        try {
            frames = stateSource.fetchStates();
        } catch (Exception e) {
            log.warn("RMS 状态轮询失败，保持上一帧镜像: {}", e.getMessage());
            return;
        }
        if (!frames.isEmpty()) {
            onStateTick(frames);
        }
    }

    /**
     * 清单轮询（默认 0.1Hz）：机器人上下线发现 + 静态信息合并。
     */
    @Scheduled(fixedDelayString = "${robot.scheduler.fleet.inventory-interval-millis:10000}")
    public void pollInventory() {
        List<RobotSnapshot> inventory;
        try {
            inventory = stateSource.fetchInventory();
        } catch (Exception e) {
            log.warn("RMS 清单轮询失败，跳过本轮: {}", e.getMessage());
            return;
        }
        onInventorySync(inventory);
    }

    /**
     * stale 扫描：超过阈值无有效帧的机器人临时下线并保留交通占用，不能把失联当成道路已腾空。
     */
    @Scheduled(fixedDelay = 5000)
    public void sweepStaleRobots() {
        long staleMillis = (long) (properties.getStaleAfterSeconds() * 1000);
        long now = System.currentTimeMillis();
        for (RobotSnapshot snapshot : fleetStateMirror.list()) {
            if (snapshot.isOnline() && now - snapshot.getUpdatedAtMillis() > staleMillis) {
                String prevMode = snapshot.getMode();
                Double prevBattery = snapshot.getBattery();
                snapshot.setOnline(false);
                snapshot.setDispatchable(false);
                snapshot.setMode("unknown");
                releaseResourcesForOffline(snapshot.getRobotCode());
                log.warn("机器人状态过期临时下线: robot={} staleAfter={}s", snapshot.getRobotCode(),
                        properties.getStaleAfterSeconds());
                fleetStateMirror.upsert(snapshot);
                eventMonitor.onFrame(snapshot.getRobotCode(), true, prevMode, prevBattery,
                        false, snapshot.getMode(), prevBattery);
            }
        }
        releaseChargersOfOfflineRobots();
    }

    /** 失联时保留路线与最后驻留；恢复在线后再核验停止，充电位也暂不释放。 */
    private void releaseResourcesForOffline(String robotCode) {
        boolean first = trafficRegistry.retainUntilStopped(robotCode, "机器人下线，等待恢复后确认停止");
        commandExecutor.abandon(robotCode, "机器人下线，导航结果未知");
        stopObservations.remove(robotCode);
        nextStopRetryAt.remove(robotCode);
        routeRegistry.clear(robotCode);
        if (first) log.warn("机器人下线，保留交通占用: robot={}", robotCode);
    }

    /**
     * 充电桩占用对账：未知占用者的历史残留可释放；已知失联机器人仍保留占用。
     * 已知机器人恢复后依靠实际位置对账，不因离线自动腾出充电位。
     */
    private void releaseChargersOfOfflineRobots() {
        java.util.Set<String> onlineCodes = new java.util.HashSet<>();
        for (RobotSnapshot snapshot : fleetStateMirror.list()) {
            if (snapshot.isOnline()) {
                onlineCodes.add(snapshot.getRobotCode());
            }
        }
        for (ChargerInfo charger : chargerPool.list(null)) {
            String occupier = charger.getOccupiedBy();
            if (occupier != null && !onlineCodes.contains(occupier)
                    && !trafficRegistry.isAwaitingStop(occupier)) {
                chargerPool.release(occupier, charger.getRmfWaypoint(), null);
                log.info("充电桩占用者已离线，自动释放: charger={} robot={}", charger.getChargerId(), occupier);
            }
        }
    }

    /**
     * 状态帧融合：更新镜像（在线、坐标、楼层映射、mode、可派发判定、离图检测）。
     */
    public void onStateTick(List<RobotSnapshot> frames) {
        long now = System.currentTimeMillis();
        for (RobotSnapshot frame : frames) {
            if (frame.getRobotCode() == null || frame.getRobotCode().isBlank()) {
                // 解析异常帧防御：无编码帧丢弃
                continue;
            }
            RobotSnapshot existing = fleetStateMirror.list().stream()
                    .filter(prev -> prev.getRobotCode().equals(frame.getRobotCode()))
                    .findFirst()
                    .orElse(null);
            // 前帧值捕获（mergeFrame 原地修改，先取再合并）：机器人事件检测输入
            Boolean prevOnline = existing == null ? null : existing.isOnline();
            String prevMode = existing == null ? null : existing.getMode();
            Double prevBattery = existing == null ? null : existing.getBattery();
            RobotSnapshot snapshot = existing != null ? mergeFrame(existing, frame)
                    : mergeFrame(frame, frame);
            if (Boolean.TRUE.equals(frame.getRmsOffline())) {
                // 明确离线与 stale 采用同样的保留策略，后续状态恢复再确认停止。
                snapshot.setOnline(false);
                snapshot.setDispatchable(false);
                releaseResourcesForOffline(snapshot.getRobotCode());
            } else {
                snapshot.setOnline(true);
            }
            snapshot.setUpdatedAtMillis(now);
            snapshot.setFloorId(properties.resolveFloorId(frame.getMap()));

            if (isDecommissioned(frame)) {
                decommission(snapshot, "RMS 停用标记");
                eventMonitor.onFrame(snapshot.getRobotCode(), prevOnline, prevMode, prevBattery,
                        snapshot.isOnline(), snapshot.getMode(), snapshot.getBattery());
                continue;
            }
            // 外部任务保底：位置移动检测（上位机直发任务时 RMS 不回报执行状态，调度靠位移自保）
            boolean moving = updateMovingState(snapshot);
            snapshot.setMode(deriveMode(snapshot, moving));
            snapshot.setDispatchable(evaluateDispatchable(snapshot));
            fleetStateMirror.upsert(snapshot);
            // 地图一致性事件（变化检测在 monitor 内部去重）
            eventMonitor.onMapIdentity(snapshot.getRobotCode(), snapshot.getSlamMapCode(),
                    mapManager.activeMetadata()
                            .map(com.zioneer.robotscheduler.core.map.MapMetadata::getMapName)
                            .orElse(null));
            // 机器人事件日志：状态/电量/上下线变化检测（前帧 vs 当前帧）
            eventMonitor.onFrame(snapshot.getRobotCode(), prevOnline, prevMode, prevBattery,
                    snapshot.isOnline(), snapshot.getMode(), snapshot.getBattery());
            // 驻留占用：在线机器人物理位置注册为交通障碍（静止占点/段，移动占物理所在段）。
            // 不看 mode：质检作业中/充电中的机器人导航占用已释放（到达即 releaseAll），
            // 但物理上仍停在作业点/充电位——必须由驻留接管，保证"在线必然占用点或段"不变量
            updateDwellState(snapshot, moving);
            // 充电桩基于位置自动释放：机器人离开充电位即释放（不依赖任务终态）
            releaseChargerIfDeparted(snapshot);
            // 完成回执闭环：当前 pending 命令匹配 → 结束执行 Future
            if (frame.getCompletedCommands() != null && !frame.getCompletedCommands().isEmpty()) {
                commandExecutor.onCompletedCommands(snapshot.getRobotCode(), frame.getCompletedCommands());
            }
        }
    }

    /**
     * 位置移动检测：帧间位移 > 阈值 → 移动中（外部任务占用）；连续静止 N 帧后解除。
     *
     * @return 当前是否移动中
     */
    boolean updateMovingState(RobotSnapshot snapshot) {
        String robotCode = snapshot.getRobotCode();
        double[] last = lastPositions.get(robotCode);
        double[] current = {snapshot.getX(), snapshot.getY()};
        lastPositions.put(robotCode, current);
        if (last == null) {
            return movingFlags.getOrDefault(robotCode, Boolean.FALSE);
        }
        double delta = Math.hypot(current[0] - last[0], current[1] - last[1]);
        if (delta >= MOVING_DELTA_METERS) {
            if (!movingFlags.getOrDefault(robotCode, Boolean.FALSE)) {
                log.info("机器人位置移动中（外部任务保底判定）: robot={} delta={}m", robotCode,
                        String.format("%.2f", delta));
            }
            movingFlags.put(robotCode, Boolean.TRUE);
            stationaryFrames.put(robotCode, 0);
            return true;
        }
        int stationary = stationaryFrames.merge(robotCode, 1, Integer::sum);
        if (stationary >= STATIONARY_FRAMES_TO_CLEAR) {
            movingFlags.put(robotCode, Boolean.FALSE);
            return false;
        }
        return movingFlags.getOrDefault(robotCode, Boolean.FALSE);
    }

    /**
     * 驻留占用：在线机器人的物理位置注册为交通障碍（点或段）。
     * - 静止：吸附点/段（停在点上占点、停在路段中间占段）——作业/充电/停靠阶段驻留接管
     * - 移动中：只登记物理所在路段（不占点，"占点"语义 = 停在点上）——
     *   上位机直发任务/遥控等非调度移动没有路线租约，由驻留段兜底交通可见性（对向避让）
     *
     * 阈值策略（与规划吸附分离）：
     * - 点位阈值 0.5m：机器人基本停在点位圆心才算"占点"（精确阻断）
     * - 路段阈值 1.0m：机器人距路段垂距内算"占段"（物理阻断）
     * - 0.5~3m 之间且在段上 → 占段（优先匹配物理位置，而非逻辑吸附）
     */
    private void updateDwellState(RobotSnapshot snapshot, boolean moving) {
        String robotCode = snapshot.getRobotCode();
        if (trafficRegistry.isAwaitingStop(robotCode)) {
            confirmStoppedFromFrame(snapshot);
            return;
        }
        if (!snapshot.isOnline()) {
            trafficRegistry.updateDwell(robotCode, null, null);
            return;
        }
        var graph = mapManager.activeGraph().orElse(null);
        if (graph == null || !graph.isLoaded()) {
            trafficRegistry.updateDwell(robotCode, null, null);
            return;
        }
        String floorId = snapshot.getFloorId();
        if (floorId == null) {
            trafficRegistry.updateDwell(robotCode, null, null);
            return;
        }
        // 驻留用小阈值：0.5m 点位 / 1.0m 路段（区别于规划吸附的 3.0m / 1.0m）
        GraphAttachment attachment = graph.attach(floorId, snapshot.getX(), snapshot.getY(),
                SchedulerConstants.DWELL_WAYPOINT_THRESHOLD,
                SchedulerConstants.DWELL_SEGMENT_THRESHOLD);
        if (moving) {
            trafficRegistry.updateDwell(robotCode, null, attachment.segmentId());
            syncChargerPoolFromDwell(robotCode, null);
            return;
        }
        trafficRegistry.updateDwell(robotCode, attachment.waypointId(), attachment.segmentId());
        syncChargerPoolFromDwell(robotCode, attachment.waypointId());
    }

    /** 只处理正常状态轮询中的停止确认，不根据定时器或缓存位置解除保留。 */
    private void confirmStoppedFromFrame(RobotSnapshot snapshot) {
        String robot = snapshot.getRobotCode();
        var graph = mapManager.activeGraph().orElse(null);
        if (!snapshot.isOnline() || isMapMismatch(snapshot) || graph == null || snapshot.getFloorId() == null
                || commandExecutor.hasPending(robot) || !Double.isFinite(snapshot.getX())
                || !Double.isFinite(snapshot.getY()) || !Double.isFinite(snapshot.getYaw())) {
            stopObservations.remove(robot);
            return;
        }
        if (!trafficRegistry.isStopAccepted(robot)) {
            long now = System.currentTimeMillis();
            if (now >= nextStopRetryAt.getOrDefault(robot, 0L)) {
                nextStopRetryAt.put(robot, now + STOP_RETRY_INTERVAL_MILLIS);
                if (commandExecutor.stop(robot, 0, null) == RobotCmdResult.SUCCESS) {
                    trafficRegistry.markStopAccepted(robot);
                }
            }
            // 本帧可能在 stop 送达前已采集，只作为停止请求触发，不计入确认帧。
            stopObservations.remove(robot);
            return;
        }
        GraphAttachment attachment = graph.attach(snapshot.getFloorId(), snapshot.getX(), snapshot.getY(),
                SchedulerConstants.DWELL_WAYPOINT_THRESHOLD, SchedulerConstants.DWELL_SEGMENT_THRESHOLD);
        if (!attachment.attached()) {
            stopObservations.remove(robot);
            return;
        }
        StopObservation previous = stopObservations.get(robot);
        double yawDelta = previous == null ? 0 : Math.atan2(Math.sin(snapshot.getYaw() - previous.yaw()),
                Math.cos(snapshot.getYaw() - previous.yaw()));
        boolean stable = previous != null
                && Math.hypot(snapshot.getX() - previous.x(), snapshot.getY() - previous.y())
                        <= STOP_POSITION_TOLERANCE_METERS
                && Math.abs(yawDelta) <= STOP_YAW_TOLERANCE_RADIANS;
        StopObservation observation = stable
                ? new StopObservation(previous.x(), previous.y(), previous.yaw(), previous.frames() + 1)
                : new StopObservation(snapshot.getX(), snapshot.getY(), snapshot.getYaw(), 1);
        stopObservations.put(robot, observation);
        if (observation.frames() >= STATIONARY_FRAMES_TO_CLEAR
                && trafficRegistry.confirmStopped(robot, attachment.waypointId(), attachment.segmentId())) {
            stopObservations.remove(robot);
            nextStopRetryAt.remove(robot);
            syncChargerPoolFromDwell(robot, attachment.waypointId());
            snapshot.setDispatchable(evaluateDispatchable(snapshot));
            fleetStateMirror.upsert(snapshot);
            log.info("机器人停止已确认，剩余预约转为实际驻留: robot={} waypoint={} segment={}",
                    robot, attachment.waypointId(), attachment.segmentId());
        }
    }

    /**
     * 驻留真相同步充电桩池：机器人停在充电位 → 桩占用（Chargers 页与选桩分配不再与
     * 交通驻留矛盾）；离开充电位 → 若占用者是本机器人则释放。
     */
    private void syncChargerPoolFromDwell(String robotCode, String dwellWaypointId) {
        try {
            boolean onCharger = dwellWaypointId != null && isChargeWaypoint(dwellWaypointId);
            if (onCharger) {
                chargerPool.occupy(robotCode, dwellWaypointId);
            } else {
                // 离开充电位（回到路段/别的点）：释放本机器人占的桩（若有）
                List<ChargerInfo> occupied = chargerPool.list(null).stream()
                        .filter(c -> robotCode.equals(c.getOccupiedBy()))
                        .toList();
                for (ChargerInfo charger : occupied) {
                    chargerPool.release(robotCode, charger.getRmfWaypoint(), null);
                }
            }
        } catch (Exception e) {
            log.debug("充电桩池驻留同步失败（不阻断）: robot={} error={}", robotCode, e.getMessage());
        }
    }

    private boolean isChargeWaypoint(String waypointId) {
        return mapManager.activeGraph()
                .filter(graph -> graph.isLoaded())
                .flatMap(graph -> graph.waypoint(
                        mapManager.activeFloorId().orElse(null), waypointId))
                .map(waypoint -> waypoint.getType()
                        == com.zioneer.robotscheduler.core.map.WaypointType.CHARGE)
                .orElse(false);
    }

    /**
     * 充电桩位置释放：机器人占用的充电桩，其桩位坐标与机器人当前位置距离超过阈值 → 自动释放。
     */
    private void releaseChargerIfDeparted(RobotSnapshot snapshot) {
        if (!snapshot.isOnline() || trafficRegistry.isAwaitingStop(snapshot.getRobotCode())) return;
        String robotCode = snapshot.getRobotCode();
        String floorId = snapshot.getFloorId();
        if (floorId == null) {
            return;
        }
        var graph = mapManager.activeGraph().orElse(null);
        if (graph == null || !graph.isLoaded()) {
            return;
        }
        for (var charger : chargerPool.list(null)) {
            if (!robotCode.equals(charger.getOccupiedBy())) {
                continue;
            }
            var waypoint = graph.waypoint(floorId, charger.getRmfWaypoint()).orElse(null);
            if (waypoint == null) {
                continue;
            }
            double distance = Math.hypot(waypoint.getX() - snapshot.getX(),
                    waypoint.getY() - snapshot.getY());
            if (distance > CHARGER_RELEASE_DISTANCE_METERS) {
                chargerPool.release(robotCode, charger.getRmfWaypoint(), null);
                log.info("机器人已离开充电位，自动释放充电桩: robot={} charger={} distance={}m",
                        robotCode, charger.getChargerId(), String.format("%.2f", distance));
            }
        }
    }

    /**
     * 清单同步：合并静态信息；新机器人上线发现（写入镜像，等状态帧刷新在线状态）；
     * 清单中消失的机器人下线（保留镜像记录以便观测）。
     */
    public void onInventorySync(List<RobotSnapshot> inventory) {
        var knownCodes = inventory.stream().map(RobotSnapshot::getRobotCode).toList();
        for (RobotSnapshot snapshot : fleetStateMirror.list()) {
            if (!knownCodes.contains(snapshot.getRobotCode())) {
                // 清单消失：临时下线（保留记录，帧恢复自动上线）
                if (snapshot.isOnline()) {
                    String prevMode = snapshot.getMode();
                    Double prevBattery = snapshot.getBattery();
                    snapshot.setOnline(false);
                    snapshot.setDispatchable(false);
                    releaseResourcesForOffline(snapshot.getRobotCode());
                    log.warn("机器人从清单消失，临时下线: robot={}", snapshot.getRobotCode());
                    fleetStateMirror.upsert(snapshot);
                    eventMonitor.onFrame(snapshot.getRobotCode(), true, prevMode, prevBattery,
                            false, snapshot.getMode(), prevBattery);
                }
            }
        }
        for (RobotSnapshot item : inventory) {
            if (item.getRobotCode() == null || item.getRobotCode().isBlank()) {
                continue;
            }
            RobotSnapshot snapshot = fleetStateMirror.list().stream()
                    .filter(existing -> existing.getRobotCode().equals(item.getRobotCode()))
                    .findFirst()
                    .orElseGet(() -> {
                        // 新机器人上线发现（对应旧栈 ensure_robot）
                        RobotSnapshot created = new RobotSnapshot();
                        created.setRobotCode(item.getRobotCode());
                        created.setFleetName(item.getFleetName());
                        log.info("清单发现新机器人: robot={}", item.getRobotCode());
                        return created;
                    });
            if (item.getRobotName() != null) {
                snapshot.setRobotName(item.getRobotName());
            }
            if (item.getHomeWaypoint() != null) {
                snapshot.setHomeWaypoint(item.getHomeWaypoint());
            }
            if (item.getCompatibleChargers() != null && !item.getCompatibleChargers().isEmpty()) {
                snapshot.setCompatibleChargers(item.getCompatibleChargers());
            }
            if (Boolean.TRUE.equals(item.getDecommissioned()) && snapshot.isOnline()) {
                decommission(snapshot, "清单停用标记");
                continue;
            }
            fleetStateMirror.upsert(snapshot);
        }
    }

    // ==================== 内部逻辑 ====================

    private String deriveMode(RobotSnapshot snapshot, boolean movingExternally) {
        // mode 推导优先级（确定性规则，替代旧版无序遍历首个命中——
        // 机器人同时挂在途普通任务+排队充电任务时旧版随机摇摆，充电转变事件丢失）：
        // 1. 本体在充（battery.is_charging）→ charging（真实充电信号，mock/真机统一）
        // 2. 有非充电任务在途 → running
        // 3. 仅有充电任务在途 → charging（导航去桩也算充电任务执行中）
        // 4. 无任务但位移检测移动中（上位机直发）→ running
        // 5. 静止无任务 → idle
        if (Boolean.TRUE.equals(snapshot.getBodyCharging())) {
            return "charging";
        }
        boolean chargeTaskOnly = false;
        for (TaskStateEvent event : taskStateMirror.list()) {
            if (!snapshot.getRobotCode().equals(event.assignedRobotName())) {
                continue;
            }
            TaskStatus status = TaskStatus.fromRaw(event.status());
            if (status.isTerminal()) {
                continue;
            }
            if (!"charge".equals(event.category())) {
                return "running";
            }
            chargeTaskOnly = true;
        }
        if (chargeTaskOnly) {
            return "charging";
        }
        if (movingExternally) {
            return "running";
        }
        return "idle";
    }

    private boolean evaluateDispatchable(RobotSnapshot snapshot) {
        if (trafficRegistry.isAwaitingStop(snapshot.getRobotCode()) || !snapshot.isOnline()) {
            return false;
        }
        String mode = snapshot.getMode();
        if ("error".equals(mode) || "unknown".equals(mode) || "paused".equals(mode)
                || "decommissioned".equals(mode)) {
            return false;
        }
        // 外部任务保底：位置在移动且无调度任务（上位机直发）→ 不可派发，防止冲突
        if (Boolean.TRUE.equals(movingFlags.getOrDefault(snapshot.getRobotCode(), Boolean.FALSE))
                && !hasActiveScheduledTask(snapshot.getRobotCode())) {
            return false;
        }
        // 地图一致性校验（三方地图一致性·调度层）：机器人 SLAM 地图标识 ≠ 调度激活地图 → 禁止派发
        // （防名字对坐标错的隐蔽事故：同一物理世界不同版本地图，点位坐标错位导致乱走乱撞）
        if (isMapMismatch(snapshot)) {
            return false;
        }
        return isOnGraph(snapshot);
    }

    /** 机器人 SLAM 地图与调度激活地图不一致（未上报标识的不判，兼容旧协议）。 */
    private boolean isMapMismatch(RobotSnapshot snapshot) {
        String slamMapCode = snapshot.getSlamMapCode();
        if (slamMapCode == null || slamMapCode.isBlank()) {
            return false;
        }
        String activeMap = mapManager.activeMetadata()
                .map(com.zioneer.robotscheduler.core.map.MapMetadata::getMapName)
                .orElse(null);
        return activeMap != null && !activeMap.equals(slamMapCode);
    }

    private boolean hasActiveScheduledTask(String robotCode) {
        for (TaskStateEvent event : taskStateMirror.list()) {
            if (robotCode.equals(event.assignedRobotName())
                    && !TaskStatus.fromRaw(event.status()).isTerminal()) {
                return true;
            }
        }
        return false;
    }

    private boolean isOnGraph(RobotSnapshot snapshot) {
        var graph = mapManager.activeGraph().orElse(null);
        if (graph == null || !graph.isLoaded()) {
            // 地图未加载：不做离图判定（无图模式，仅告警一次）
            return true;
        }
        String floorId = snapshot.getFloorId();
        String activeFloor = mapManager.activeFloorId().orElse(null);
        if (floorId == null || !floorId.equals(activeFloor)) {
            String reason = "楼层不匹配: robotFloor=" + floorId + " activeFloor=" + activeFloor;
            warnOffGraph(snapshot, reason);
            eventMonitor.onGraphState(snapshot.getRobotCode(), false, reason);
            return false;
        }
        GraphAttachment attachment = graph.attach(floorId, snapshot.getX(), snapshot.getY(),
                SchedulerConstants.MAX_MERGE_WAYPOINT_DISTANCE, SchedulerConstants.MAX_MERGE_LANE_DISTANCE);
        if (!attachment.attached()) {
            String reason = String.format("位置吸附失败: (%.2f, %.2f)", snapshot.getX(), snapshot.getY());
            warnOffGraph(snapshot, reason);
            eventMonitor.onGraphState(snapshot.getRobotCode(), false, reason);
            return false;
        }
        offGraphWarnAt.remove(snapshot.getRobotCode());
        eventMonitor.onGraphState(snapshot.getRobotCode(), true, null);
        return true;
    }

    private void warnOffGraph(RobotSnapshot snapshot, String reason) {
        long now = System.currentTimeMillis();
        long intervalMillis = (long) (properties.getOffGraphWarningIntervalSeconds() * 1000);
        Long lastWarn = offGraphWarnAt.get(snapshot.getRobotCode());
        if (lastWarn == null || now - lastWarn >= intervalMillis) {
            log.warn("机器人离图，临时不可派发: robot={} floor={} ({})",
                    snapshot.getRobotCode(), snapshot.getFloorId(), reason);
            offGraphWarnAt.put(snapshot.getRobotCode(), now);
        }
    }

    private void decommission(RobotSnapshot snapshot, String reason) {
        snapshot.setOnline(false);
        snapshot.setDispatchable(false);
        snapshot.setMode("decommissioned");
        snapshot.setDecommissioned(true);
        releaseResourcesForOffline(snapshot.getRobotCode());
        log.warn("机器人停用（decommission）: robot={} reason={}", snapshot.getRobotCode(), reason);
        fleetStateMirror.upsert(snapshot);
    }

    private boolean isDecommissioned(RobotSnapshot frame) {
        return Boolean.TRUE.equals(frame.getDecommissioned());
    }

    /** 帧数据合并到已有快照（保留静态信息，坐标/电量取新帧）。 */
    private RobotSnapshot mergeFrame(RobotSnapshot existing, RobotSnapshot frame) {
        existing.setRobotCode(frame.getRobotCode());
        existing.setRobotName(frame.getRobotName() == null ? existing.getRobotName() : frame.getRobotName());
        existing.setFleetName(frame.getFleetName() == null ? existing.getFleetName() : frame.getFleetName());
        existing.setX(frame.getX());
        existing.setY(frame.getY());
        existing.setYaw(frame.getYaw());
        existing.setBattery(frame.getBattery());
        existing.setMap(frame.getMap());
        existing.setCompletedCommands(frame.getCompletedCommands());
        existing.setReplanRequested(frame.isReplanRequested());
        // 管理接口档案字段（地图标识/本体充电标记）：帧携带时刷新（1Hz 新鲜度优于清单 10s）
        if (frame.getSlamMapCode() != null) {
            existing.setSlamMapCode(frame.getSlamMapCode());
        }
        if (frame.getBodyCharging() != null) {
            existing.setBodyCharging(frame.getBodyCharging());
        }
        return existing;
    }
}
