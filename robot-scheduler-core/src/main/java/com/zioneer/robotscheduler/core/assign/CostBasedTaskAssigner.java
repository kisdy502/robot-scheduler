package com.zioneer.robotscheduler.core.assign;

import com.zioneer.robotscheduler.common.scheduler.SchedulerConstants;
import com.zioneer.robotscheduler.core.fleet.FleetStateMirror;
import com.zioneer.robotscheduler.core.fleet.RobotSnapshot;
import com.zioneer.robotscheduler.core.map.MapManager;
import com.zioneer.robotscheduler.core.routing.GraphAttachment;
import com.zioneer.robotscheduler.core.task.TaskGoal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 代价分配实现（对齐旧栈 TaskPlanner 的 BatteryAware 代价模型简化版）：
 * 总代价 = 规划 ETA + 低电量惩罚 + 忙碌惩罚，取最小者。
 */
@Slf4j
@Component
public class CostBasedTaskAssigner implements TaskAssigner {

    private final FleetStateMirror fleetStateMirror;
    private final MapManager mapManager;
    private final TaskAssignProperties properties;
    private final ChargingInterruptionPolicy chargingPolicy;

    /** 单构造器（Spring 自动注入；策略对象由 properties 组装，测试可注入自定义参数）。 */
    public CostBasedTaskAssigner(FleetStateMirror fleetStateMirror, MapManager mapManager,
                                 TaskAssignProperties properties) {
        this.fleetStateMirror = fleetStateMirror;
        this.mapManager = mapManager;
        this.properties = properties;
        this.chargingPolicy = new ChargingInterruptionPolicy(properties);
    }

    /**
     * 选择最优机器人（实现 TaskAssigner 端口）：过滤可派+非忙+fleet 匹配，代价最小者胜出。
     * 调用方：TaskExecutionEngine.tryAssignOnce（自动分配链路）。
     */
    @Override
    public Optional<RobotSnapshot> select(TaskGoal goal, String fleetName, String robotGroup) {
        return selectWithTrace(goal, fleetName, robotGroup).selected().map(candidate ->
                fleetStateMirror.list().stream()
                        .filter(robot -> candidate.robotCode().equals(robot.getRobotCode()))
                        .findFirst()
                        .orElse(null));
    }

    /**
     * 带评分明细的选择：全部候选按（ETA + 电量惩罚 + 忙碌惩罚）升序，
     * 首位胜出并标记 selected（分配透明化：事件 detail.assignment_trace）。
     */
    @Override
    public AssignmentDecision selectWithTrace(TaskGoal goal, String fleetName, String robotGroup) {
        String fleetFilter = resolveFleetFilter(fleetName, robotGroup);
        record ScoredRobot(RobotSnapshot robot, double eta, double batteryPenalty, double busyPenalty,
                           double total) { }
        List<ScoredRobot> scored = fleetStateMirror.list().stream()
                .filter(robot -> fleetFilter == null || fleetFilter.equals(robot.getFleetName()))
                .filter(RobotSnapshot::isDispatchable)
                .map(robot -> {
                    double eta = estimateEtaSeconds(robot, goal);
                    double batteryPenalty = batteryPenaltySeconds(robot);
                    double busyPenalty = busyPenaltySeconds(robot);
                    return new ScoredRobot(robot, eta, batteryPenalty, busyPenalty,
                            eta + batteryPenalty + busyPenalty);
                })
                // 候选资格（评分后过滤：充电中断安全性评估依赖 ETA）：
                // 执行中出局（排队语义由指定机器人链路承担）；充电中由策略评估中断安全性；其余参选
                .filter(s -> {
                    String mode = s.robot().getMode();
                    if ("running".equals(mode)) {
                        return false;
                    }
                    if ("charging".equals(mode)) {
                        return chargingPolicy.interruptible(s.robot(), s.eta());
                    }
                    return true;
                })
                .sorted(Comparator.comparingDouble(ScoredRobot::total))
                .toList();
        List<AssignmentDecision.AssignmentCandidate> candidates = new ArrayList<>(scored.size());
        for (int i = 0; i < scored.size(); i++) {
            ScoredRobot s = scored.get(i);
            candidates.add(new AssignmentDecision.AssignmentCandidate(
                    s.robot().getRobotCode(), s.robot().getRobotName(), s.robot().getBattery(),
                    s.robot().getMode(), s.eta(), s.batteryPenalty(), s.busyPenalty(),
                    s.total(), i == 0));
        }
        if (log.isDebugEnabled() && !candidates.isEmpty()) {
            log.debug("任务分配评分: goal={} candidates={}", goal.goalWaypointId(),
                    candidates.stream()
                            .map(c -> String.format("%s(total=%.0f,eta=%.0f,battery=%s)",
                                    c.robotCode(), c.totalCost(), c.etaSeconds(),
                                    c.batteryPercent() == null ? "?" : String.format("%.0f%%", c.batteryPercent())))
                            .toList());
        }
        return new AssignmentDecision(
                candidates.isEmpty() ? Optional.empty() : Optional.of(candidates.get(0)),
                candidates);
    }

    /** 计算候选代价：ETA + 电量惩罚 + 忙碌惩罚。 */
    double cost(RobotSnapshot robot, TaskGoal goal) {
        double eta = estimateEtaSeconds(robot, goal);
        return eta + batteryPenaltySeconds(robot) + busyPenaltySeconds(robot);
    }

    /** 规划 ETA：机器人吸附点 → 目标点；无图/不可达时退化为欧氏距离估算。 */
    private double estimateEtaSeconds(RobotSnapshot robot, TaskGoal goal) {
        if (!goal.requiresNavigation()) {
            return 0;
        }
        var graph = mapManager.activeGraph().orElse(null);
        if (graph == null || !graph.isLoaded()) {
            return estimateStraightSeconds(robot, goal);
        }
        String floorId = mapManager.activeFloorId().orElse(null);
        var target = graph.waypointByIdOrName(floorId, goal.goalWaypointId()).orElse(null);
        if (target == null) {
            return estimateStraightSeconds(robot, goal);
        }
        GraphAttachment attachment = graph.attach(floorId, robot.getX(), robot.getY(),
                SchedulerConstants.MAX_MERGE_WAYPOINT_DISTANCE, SchedulerConstants.MAX_MERGE_LANE_DISTANCE);
        String fromWaypoint = attachment.waypointId();
        if (fromWaypoint == null) {
            return estimateStraightSeconds(robot, goal);
        }
        double speed = properties.getNavigationMaxSpeedMps();
        return graph.plan(floorId, fromWaypoint, target.getId(), speed, null)
                .map(route -> route.etaSeconds())
                .orElseGet(() -> estimateStraightSeconds(robot, goal));
    }

    private double estimateStraightSeconds(RobotSnapshot robot, TaskGoal goal) {
        if (!goal.requiresNavigation()) {
            return 0;
        }
        var graph = mapManager.activeGraph().orElse(null);
        if (graph == null) {
            return 0;
        }
        String floorId = mapManager.activeFloorId().orElse(null);
        var target = graph.waypointByIdOrName(floorId, goal.goalWaypointId()).orElse(null);
        if (target == null) {
            return 0;
        }
        return Math.hypot(target.getX() - robot.getX(), target.getY() - robot.getY()) / properties.getNavigationMaxSpeedMps();
    }

    /** 电量惩罚（BatteryAware）：低于阈值线性加重，超低电量二次惩罚。 */
    private double batteryPenaltySeconds(RobotSnapshot robot) {
        Double battery = robot.getBattery();
        if (battery == null) {
            // 电量未知：轻微惩罚（保守）
            return properties.getBatteryPenaltyWeightSeconds() * 0.1;
        }
        double threshold = properties.getLowBatteryThresholdPercent();
        if (battery >= threshold) {
            return 0;
        }
        double factor = (threshold - battery) / threshold;
        // 低段（<10%）二次加重：优先保证电量过低机器人不接任务（留给返充）
        double quadratic = battery < 10 ? factor * factor * 3 : 0;
        return properties.getBatteryPenaltyWeightSeconds() * (factor + quadratic);
    }

    private double busyPenaltySeconds(RobotSnapshot robot) {
        String mode = robot.getMode();
        if ("running".equals(mode)) {
            // 执行中机器人实际不会进候选（资格过滤已剔除），保留代价语义完整
            return properties.getBusyPenaltySeconds();
        }
        if ("charging".equals(mode)) {
            // 充电打断代价：桩上机器人参与竞争的额外代价，同等条件空闲机优先
            return chargingPolicy.penaltySeconds();
        }
        return 0;
    }

    private String resolveFleetFilter(String fleetName, String robotGroup) {
        String value = fleetName != null && !fleetName.isBlank() ? fleetName : robotGroup;
        return value == null || value.isBlank() ? null : value;
    }
}
