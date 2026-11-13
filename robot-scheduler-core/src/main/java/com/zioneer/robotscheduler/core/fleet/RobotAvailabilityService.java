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

    public void updateReadiness(RobotSnapshot robot, boolean ready, String blockedReason) {
        robot.setNavigationReady(ready);
        String owner = registry.ownerOf(robot.getRobotCode());
        robot.setOwnedByTaskflow(owner);
        robot.setOwnedSinceMillis(owner == null ? null : registry.ownerSince(owner));
        boolean ownershipOk = registry.canExecute(robot.getRobotCode(), null);
        robot.setDispatchable(ready && ownershipOk);
        robot.setDispatchBlockedReason(!ready ? blockedReason
                : ownershipOk ? null
                : owner == null ? "使用权状态异常"
                : "使用权被任务流 " + owner + " 占用（" + heldSeconds(robot) + "）");
    }

    private String heldSeconds(RobotSnapshot robot) {
        if (robot.getOwnedSinceMillis() == null) {
            return "未知时长";
        }
        long seconds = (System.currentTimeMillis() - robot.getOwnedSinceMillis()) / 1000;
        if (seconds < 90) {
            return seconds + " 秒";
        }
        if (seconds < 5400) {
            return String.format("%.1f 分钟", seconds / 60.0);
        }
        return String.format("%.1f 小时", seconds / 3600.0);
    }

    /** 兼容旧调用（无原因）。 */
    public void updateReadiness(RobotSnapshot robot, boolean ready) {
        updateReadiness(robot, ready, ready ? null : "基础导航未就绪");
    }
}
