package com.zioneer.robotscheduler.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zioneer.robotscheduler.core.charger.ChargerInfo;
import com.zioneer.robotscheduler.core.charger.ChargerRecordRepository;
import com.zioneer.robotscheduler.persistence.entity.ChargerEntity;
import com.zioneer.robotscheduler.persistence.mapper.ChargerMapper;
import com.zioneer.robotscheduler.persistence.support.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 充电桩仓储 MyBatis-Plus 实现（全 Lambda 查询；compatible_fleets 以逗号分隔字符串映射 text[]）。
 */
@Repository
@RequiredArgsConstructor
public class MybatisChargerRecordRepository implements ChargerRecordRepository {

    private final ChargerMapper mapper;
    private final SnowflakeIdGenerator idGenerator;

    @Override
    public List<ChargerInfo> findAll() {
        return mapper.selectList(new LambdaQueryWrapper<ChargerEntity>()
                        .orderByAsc(ChargerEntity::getChargerId))
                .stream().map(this::toDomain).toList();
    }

    @Override
    public Optional<ChargerInfo> findByChargerId(String chargerId) {
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<ChargerEntity>()
                        .eq(ChargerEntity::getChargerId, chargerId)))
                .map(this::toDomain);
    }

    @Override
    public void upsert(ChargerInfo charger) {
        ChargerEntity entity = toEntity(charger);
        ChargerEntity existing = mapper.selectOne(new LambdaQueryWrapper<ChargerEntity>()
                .eq(ChargerEntity::getChargerId, charger.getChargerId()));
        if (existing == null) {
            entity.setId(idGenerator.nextId());
            mapper.insert(entity);
        } else {
            // 地图对账更新静态字段，保留动态占用（status/occupied_by/reserved_by）
            entity.setId(existing.getId());
            entity.setStatus(existing.getStatus());
            entity.setOccupiedBy(existing.getOccupiedBy());
            entity.setReservedBy(existing.getReservedBy());
            mapper.updateById(entity);
        }
    }

    @Override
    public void updateOccupancy(ChargerInfo charger) {
        ChargerEntity existing = mapper.selectOne(new LambdaQueryWrapper<ChargerEntity>()
                .eq(ChargerEntity::getChargerId, charger.getChargerId()));
        if (existing == null) {
            return;
        }
        ChargerEntity patch = new ChargerEntity();
        patch.setId(existing.getId());
        patch.setStatus(charger.getStatus());
        patch.setOccupiedBy(charger.getOccupiedBy());
        patch.setReservedBy(charger.getReservedBy());
        patch.setUpdatedAt(Instant.now());
        mapper.updateById(patch);
    }

    @Override
    public int deleteByChargerIdsNotIn(List<String> keepChargerIds) {
        List<Long> toDelete = mapper.selectList(new LambdaQueryWrapper<ChargerEntity>()
                        .notIn(ChargerEntity::getChargerId, keepChargerIds))
                .stream().map(ChargerEntity::getId).toList();
        toDelete.forEach(mapper::deleteById);
        return toDelete.size();
    }

    private ChargerEntity toEntity(ChargerInfo charger) {
        ChargerEntity entity = new ChargerEntity();
        entity.setChargerId(charger.getChargerId());
        entity.setChargerName(charger.getName());
        entity.setRmfWaypoint(charger.getRmfWaypoint());
        entity.setPointCode(charger.getPointCode());
        entity.setRmfLevel(charger.getRmfLevel());
        entity.setMapCode(charger.getMapCode());
        entity.setSceneCode(charger.getSceneCode());
        entity.setResourceType(charger.getResourceType() == null ? "charger" : charger.getResourceType());
        entity.setConnectorType(charger.getConnectorType());
        entity.setCompatibleFleets(charger.getCompatibleFleets() == null || charger.getCompatibleFleets().isEmpty()
                ? null : String.join(",", charger.getCompatibleFleets()));
        entity.setFleetName(charger.getFleetName());
        entity.setStatus(charger.getStatus());
        entity.setOccupiedBy(charger.getOccupiedBy());
        entity.setReservedBy(charger.getReservedBy());
        entity.setEnabled(charger.isEnabled());
        entity.setUpdatedAt(Instant.now());
        return entity;
    }

    private ChargerInfo toDomain(ChargerEntity entity) {
        ChargerInfo info = new ChargerInfo();
        info.setChargerId(entity.getChargerId());
        info.setName(entity.getChargerName());
        info.setRmfWaypoint(entity.getRmfWaypoint());
        info.setPointCode(entity.getPointCode());
        info.setRmfLevel(entity.getRmfLevel());
        info.setMapCode(entity.getMapCode());
        info.setSceneCode(entity.getSceneCode());
        info.setResourceType(entity.getResourceType());
        info.setConnectorType(entity.getConnectorType());
        info.setCompatibleFleets(entity.getCompatibleFleets() == null ? List.of()
                : Arrays.stream(entity.getCompatibleFleets().split(","))
                        .filter(value -> !value.isBlank()).collect(Collectors.toList()));
        info.setFleetName(entity.getFleetName());
        info.setStatus(entity.getStatus() == null ? 0 : entity.getStatus());
        info.setOccupiedBy(entity.getOccupiedBy());
        info.setReservedBy(entity.getReservedBy());
        info.setEnabled(!Boolean.FALSE.equals(entity.getEnabled()));
        info.setUpdatedAtMillis(entity.getUpdatedAt() == null ? 0 : entity.getUpdatedAt().toEpochMilli());
        return info;
    }
}
