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
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** 地图生命周期与任务绑定入口；任务接纳和激活在同一监视器内串行决策。 */
@Slf4j
@Component
public class MapManager {
    public static final String STATUS_PUBLISHED = "published";
    private final MapMetadataParser parser;
    private final MapArchiveRepository archiveRepository;
    private volatile MapSnapshot activeSnapshot;
    private int activeBindings;
    private long generation;
    private Runnable onActivated;
    private BooleanSupplier activationBlocked = () -> false;

    public MapManager(MapMetadataParser parser, MapArchiveRepository archiveRepository) {
        this.parser = parser;
        this.archiveRepository = archiveRepository;
    }

    /** 门禁通过后、快照提交前清理旧运行态；清理失败时中止发布。 */
    public synchronized void setOnActivated(Runnable onActivated) {
        this.onActivated = onActivated;
    }

    /** 由装配层补充交通占用检查；驻留占用也必须先解除，才允许切换地图。 */
    public synchronized void setActivationBlocked(BooleanSupplier activationBlocked) {
        this.activationBlocked = java.util.Objects.requireNonNull(activationBlocked);
    }

    /** 接纳任务时立即绑定，包含排队和自动分配等待阶段。无地图时也阻止中途加载地图。 */
    public synchronized MapBinding bindTask() {
        activeBindings++;
        return new MapBinding(this, activeSnapshot);
    }

    public Optional<MapSnapshot> activeSnapshot() {
        return Optional.ofNullable(activeSnapshot);
    }

    public Optional<MapMetadata> activeMetadata() {
        return activeSnapshot().map(MapSnapshot::metadata);
    }

    public Optional<NavigationGraph> activeGraph() {
        return activeSnapshot().map(MapSnapshot::graph);
    }

    public Instant loadedAt() {
        return activeSnapshot().map(MapSnapshot::loadedAt).orElse(null);
    }

    public boolean isLoaded() {
        return activeSnapshot != null;
    }

    public Optional<String> activeFloorId() {
        return activeSnapshot().map(MapSnapshot::defaultFloorId);
    }

    public boolean loadFromArchive() {
        return archiveRepository.findActive().map(archive -> {
            try {
                activate(parser.parse(archive.getMetadataJson()), archive, false);
                return true;
            } catch (MapMetadataException e) {
                log.error("存档地图解析失败: map={} version={} error={}",
                        archive.getMapName(), archive.getVersion(), e.getMessage());
                return false;
            }
        }).orElse(false);
    }

    public void loadFromFiles(String metadataJsonPath, String pgmPath, String yamlPath) {
        String json;
        try {
            json = Files.readString(Path.of(metadataJsonPath), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new MapMetadataException("地图文件加载失败: " + e.getMessage(), e);
        }
        MapMetadata metadata = parser.parse(json);
        activate(metadata, archive(metadata, json, pgmPath, yamlPath), false);
    }

    /** 上传仅 published 版本；拒绝时不更新地图存档，也不清理运行态。 */
    public MapMetadata activateFromJson(String json, String pgmPath, String yamlPath) {
        MapMetadata metadata = parser.parse(json);
        activate(metadata, archive(metadata, json, pgmPath, yamlPath), true);
        return metadata;
    }

    private MapArchive archive(MapMetadata metadata, String json, String pgmPath, String yamlPath) {
        MapArchive archive = new MapArchive();
        archive.setMapName(metadata.getMapName());
        archive.setVersion(metadata.getVersionInfo().getVersion());
        archive.setStatus(metadata.getVersionInfo().getStatus());
        archive.setMetadataJson(json);
        archive.setPgmPath(pgmPath);
        archive.setYamlPath(yamlPath);
        return archive;
    }

    private synchronized void activate(MapMetadata metadata, MapArchive archive, boolean persist) {
        if (!STATUS_PUBLISHED.equals(metadata.getVersionInfo().getStatus())) {
            throw new MapMetadataException("只有 published 状态的地图版本才能激活");
        }
        if (activeBindings > 0) {
            throw new MapActivationConflictException("地图仍有 " + activeBindings + " 个使用绑定，请等待任务退出后再切换");
        }
        if (activationBlocked.getAsBoolean()) {
            throw new MapActivationConflictException("仍有交通占用（含机器人驻留），请确认机器人停止并解除占用后再切换");
        }
        // 构图失败时旧快照、存档均不变；门禁通过后才执行清理与存档。
        NavigationGraph graph = NavigationGraph.from(metadata);
        if (onActivated != null) {
            onActivated.run();
        }
        if (persist) {
            archiveRepository.findByMapNameAndVersion(archive.getMapName(), archive.getVersion())
                    .ifPresentOrElse(existing -> {
                        archive.setId(existing.getId());
                        archive.setActivatedAt(existing.getActivatedAt());
                        archiveRepository.update(archive);
                    }, () -> archiveRepository.save(archive));
        }
        activeSnapshot = new MapSnapshot(metadata.getMapName(), metadata.getVersionInfo().getVersion(),
                ++generation, metadata, graph, Instant.now(), archive.getPgmPath(), archive.getYamlPath());
        log.info("地图已激活: map={} version={} generation={}",
                activeSnapshot.mapName(), activeSnapshot.version(), activeSnapshot.generation());
    }

    /** 绑定关闭幂等，可由排队取消和执行线程退出分别调用。 */
    public static final class MapBinding implements AutoCloseable {
        private final MapManager owner;
        private final MapSnapshot snapshot;
        private boolean closed;

        private MapBinding(MapManager owner, MapSnapshot snapshot) {
            this.owner = owner;
            this.snapshot = snapshot;
        }

        public Optional<MapSnapshot> snapshot() {
            return Optional.ofNullable(snapshot);
        }

        @Override
        public void close() {
            synchronized (owner) {
                if (!closed) {
                    closed = true;
                    owner.activeBindings--;
                }
            }
        }
    }
}
