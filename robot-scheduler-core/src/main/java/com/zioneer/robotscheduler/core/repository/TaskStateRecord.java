package com.zioneer.robotscheduler.core.repository;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * 任务状态记录（t_task_state 落库域模型，含失败原因/取消标记，供历史查询与上层观测）。
 */
@Getter
@Setter
public class TaskStateRecord {

    private Long id;

    private String rmfTaskId;

    private String businessTaskId;

    private String robotCode;

    /** queued/underway/completed/failed/canceled。 */
    private String status;

    private String category;

    /** 状态说明（失败真实原因）。 */
    private String message;

    private Map<String, Object> detail;

    private Map<String, Object> cancellation;

    private Map<String, Object> inspectionResult;

    private Long updatedAtMillis;
}
