package com.zioneer.robotscheduler.core.repository;

import java.util.List;
import java.util.Optional;

/**
 * 调度请求记录仓储端口（persistence 模块提供 MyBatis-Plus 实现，全部使用 Lambda 查询，禁止手写 SQL）。
 */
public interface TaskRecordRepository {

    void save(TaskRecord record);

    void update(TaskRecord record);

    Optional<TaskRecord> findBySchedulerRequestId(String schedulerRequestId);

    Optional<TaskRecord> findByBusinessTaskId(String businessTaskId);

    Optional<TaskRecord> findByRmfTaskId(String rmfTaskId);

    /** 批量按业务任务 ID 取记录（观测列表 enrich 请求报文用）。 */
    List<TaskRecord> findByBusinessTaskIds(List<String> businessTaskIds);

    /** 机器人最近若干条未取消请求的记录（stop-task 按 robot_code 定位用，终态过滤由调用方结合任务状态镜像判定）。 */
    List<TaskRecord> findRecentByRobotCode(String robotCode, int limit);

    /** 归档扫描：published 且无内核任务 ID 且超龄未回执的记录（GC 放弃候选）。 */
    List<TaskRecord> findAbandonCandidates(long createdBeforeMillis, int limit);

    /** 归档扫描：长期未更新且未归档的记录（终态保留到期）。 */
    List<TaskRecord> findRetentionExpired(long updatedBeforeMillis, int limit);
}
