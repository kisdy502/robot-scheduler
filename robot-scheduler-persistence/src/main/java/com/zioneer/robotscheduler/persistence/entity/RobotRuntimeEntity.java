package com.zioneer.robotscheduler.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.List;

/** 可空字段必须允许显式写 NULL，释放使用权和结束作业不能被 MyBatis 的默认忽略策略吞掉。 */
@Getter
@Setter
@TableName(value = "t_robot_runtime", autoResultMap = true)
public class RobotRuntimeEntity {
    @TableId(type = IdType.INPUT)
    private String robotCode;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String taskflowId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String operationId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Instant operationStartedAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String completedTaskflowId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String completedOperationId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Instant completedStartedAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Instant completedFinishedAt;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> segmentIds;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> waypointIds;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String dwellWaypointId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String dwellSegmentId;
    private Boolean awaitingStop;
    private Boolean stopAccepted;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Instant acquiredAt;
    private String mapName;
    private String mapVersion;
    private Integer lastCommandId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String schedulerTaskId;
    private Instant updatedAt;
}
