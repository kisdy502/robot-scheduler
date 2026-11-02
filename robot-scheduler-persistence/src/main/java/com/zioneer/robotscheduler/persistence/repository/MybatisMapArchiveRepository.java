package com.zioneer.robotscheduler.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zioneer.robotscheduler.core.repository.MapArchive;
import com.zioneer.robotscheduler.core.repository.MapArchiveRepository;
import com.zioneer.robotscheduler.persistence.entity.MapArchiveEntity;
import com.zioneer.robotscheduler.persistence.mapper.MapArchiveMapper;
import com.zioneer.robotscheduler.persistence.support.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 地图存档仓储 MyBatis-Plus 实现（全 Lambda 查询）。
 */
@Repository
@RequiredArgsConstructor
public class MybatisMapArchiveRepository implements MapArchiveRepository {

    private final MapArchiveMapper mapper;
    private final SnowflakeIdGenerator idGenerator;

    @Override
    public void save(MapArchive archive) {
        MapArchiveEntity entity = toEntity(archive);
        entity.setId(idGenerator.nextId());
        entity.setActivatedAt(Instant.now());
        mapper.insert(entity);
        archive.setId(entity.getId());
    }

    @Override
    public void update(MapArchive archive) {
        MapArchiveEntity entity = toEntity(archive);
        LambdaQueryWrapper<MapArchiveEntity> wrapper = new LambdaQueryWrapper<MapArchiveEntity>()
                .eq(MapArchiveEntity::getMapName, archive.getMapName())
                .eq(MapArchiveEntity::getVersion, archive.getVersion());
        entity.setMapName(null);
        entity.setVersion(null);
        mapper.update(entity, wrapper);
    }

    @Override
    public Optional<MapArchive> findActive() {
        LambdaQueryWrapper<MapArchiveEntity> wrapper = new LambdaQueryWrapper<MapArchiveEntity>()
                .isNotNull(MapArchiveEntity::getActivatedAt)
                .orderByDesc(MapArchiveEntity::getActivatedAt)
                .last("limit 1");
        return Optional.ofNullable(mapper.selectOne(wrapper)).map(this::toDomain);
    }

    @Override
    public Optional<MapArchive> findByMapNameAndVersion(String mapName, String version) {
        LambdaQueryWrapper<MapArchiveEntity> wrapper = new LambdaQueryWrapper<MapArchiveEntity>()
                .eq(MapArchiveEntity::getMapName, mapName)
                .eq(MapArchiveEntity::getVersion, version);
        return Optional.ofNullable(mapper.selectOne(wrapper)).map(this::toDomain);
    }

    @Override
    public List<MapArchive> findByMapName(String mapName) {
        LambdaQueryWrapper<MapArchiveEntity> wrapper = new LambdaQueryWrapper<MapArchiveEntity>()
                .eq(MapArchiveEntity::getMapName, mapName)
                .orderByDesc(MapArchiveEntity::getId);
        return mapper.selectList(wrapper).stream().map(this::toDomain).toList();
    }

    private MapArchiveEntity toEntity(MapArchive archive) {
        MapArchiveEntity entity = new MapArchiveEntity();
        entity.setId(archive.getId());
        entity.setMapName(archive.getMapName());
        entity.setVersion(archive.getVersion());
        entity.setStatus(archive.getStatus());
        entity.setMetadataJson(archive.getMetadataJson());
        entity.setPgmPath(archive.getPgmPath());
        entity.setYamlPath(archive.getYamlPath());
        entity.setActivatedAt(archive.getActivatedAt());
        return entity;
    }

    private MapArchive toDomain(MapArchiveEntity entity) {
        MapArchive archive = new MapArchive();
        archive.setId(entity.getId());
        archive.setMapName(entity.getMapName());
        archive.setVersion(entity.getVersion());
        archive.setStatus(entity.getStatus());
        archive.setMetadataJson(entity.getMetadataJson());
        archive.setPgmPath(entity.getPgmPath());
        archive.setYamlPath(entity.getYamlPath());
        archive.setActivatedAt(entity.getActivatedAt());
        archive.setCreatedAt(entity.getCreatedAt());
        return archive;
    }
}
