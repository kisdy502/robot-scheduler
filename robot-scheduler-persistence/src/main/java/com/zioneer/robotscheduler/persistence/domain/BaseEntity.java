package com.zioneer.robotscheduler.persistence.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 通用实体基类：主键（雪花，Service 层生成后写入）+ 审计字段自动填充。
 *
 * <p>时间统一 {@link Instant}，数据库列 TIMESTAMPTZ；逻辑删除 is_deleted。
 * 注意：使用 MyBatis-Plus API 时由 MetaObjectHandler 自动填充；审计字段对调度记录取 "system"。</p>
 */
@Getter
@Setter
public abstract class BaseEntity {

    /** 业务预生成主键（雪花算法，Service/Repository 层赋值后写入）。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    @TableField(value = "created_by", fill = FieldFill.INSERT)
    private String createdBy;

    @TableField(value = "updated_by", fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;

    @TableField(value = "is_deleted", fill = FieldFill.INSERT)
    private Boolean deleted;
}
