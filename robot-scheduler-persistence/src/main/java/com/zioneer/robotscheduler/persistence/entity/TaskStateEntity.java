package com.zioneer.robotscheduler.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 任务状态记录（t_task_state；事件驱动 upsert）。
 */
@Getter
@Setter
@TableName(value = "t_task_state", autoResultMap = true)
public class TaskStateEntity {

    @TableId
    private Long id;

    @TableField("rmf_task_id")
    private String rmfTaskId;

    @TableField("business_task_id")
    private String businessTaskId;

    @TableField("robot_code")
    private String robotCode;

    @TableField("status")
    private String status;

    @TableField("category")
    private String category;

    @TableField("message")
    private String message;

    @TableField(value = "detail", typeHandler = JacksonTypeHandler.class)
    private java.util.Map<String, Object> detail;

    @TableField(value = "cancellation", typeHandler = JacksonTypeHandler.class)
    private java.util.Map<String, Object> cancellation;

    @TableField(value = "inspection_result", typeHandler = JacksonTypeHandler.class)
    private java.util.Map<String, Object> inspectionResult;

    @TableField("updated_at")
    private Instant updatedAt;
}
