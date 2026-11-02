package com.zioneer.robotscheduler.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.zioneer.robotscheduler.persistence.domain.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 地图存档（元数据 json 正本 + 附件路径）。
 */
@Getter
@Setter
@TableName(value = "t_map_metadata", autoResultMap = true)
public class MapArchiveEntity extends BaseEntity {

    @TableField("map_name")
    private String mapName;

    @TableField("version")
    private String version;

    @TableField("status")
    private String status;

    /** 元数据 json 原文（纯文本存储，避免嵌套 jsonb 的序列化歧义）。 */
    @TableField("metadata_json")
    private String metadataJson;

    @TableField("pgm_path")
    private String pgmPath;

    @TableField("yaml_path")
    private String yamlPath;

    @TableField("activated_at")
    private Instant activatedAt;
}
