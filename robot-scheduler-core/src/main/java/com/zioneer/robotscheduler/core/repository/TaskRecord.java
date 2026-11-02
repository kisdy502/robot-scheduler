package com.zioneer.robotscheduler.core.repository;

import com.zioneer.robotscheduler.common.scheduler.task.DispatchStatus;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * 调度请求记录域模型（对齐旧栈 bridge._records 的持久化字段；
 * 由 persistence 模块映射为 t_task_record 表）。
 */
@Getter
@Setter
public class TaskRecord {

    /** 主键（雪花 ID）。 */
    private Long id;

    /** 调度请求 ID（对外查询键，推荐用）。 */
    private String schedulerRequestId;

    /** 业务任务键（task_id）。 */
    private String businessTaskId;

    /** 内核任务 ID（对外字段名 rmf_task_id 保留）。 */
    private String rmfTaskId;

    /** 任务类别。 */
    private String category;

    /** 指定机器人编码（stop-task 按 robot_code 定位的冗余列）。 */
    private String robotCode;

    /** 受理状态（published/rmf_response_received/cancel_requested）。 */
    private DispatchStatus dispatchStatus;

    /** 请求原始载荷（JSON，审计与重放用）。 */
    private Map<String, Object> payload;

    /** 内核回执（JSON）。 */
    private Map<String, Object> response;

    /** 取消请求信息（JSON：stop_request_id/reason 等）。 */
    private Map<String, Object> cancelRequest;

    /** 消息（对外 message 字段）。 */
    private String message;

    /** 场景编码（任务下发时的场景快照）。 */
    private String sceneCode;

    /** 是否已放弃（超龄无内核信号，查询接口隐藏）。 */
    private boolean abandoned;

    /** 放弃/归档时间（毫秒）。 */
    private Long archivedAtMillis;

    private Long createdAtMillis;
    private Long updatedAtMillis;
}
