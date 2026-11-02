package com.zioneer.robotscheduler.core.repository;

import java.util.List;

/**
 * 任务状态记录仓储端口（upsert 语义：按 rmf_task_id 唯一，事件驱动更新）。
 */
public interface TaskStateRecordRepository {

    void upsert(TaskStateRecord record);

    /** 分页查询（按机器人/状态过滤，更新时间倒序；robotCode/status 为空不过滤）。 */
    List<TaskStateRecord> findPage(String robotCode, String status, String category, int offset, int limit);

    /** 计数（同 findPage 过滤条件）。 */
    long count(String robotCode, String status, String category);

    java.util.Optional<TaskStateRecord> findByRmfTaskId(String rmfTaskId);

    /**
     * 启动对账：将非终态（underway/queued）任务批量标记为中断失败。
     * 调度重启后内核态丢失，DB 里的非终态记录成为永久僵尸（取消接口也救不回），启动时统一收口。
     *
     * @return 标记条数
     */
    int markInterrupted(String message);
}
