package com.zioneer.robotscheduler.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;

/** 外部动作回执镜像（t_external_activity；观测档案，非任务表）。 */
@Getter
@Setter
@TableName(value = "t_external_activity", autoResultMap = true)
public class ExternalActivityEntity {

    @TableId
    private Long id;

    @TableField("robot_code")
    private String robotCode;

    @TableField("body_task_id")
    private String bodyTaskId;

    @TableField("rms_command_id")
    private String rmsCommandId;

    @TableField("action_type")
    private String actionType;

    @TableField("action_status")
    private Integer actionStatus;

    /** JSONB（MyBatis-Plus JacksonTypeHandler，stringtype=unspecified 前提）。 */
    @TableField(value = "result_report", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> resultReport;

    @TableField("failure_msg")
    private String failureMsg;

    @TableField("robot_reported_ms")
    private Long robotReportedMs;

    @TableField("finished_at")
    private Instant finishedAt;

    @TableField("created_at")
    private Instant createdAt;
}
