package com.zioneer.robotscheduler.core.repository;

import java.util.List;
import java.util.Optional;

/**
 * 机器人事件仓储端口（聚合决策在监测器，仓储只做读写）。
 */
public interface RobotEventRepository {

    /** 某机器人某类事件最近一条（聚合判断输入）。 */
    Optional<RobotEventRecord> findLatest(String robotCode, String eventType, String reason);

    void insert(RobotEventRecord record);

    /** 聚合更新（按 id：count/new_value/last_at/message）。 */
    void updateAggregated(RobotEventRecord record);

    List<RobotEventRecord> findPage(String robotCode, String eventType, String severity,
                                    int offset, int limit);

    long count(String robotCode, String eventType, String severity);

    /** 删除早于阈值的事件（保留期 GC）。 */
    long deleteOlderThan(long thresholdMillis);
}
