package com.zioneer.robotscheduler.core.repository;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 地图存档域模型（json 元数据 + png/yaml 附件路径）。
 */
@Getter
@Setter
public class MapArchive {

    private Long id;

    private String mapName;

    /** 语义化版本（versionInfo.version）。 */
    private String version;

    /** 版本状态（published 才可激活）。 */
    private String status;

    /** 地图元数据 json 原文（正本）。 */
    private String metadataJson;

    /** 地图 png/pgm 图片存储路径。 */
    private String pgmPath;

    /** SLAM 地图 yaml 存储路径。 */
    private String yamlPath;

    private Instant activatedAt;

    private Instant createdAt;
}
