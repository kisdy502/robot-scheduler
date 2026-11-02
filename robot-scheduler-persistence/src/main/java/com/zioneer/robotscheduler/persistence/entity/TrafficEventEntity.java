package com.zioneer.robotscheduler.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;

/**
 * 交通事件审计（t_traffic_event：占用/释放/清理/死锁告警）。
 */
@Getter
@Setter
@TableName(value = "t_traffic_event", autoResultMap = true)
public class TrafficEventEntity {

    @TableId
    private Long id;

    @TableField("scene_code")
    private String sceneCode;

    @TableField("robot_code")
    private String robotCode;

    @TableField("resource_id")
    private String resourceId;

    /** ACQUIRE / RELEASE / CLEAR / DEADLOCK。 */
    @TableField("action")
    private String action;

    @TableField(value = "detail", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> detail;

    @TableField("created_at")
    private Instant createdAt;
}
