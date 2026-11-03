package com.zioneer.robotscheduler.core.fleet;

import com.zioneer.robotscheduler.core.traffic.TrafficRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 统一基础就绪与任务流使用权判断，避免持有者被全局不可派发标志阻塞。 */
@Component
@RequiredArgsConstructor
public class RobotAvailabilityService {
    private final TaskAssignmentRegistry registry;
    private final TrafficRegistry traffic;

    public boolean canExecute(RobotSnapshot robot, String owner) {
        boolean ready = robot.getNavigationReady() == null ? robot.isDispatchable() : robot.getNavigationReady();
        return robot.isOnline() && ready && !traffic.isAwaitingStop(robot.getRobotCode())
                && registry.canExecute(robot.getRobotCode(), owner);
    }
    public boolean canAllocate(RobotSnapshot robot, String owner) {
        return canExecute(robot, owner) && registry.canAllocate(robot.getRobotCode(), owner);
    }
    /** 自动返充的资格检查与任务登记必须和使用权变更串行，避免留下未实际受理的记录。 */
    public void runIfAllocatable(RobotSnapshot robot, String owner, Runnable admission) {
        synchronized (registry) {
            if (canAllocate(robot, owner)) admission.run();
        }
    }

    public void assertExternalStartAllowed(String robot) {
        if (traffic.isAwaitingStop(robot)) {
            throw com.zioneer.robotscheduler.core.dispatch.SchedulerHttpException.conflict(
                    "robot is awaiting stop confirmation; external operation cannot start");
        }
    }

    public void updateReadiness(RobotSnapshot robot, boolean ready) {
        robot.setNavigationReady(ready);
        robot.setDispatchable(ready && registry.canExecute(robot.getRobotCode(), null));
    }
}
