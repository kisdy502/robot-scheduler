package com.zioneer.robotscheduler.core.charger;

import com.zioneer.robotscheduler.core.fleet.FleetStateMirror;
import com.zioneer.robotscheduler.core.fleet.RobotSnapshot;
import com.zioneer.robotscheduler.core.map.MapManager;
import com.zioneer.robotscheduler.core.map.WaypointMetadata;
import com.zioneer.robotscheduler.core.repository.TaskRecord;
import com.zioneer.robotscheduler.core.task.TaskGoal;
import com.zioneer.robotscheduler.core.task.TaskStateMirror;
import com.zioneer.robotscheduler.core.task.TaskExecutionEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 低电量返充监督器（对齐旧栈 consider_retreating_to_charger）：
 * 空闲且电量低于阈值的机器人自动生成充电任务（充电位来自兼容列表或地图 charge 点）。
 */
@Slf4j
@Component
public class LowBatteryChargeSupervisor {

    private final FleetStateMirror fleetStateMirror;
    private final TaskStateMirror taskStateMirror;
    private final ChargerPoolPort chargerPool;
    private final MapManager mapManager;
    private final TaskExecutionEngine executionEngine;
    private final com.zioneer.robotscheduler.core.repository.TaskRecordRepository taskRecordRepository;
    private final ChargeProperties properties;

    /** 已发起返充的机器人（防重复；充电任务终态后可重入）。 */
    private final Map<String, String> retreatInFlight = new ConcurrentHashMap<>();

    public LowBatteryChargeSupervisor(FleetStateMirror fleetStateMirror, TaskStateMirror taskStateMirror,
                                      ChargerPoolPort chargerPool, MapManager mapManager,
                                      TaskExecutionEngine executionEngine,
                                      com.zioneer.robotscheduler.core.repository.TaskRecordRepository taskRecordRepository,
                                      ChargeProperties properties) {
        this.fleetStateMirror = fleetStateMirror;
        this.taskStateMirror = taskStateMirror;
        this.chargerPool = chargerPool;
        this.mapManager = mapManager;
        this.executionEngine = executionEngine;
        this.taskRecordRepository = taskRecordRepository;
        this.properties = properties;
    }

    /**
     * 周期扫描（30s）：低电量空闲机器人发起返充；清理已完成任务的在途标记。
     */
    @Scheduled(fixedDelayString = "${robot.scheduler.charge.scan-interval-millis:30000}")
    public void scan() {
        try (var admissionBinding = mapManager.bindTask()) {
            cleanFinishedRetreats();
            for (RobotSnapshot robot : fleetStateMirror.list()) {
                if (!shouldRetreat(robot)) {
                    continue;
                }
                Optional<ChargerInfo> charger = allocateCharger(robot);
                if (charger.isEmpty()) {
                    log.warn("低电量机器人无可用充电位: robot={} battery={}",
                            robot.getRobotCode(), robot.getBattery());
                    continue;
                }
                submitRetreat(robot, charger.get());
            }
        }
    }

    boolean shouldRetreat(RobotSnapshot robot) {
        if (!robot.isOnline() || !robot.isDispatchable()) {
            return false;
        }
        if (!"idle".equals(robot.getMode())) {
            return false;
        }
        if (retreatInFlight.containsKey(robot.getRobotCode())) {
            return false;
        }
        Double battery = robot.getBattery();
        return battery != null && battery <= properties.getLowBatteryThresholdPercent();
    }

    private void submitRetreat(RobotSnapshot robot, ChargerInfo charger) {
        TaskRecord record = new TaskRecord();
        record.setSchedulerRequestId("retreat-" + robot.getRobotCode() + "-" + System.currentTimeMillis());
        record.setBusinessTaskId(record.getSchedulerRequestId());
        record.setCategory("charge");
        record.setRobotCode(robot.getRobotCode());
        record.setDispatchStatus(com.zioneer.robotscheduler.common.scheduler.task.DispatchStatus.PUBLISHED);
        record.setMessage("低电量自动返充");
        record.setCreatedAtMillis(System.currentTimeMillis());
        record.setUpdatedAtMillis(System.currentTimeMillis());
        // 落库：内部任务同样可查询/可取消（stop-task 按 robot_code 定位依赖记录表）
        taskRecordRepository.save(record);
        Map<String, Object> parameters = new java.util.HashMap<>(Map.of(
                "task_id", record.getBusinessTaskId(),
                "charger_waypoint", charger.getRmfWaypoint(),
                "internal", true));
        // 机器人本体契约：charge 模板需要 destination（充电位坐标对象）
        mapManager.activeGraph().ifPresent(graph ->
                graph.waypointByIdOrName(mapManager.activeFloorId().orElse(null), charger.getRmfWaypoint())
                        .ifPresent(waypoint -> parameters.put("destination", Map.of(
                                "x", waypoint.getX(),
                                "y", waypoint.getY(),
                                "yaw", waypoint.getTheta()))));
        TaskGoal goal = new TaskGoal("charge", null, charger.getRmfWaypoint(), "charge",
                robot.getRobotCode(), null, parameters);
        executionEngine.submit(record, robot.getRobotCode(), goal);
        retreatInFlight.put(robot.getRobotCode(), record.getSchedulerRequestId());
        log.info("低电量自动返充: robot={} battery={} charger={}",
                robot.getRobotCode(), robot.getBattery(), charger.getRmfWaypoint());
    }

    private Optional<ChargerInfo> allocateCharger(RobotSnapshot robot) {
        // 优先兼容充电位列表，其次按充电位池自动分配
        List<String> compatible = robot.getCompatibleChargers();
        var allocated = chargerPool.allocate(robot.getRobotCode(), robot.getFleetName(),
                compatible == null || compatible.isEmpty() ? null : compatible, null);
        if (allocated.isPresent()) {
            return allocated;
        }
        // 兜底：地图上的 charge 点
        return mapManager.activeGraph()
                .map(graph -> graph.waypointsByType(mapManager.activeFloorId().orElse(null),
                        com.zioneer.robotscheduler.core.map.WaypointType.CHARGE))
                .orElse(List.of()).stream()
                .findFirst()
                .map(waypoint -> {
                    ChargerInfo info = new ChargerInfo();
                    info.setChargerId(waypoint.getId());
                    info.setRmfWaypoint(waypoint.getId());
                    info.setStatus(0);
                    info.setEnabled(true);
                    return info;
                });
    }

    private void cleanFinishedRetreats() {
        retreatInFlight.entrySet().removeIf(entry -> {
            boolean finished = taskStateMirror.list().stream()
                    .filter(event -> entry.getValue() != null
                            && entry.getValue().equals(event.schedulerRequestId()))
                    .findFirst()
                    .map(event -> "completed".equals(event.status()) || "failed".equals(event.status())
                            || "canceled".equals(event.status()))
                    .orElse(false);
            if (finished) {
                // 充电任务终态：释放充电桩，允许下次返充或手动分配
                chargerPool.release(entry.getKey(), null, null);
            }
            return finished;
        });
    }

    /**
     * 返充配置（由 SchedulerCoreAutoConfiguration 注册）。
     */
    @lombok.Getter
    @lombok.Setter
    @ConfigurationProperties(prefix = "robot.scheduler.charge")
    public static class ChargeProperties {

        /** 返充电量阈值（百分比，低于即发起）。 */
        private double lowBatteryThresholdPercent = 20.0;

        /** 扫描周期（毫秒）。 */
        private long scanIntervalMillis = 30_000;
    }
}
