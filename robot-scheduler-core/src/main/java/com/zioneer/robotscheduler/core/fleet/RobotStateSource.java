package com.zioneer.robotscheduler.core.fleet;

import java.util.List;

/**
 * 机器人状态数据源端口：由 rms-client 提供实现（HTTP 轮询 RMS），
 * Mock 实现用于联调与测试；返回已转换为 {@link RobotSnapshot} 的数据（mode 由 FleetManager 推导）。
 */
public interface RobotStateSource {

    /**
     * 拉取全车队实时状态（1Hz 轮询）。
     *
     * @throws com.zioneer.robotscheduler.core.fleet.RobotStateSourceException RMS 不可用时抛出，由 FleetManager 降级处理
     */
    List<RobotSnapshot> fetchStates();

    /**
     * 拉取机器人清单（0.1Hz 轮询）：机器人上下线发现的依据。
     */
    List<RobotSnapshot> fetchInventory();
}
