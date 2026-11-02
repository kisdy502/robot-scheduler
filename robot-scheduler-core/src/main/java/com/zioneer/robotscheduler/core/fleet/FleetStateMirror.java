package com.zioneer.robotscheduler.core.fleet;

import com.zioneer.robotscheduler.common.scheduler.SchedulerConstants;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 机器人状态内存镜像（对齐旧栈 _fleet_state_records：fleet_states 订阅缓存）。
 *
 * <p>P1 阶段由测试/模拟器直接注入；P2 接入 RMS 轮询后由 FleetManager 驱动更新。</p>
 */
@Component
public class FleetStateMirror {

    private final Map<String, RobotSnapshot> snapshotsByRobot = new ConcurrentHashMap<>();

    public void upsert(RobotSnapshot snapshot) {
        snapshotsByRobot.put(snapshot.getRobotCode(), snapshot);
    }

    public List<RobotSnapshot> list() {
        return List.copyOf(snapshotsByRobot.values());
    }

    public List<RobotSnapshot> listByFleet(String fleetName) {
        return snapshotsByRobot.values().stream()
                .filter(robot -> fleetName == null || fleetName.equals(robot.getFleetName()))
                .toList();
    }

    /** 是否在线：15s TTL 判定（对齐 LIVE_ROBOT_ONLINE_TTL_MILLIS）。 */
    public boolean isOnline(RobotSnapshot snapshot) {
        return System.currentTimeMillis() - snapshot.getUpdatedAtMillis()
                <= SchedulerConstants.LIVE_ROBOT_ONLINE_TTL_MILLIS;
    }

    /** 是否可派发：在线且 mode 不属于 error/unknown/paused（对齐旧栈 dispatchable 判定）。 */
    public boolean isDispatchable(RobotSnapshot snapshot) {
        if (!isOnline(snapshot)) {
            return false;
        }
        String mode = snapshot.getMode();
        return !"error".equals(mode) && !"unknown".equals(mode) && !"paused".equals(mode);
    }

    public void clear() {
        snapshotsByRobot.clear();
    }
}
