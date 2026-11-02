package com.zioneer.robotscheduler.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/**
 * POST /api/v1/scheduler/stop-task 请求体（task_id / rmf_task_id / robot_code 三选一）。
 */
@Getter
@Setter
@Schema(name = "StopTaskRequest", description = "任务取消请求")
public class StopTaskRequest {

    @Schema(description = "业务任务 id")
    private String taskId;

    @Schema(description = "调度内核任务 id")
    private String rmfTaskId;

    @Schema(description = "机器人编码（取最新活跃任务）")
    private String robotCode;

    @Schema(description = "取消原因（进入 cancel labels: reason=<reason>）")
    private String reason;
}
