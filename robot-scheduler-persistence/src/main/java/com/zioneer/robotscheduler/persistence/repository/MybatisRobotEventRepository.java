package com.zioneer.robotscheduler.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zioneer.robotscheduler.core.repository.RobotEventRecord;
import com.zioneer.robotscheduler.core.repository.RobotEventRepository;
import com.zioneer.robotscheduler.persistence.entity.RobotEventEntity;
import com.zioneer.robotscheduler.persistence.mapper.RobotEventMapper;
import com.zioneer.robotscheduler.persistence.support.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 机器人事件仓储 MyBatis-Plus 实现（聚合决策在 RobotEventMonitor，本类只读写）。
 */
@Repository
@RequiredArgsConstructor
public class MybatisRobotEventRepository implements RobotEventRepository {

    private final RobotEventMapper mapper;
    private final SnowflakeIdGenerator idGenerator;

    @Override
    public Optional<RobotEventRecord> findLatest(String robotCode, String eventType, String reason) {
        RobotEventEntity entity = mapper.selectOne(new LambdaQueryWrapper<RobotEventEntity>()
                .eq(RobotEventEntity::getRobotCode, robotCode)
                .eq(RobotEventEntity::getEventType, eventType)
                .eq(RobotEventEntity::getReason, reason)
                .orderByDesc(RobotEventEntity::getLastAt)
                .last("limit 1"));
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    @Override
    public void insert(RobotEventRecord record) {
        RobotEventEntity entity = toEntity(record);
        entity.setId(idGenerator.nextId());
        mapper.insert(entity);
    }

    @Override
    public void updateAggregated(RobotEventRecord record) {
        RobotEventEntity entity = new RobotEventEntity();
        entity.setId(record.getId());
        entity.setEventCount(record.getEventCount());
        entity.setNewValue(record.getNewValue());
        entity.setMessage(record.getMessage());
        entity.setLastAt(Instant.ofEpochMilli(record.getLastAtMillis()));
        mapper.updateById(entity);
    }

    @Override
    public List<RobotEventRecord> findPage(String robotCode, String eventType, String severity,
                                           int offset, int limit) {
        return mapper.selectList(baseWrapper(robotCode, eventType, severity)
                        .orderByDesc(RobotEventEntity::getLastAt)
                        .last("limit " + limit + " offset " + offset))
                .stream().map(this::toDomain).toList();
    }

    @Override
    public long count(String robotCode, String eventType, String severity) {
        return mapper.selectCount(baseWrapper(robotCode, eventType, severity));
    }

    @Override
    public long deleteOlderThan(long thresholdMillis) {
        return mapper.delete(new LambdaQueryWrapper<RobotEventEntity>()
                .lt(RobotEventEntity::getLastAt, Instant.ofEpochMilli(thresholdMillis)));
    }

    private LambdaQueryWrapper<RobotEventEntity> baseWrapper(String robotCode, String eventType,
                                                             String severity) {
        LambdaQueryWrapper<RobotEventEntity> wrapper = new LambdaQueryWrapper<>();
        if (robotCode != null && !robotCode.isBlank()) {
            wrapper.eq(RobotEventEntity::getRobotCode, robotCode);
        }
        if (eventType != null && !eventType.isBlank()) {
            wrapper.eq(RobotEventEntity::getEventType, eventType);
        }
        if (severity != null && !severity.isBlank()) {
            wrapper.eq(RobotEventEntity::getSeverity, severity);
        }
        return wrapper;
    }

    private RobotEventEntity toEntity(RobotEventRecord record) {
        RobotEventEntity entity = new RobotEventEntity();
        entity.setRobotCode(record.getRobotCode());
        entity.setEventType(record.getEventType());
        entity.setReason(record.getReason());
        entity.setSeverity(record.getSeverity());
        entity.setOldValue(record.getOldValue());
        entity.setNewValue(record.getNewValue());
        entity.setMessage(record.getMessage());
        entity.setEventCount(record.getEventCount());
        entity.setFirstAt(Instant.ofEpochMilli(record.getFirstAtMillis()));
        entity.setLastAt(Instant.ofEpochMilli(record.getLastAtMillis()));
        return entity;
    }

    private RobotEventRecord toDomain(RobotEventEntity entity) {
        RobotEventRecord record = new RobotEventRecord();
        record.setId(entity.getId());
        record.setRobotCode(entity.getRobotCode());
        record.setEventType(entity.getEventType());
        record.setReason(entity.getReason());
        record.setSeverity(entity.getSeverity());
        record.setOldValue(entity.getOldValue());
        record.setNewValue(entity.getNewValue());
        record.setMessage(entity.getMessage());
        record.setEventCount(entity.getEventCount() == null ? 1 : entity.getEventCount());
        record.setFirstAtMillis(entity.getFirstAt() == null ? null : entity.getFirstAt().toEpochMilli());
        record.setLastAtMillis(entity.getLastAt() == null ? null : entity.getLastAt().toEpochMilli());
        return record;
    }
}
