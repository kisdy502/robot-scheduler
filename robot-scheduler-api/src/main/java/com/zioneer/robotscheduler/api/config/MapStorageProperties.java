package com.zioneer.robotscheduler.api.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 地图存储配置（附件落盘目录；后续 S3/MinIO 化时替换 storeAttachment 实现）。
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "robot.scheduler.map")
public class MapStorageProperties {

    /** 本地调试兜底：地图元数据 json 路径。 */
    private String metadataPath;

    /** 本地调试兜底：地图 png/pgm 路径。 */
    private String pgmPath;

    /** 本地调试兜底：SLAM 地图 yaml 路径。 */
    private String yamlPath;

    /** 上传附件存储目录。 */
    private String attachmentDir = "./data/map-attachments";

    /** DB 无激活版本时是否尝试从配置文件加载。 */
    private boolean fallbackToFiles = true;
}
