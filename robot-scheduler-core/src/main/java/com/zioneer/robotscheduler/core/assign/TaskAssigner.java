package com.zioneer.robotscheduler.core.assign;

import com.zioneer.robotscheduler.core.fleet.RobotSnapshot;
import com.zioneer.robotscheduler.core.task.TaskGoal;

import java.util.Optional;

/**
 * 集中任务分配端口：为无指定机器人的任务选择执行者（替代 RMF dispatcher 的竞标机制）。
 */
public interface TaskAssigner {

    /**
     * 选择执行机器人。
     *
     * @param goal       任务目标
     * @param fleetName  车队过滤（空=不限）
     * @param robotGroup 分组过滤（空=不限；与 fleet 同值语义）
     * @return 最优机器人；无可用候选返回空
     */
    Optional<RobotSnapshot> select(TaskGoal goal, String fleetName, String robotGroup);

    /**
     * 带评分明细的选择（分配透明化）：返回全部候选的 ETA/电量惩罚/总分与胜出者。
     * 默认实现退化为无 trace 的 select；代价分配实现覆盖此方法。
     */
    default AssignmentDecision selectWithTrace(TaskGoal goal, String fleetName, String robotGroup) {
        return new AssignmentDecision(select(goal, fleetName, robotGroup).map(
                robot -> new AssignmentDecision.AssignmentCandidate(robot.getRobotCode(), robot.getRobotName(),
                        robot.getBattery(), robot.getMode(), 0, 0, 0, 0, true)),
                java.util.List.of());
    }
}
