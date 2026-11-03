package com.zioneer.robotscheduler.core.repository;

import com.zioneer.robotscheduler.core.traffic.TrafficSnapshot;
import java.util.List;

/** 单实例持久化端口；写入成功后才能向外确认使用权或下发依赖交通预约的命令。 */
public interface RobotRuntimeRepository {
    List<RobotUsageSnapshot> findUsages();
    void saveUsage(RobotUsageSnapshot snapshot);
    List<TrafficSnapshot> findTraffic();
    void saveTraffic(TrafficSnapshot snapshot);
    int nextCommandId(String robotCode);
    void beginSchedulerExecution(String robotCode, String taskId, String mapName, String mapVersion);
    void finishSchedulerExecution(String robotCode, String taskId);
}
