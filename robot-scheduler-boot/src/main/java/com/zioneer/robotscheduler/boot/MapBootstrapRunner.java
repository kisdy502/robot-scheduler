package com.zioneer.robotscheduler.boot;

import com.zioneer.robotscheduler.api.config.MapStorageProperties;
import com.zioneer.robotscheduler.core.map.MapManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 地图启动加载：DB 激活版本优先，无存档时按配置文件兜底（本地调试）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MapBootstrapRunner implements ApplicationRunner {

    private final MapManager mapManager;
    private final MapStorageProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        if (mapManager.loadFromArchive()) {
            return;
        }
        if (properties.isFallbackToFiles() && properties.getMetadataPath() != null
                && !properties.getMetadataPath().isBlank()) {
            try {
                mapManager.loadFromFiles(properties.getMetadataPath(),
                        properties.getPgmPath(), properties.getYamlPath());
                log.info("地图已从配置文件加载: {}", properties.getMetadataPath());
            } catch (Exception e) {
                log.error("配置文件地图加载失败，服务以无图模式启动: {}", e.getMessage());
            }
            return;
        }
        log.warn("无激活地图存档且未配置地图文件，服务以无图模式启动（等待地图上传接口激活）");
    }
}
