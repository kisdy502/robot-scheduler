package com.zioneer.robotscheduler.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.zioneer.robotscheduler.persistence.domain.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;

/**
 * 调度请求记录（对齐域模型 {@link com.zioneer.robotscheduler.core.repository.TaskRecord}）。
 */
@Getter
@Setter
@TableName(value = "t_task_record", autoResultMap = true)
public class TaskRecordEntity extends BaseEntity {

    /** 调度请求 ID（对外查询键）。 */
    @TableField("scheduler_request_id")
    private String schedulerRequestId;

    /** 业务任务键（taskflow 侧 task_id）。 */
    @TableField("business_task_id")
    private String businessTaskId;

    /** 内核任务 ID（对外字段名 rmf_task_id 保留兼容）。 */
    @TableField("rmf_task_id")
    private String rmfTaskId;

    /** 任务类别。 */
    @TableField("category")
    private String category;

    /** 指定机器人编码。 */
    @TableField("robot_code")
    private String robotCode;

    /** 受理状态：published / rmf_response_received / cancel_requested。 */
    @TableField("dispatch_status")
    private String dispatchStatus;

    /** 原始请求载荷（jsonb）。 */
    @TableField(value = "payload", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> payload;

    /** 内核回执（jsonb）。 */
    @TableField(value = "response", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> response;

    /** 取消请求信息（jsonb）。 */
    @TableField(value = "cancel_request", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> cancelRequest;

    /** 对外 message。 */
    @TableField("message")
    private String message;

    /** 场景编码快照。 */
    @TableField("scene_code")
    private String sceneCode;

    /** 是否已放弃（超龄归档，查询接口隐藏）。 */
    @TableField("is_abandoned")
    private Boolean abandoned;

    /** 放弃/归档时间。 */
    @TableField("archived_at")
    private Instant archivedAt;
}
