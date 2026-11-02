package com.zioneer.robotscheduler.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 机器人事件记录（t_robot_event；K8s Events 风格聚合）。 */
@Getter
@Setter
@TableName("t_robot_event")
public class RobotEventEntity {

    @TableId
    private Long id;

    @TableField("robot_code")
    private String robotCode;

    @TableField("event_type")
    private String eventType;

    @TableField("reason")
    private String reason;

    @TableField("severity")
    private String severity;

    @TableField("old_value")
    private String oldValue;

    @TableField("new_value")
    private String newValue;

    @TableField("message")
    private String message;

    @TableField("event_count")
    private Long eventCount;

    @TableField("first_at")
    private Instant firstAt;

    @TableField("last_at")
    private Instant lastAt;
}
