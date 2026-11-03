package com.zioneer.robotscheduler.core.fleet;

import com.zioneer.robotscheduler.core.dispatch.SchedulerHttpException;
import com.zioneer.robotscheduler.core.traffic.InMemoryTrafficRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class RobotAvailabilityServiceTest {
    private final TaskAssignmentRegistry registry = new TaskAssignmentRegistry();
    private final InMemoryTrafficRegistry traffic = new InMemoryTrafficRegistry();
    private final RobotAvailabilityService availability = new RobotAvailabilityService(registry, traffic);

    private RobotSnapshot robot() {
        var robot = new RobotSnapshot();
        robot.setRobotCode("R1");
        robot.setOnline(true);
        robot.setDispatchable(true);
        return robot;
    }

    // 全局不可派发不应挡住持有者；外部作业阻止导航，结束并释放使用权后仍保留物理驻留。
    @Test
    void globalAvailabilityDoesNotBlockOwnerAndReleaseDoesNotRemoveDwell() {
        var robot = robot();
        traffic.updateDwell("R1", "W1", null);
        registry.assign("flow", "R1");
        availability.updateReadiness(robot, true);
        assertThat(robot.isDispatchable()).isFalse();
        assertThat(availability.canExecute(robot, "flow")).isTrue();
        assertThat(availability.canExecute(robot, "other")).isFalse();
        registry.startOperation("flow", "R1", "photo");
        assertThat(availability.canExecute(robot, "flow")).isFalse();
        registry.finishOperation("flow", "R1", "photo");
        registry.release("flow", "R1");
        assertThat(traffic.occupiedWaypoints()).contains("W1");
        assertThat(availability.canExecute(robot, null)).isTrue();
    }

    // 持有使用权仍需满足在线、基础就绪和停止确认条件，未确认停止时不能开始外部作业。
    @Test
    void ownerStillNeedsOnlineAndBaseReadinessAndStopConfirmation() {
        var robot = robot();
        registry.assign("flow", "R1");
        availability.updateReadiness(robot, false);
        assertThat(availability.canExecute(robot, "flow")).isFalse();
        availability.updateReadiness(robot, true);
        robot.setOnline(false);
        assertThat(availability.canExecute(robot, "flow")).isFalse();
        robot.setOnline(true);
        traffic.retainUntilStopped("R1", "navigation failed");
        assertThat(availability.canExecute(robot, "flow")).isFalse();
        assertThatThrownBy(() -> availability.assertExternalStartAllowed("R1"))
                .isInstanceOf(SchedulerHttpException.class);
    }

    // 初次检查后若机器人被其他流程取得使用权，自动任务在实际受理前复查并跳过。
    @Test
    void automaticAdmissionRechecksClaimImmediatelyBeforeRunning() {
        var robot = robot();
        assertThat(availability.canAllocate(robot, null)).isTrue();
        registry.assign("flow", "R1");
        var admitted = new java.util.concurrent.atomic.AtomicBoolean();
        availability.runIfAllocatable(robot, null, () -> admitted.set(true));
        assertThat(admitted).isFalse();
    }
}
