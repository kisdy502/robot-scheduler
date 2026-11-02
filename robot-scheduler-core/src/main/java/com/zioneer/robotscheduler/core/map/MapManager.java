package com.zioneer.robotscheduler.core.map;

import com.zioneer.robotscheduler.core.repository.MapArchive;
import com.zioneer.robotscheduler.core.repository.MapArchiveRepository;
import com.zioneer.robotscheduler.core.routing.NavigationGraph;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 地图管理器：持有当前激活地图（元数据 + 导航图），支持热切换。
 *
 * <p>加载优先级：存档激活版本 → 配置文件兜底（本地调试）。
 * 地图元数据一个 json 全量描述（SLAM 坐标系），替换旧栈 scene bundle 多配置文件体系。</p>
 */
@Slf4j
@Component
public class MapManager {

    public static final String STATUS_PUBLISHED = "published";

    private final MapMetadataParser parser;
    private final MapArchiveRepository archiveRepository;

    private volatile MapMetadata activeMetadata;
    private volatile NavigationGraph activeGraph;
    private volatile Instant loadedAt;

    /** 地图激活后回调（热切换时清理交通占用等运行态，由装配层注入）。 */
    private volatile Runnable onActivated;

    public void setOnActivated(Runnable onActivated) {
        this.onActivated = onActivated;
    }

    public MapManager(MapMetadataParser parser, MapArchiveRepository archiveRepository) {
        this.parser = parser;
        this.archiveRepository = archiveRepository;
    }

    /**
     * 启动加载：优先存档激活版本；无存档时返回 false 由调用方走配置文件兜底。
     */
    public boolean loadFromArchive() {
        return archiveRepository.findActive().map(archive -> {
            try {
                activateInternal(parser.parse(archive.getMetadataJson()), archive);
                return true;
            } catch (MapMetadataException e) {
                log.error("存档地图解析失败，跳过激活: map={} version={} error={}",
                        archive.getMapName(), archive.getVersion(), e.getMessage());
                return false;
            }
        }).orElse(false);
    }

    /**
     * 本地调试兜底：从文件加载（不写存档）。
     */
    public void loadFromFiles(String metadataJsonPath, String pgmPath, String yamlPath) {
        try {
            String json = Files.readString(Path.of(metadataJsonPath), StandardCharsets.UTF_8);
            MapMetadata metadata = parser.parse(json);
            MapArchive archive = new MapArchive();
            archive.setMapName(metadata.getMapName());
            archive.setVersion(metadata.getVersionInfo().getVersion());
            archive.setStatus(metadata.getVersionInfo().getStatus());
            archive.setMetadataJson(json);
            archive.setPgmPath(pgmPath);
            archive.setYamlPath(yamlPath);
            activateInternal(metadata, archive);
        } catch (MapMetadataException e) {
            throw e;
        } catch (Exception e) {
            throw new MapMetadataException("地图文件加载失败: " + e.getMessage(), e);
        }
    }

    /**
     * 上传激活：解析校验（必须 published）→ 切换运行图 → 写存档。
     */
    public MapMetadata activateFromJson(String metadataJson, String pgmPath, String yamlPath) {
        MapMetadata metadata = parser.parse(metadataJson);
        if (!STATUS_PUBLISHED.equals(metadata.getVersionInfo().getStatus())) {
            throw new MapMetadataException("只有 published 状态的地图版本才能激活，当前状态: "
                    + metadata.getVersionInfo().getStatus());
        }
        MapArchive archive = new MapArchive();
        archive.setMapName(metadata.getMapName());
        archive.setVersion(metadata.getVersionInfo().getVersion());
        archive.setStatus(metadata.getVersionInfo().getStatus());
        archive.setMetadataJson(metadataJson);
        archive.setPgmPath(pgmPath);
        archive.setYamlPath(yamlPath);
        archiveRepository.findByMapNameAndVersion(metadata.getMapName(), metadata.getVersionInfo().getVersion())
                .ifPresentOrElse(existing -> {
                    archive.setId(existing.getId());
                    archive.setActivatedAt(existing.getActivatedAt());
                    archiveRepository.update(archive);
                }, () -> archiveRepository.save(archive));
        activateInternal(metadata, archive);
        log.info("地图已激活并存档: map={} version={}", metadata.getMapName(),
                metadata.getVersionInfo().getVersion());
        return metadata;
    }

    /** 当前激活元数据（未加载为空）。 */
    public Optional<MapMetadata> activeMetadata() {
        return Optional.ofNullable(activeMetadata);
    }

    /** 当前运行图（未加载为空）。 */
    public Optional<NavigationGraph> activeGraph() {
        return Optional.ofNullable(activeGraph);
    }

    public Instant loadedAt() {
        return loadedAt;
    }

    public boolean isLoaded() {
        return activeMetadata != null;
    }

    /** 默认楼层：第一层（本期单层调度；跨层调度 P4+ 使用）。 */
    public Optional<String> activeFloorId() {
        return activeMetadata().map(metadata -> metadata.getFloors().get(0).getFloorId());
    }

    private void activateInternal(MapMetadata metadata, MapArchive archive) {
        this.activeMetadata = metadata;
        this.activeGraph = NavigationGraph.from(metadata);
        this.loadedAt = Instant.now();
        List<String> waypointCount = metadata.getFloors().stream()
                .map(floor -> floor.getFloorId() + ":" + floor.getWaypoints().size())
                .toList();
        log.info("导航图已构建: map={} floors={} waypoints={} paths={}",
                metadata.getMapName(), metadata.getFloors().size(), waypointCount,
                metadata.getFloors().stream().mapToInt(f -> f.getPaths().size()).sum());
        if (onActivated != null) {
            try {
                onActivated.run();
            } catch (Exception e) {
                log.warn("地图激活回调执行失败: {}", e.getMessage());
            }
        }
    }
}
