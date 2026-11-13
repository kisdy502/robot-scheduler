package com.zioneer.robotscheduler.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zioneer.robotscheduler.core.repository.ExternalActivityRecord;
import com.zioneer.robotscheduler.core.repository.ExternalActivityRepository;
import com.zioneer.robotscheduler.persistence.entity.ExternalActivityEntity;
import com.zioneer.robotscheduler.persistence.mapper.ExternalActivityMapper;
import com.zioneer.robotscheduler.persistence.support.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * 外部动作镜像仓储 MyBatis-Plus 实现。
 */
@Repository
@RequiredArgsConstructor
public class MybatisExternalActivityRepository implements ExternalActivityRepository {

    private final ExternalActivityMapper mapper;
    private final SnowflakeIdGenerator idGenerator;

    @Override
    public boolean existsByRmsCommandId(String rmsCommandId) {
        return mapper.selectCount(new LambdaQueryWrapper<ExternalActivityEntity>()
                .eq(ExternalActivityEntity::getRmsCommandId, rmsCommandId)) > 0;
    }

    @Override
    public void insert(ExternalActivityRecord record) {
        ExternalActivityEntity entity = new ExternalActivityEntity();
        entity.setId(idGenerator.nextId());
        entity.setRobotCode(record.getRobotCode());
        entity.setBodyTaskId(record.getBodyTaskId());
        entity.setRmsCommandId(record.getRmsCommandId());
        entity.setActionType(record.getActionType());
        entity.setActionStatus(record.getActionStatus());
        entity.setResultReport(record.getResultReport());
        entity.setFailureMsg(record.getFailureMessage());
        entity.setRobotReportedMs(record.getRobotReportedMillis());
        Instant now = Instant.ofEpochMilli(record.getCreatedAtMillis() == null
                ? System.currentTimeMillis() : record.getCreatedAtMillis());
        entity.setFinishedAt(now);
        entity.setCreatedAt(now);
        mapper.insert(entity);
    }

    @Override
    public List<ExternalActivityRecord> findPage(String robotCode, String bodyTaskId, int offset, int limit) {
        return mapper.selectList(baseWrapper(robotCode, bodyTaskId)
                        .orderByDesc(ExternalActivityEntity::getFinishedAt)
                        .last("limit " + limit + " offset " + offset))
                .stream().map(this::toDomain).toList();
    }

    @Override
    public long count(String robotCode, String bodyTaskId) {
        return mapper.selectCount(baseWrapper(robotCode, bodyTaskId));
    }

    private LambdaQueryWrapper<ExternalActivityEntity> baseWrapper(String robotCode, String bodyTaskId) {
        LambdaQueryWrapper<ExternalActivityEntity> wrapper = new LambdaQueryWrapper<>();
        if (robotCode != null && !robotCode.isBlank()) {
            wrapper.eq(ExternalActivityEntity::getRobotCode, robotCode);
        }
        if (bodyTaskId != null && !bodyTaskId.isBlank()) {
            wrapper.eq(ExternalActivityEntity::getBodyTaskId, bodyTaskId);
        }
        return wrapper;
    }

    private ExternalActivityRecord toDomain(ExternalActivityEntity entity) {
        ExternalActivityRecord record = new ExternalActivityRecord();
        record.setId(entity.getId());
        record.setRobotCode(entity.getRobotCode());
        record.setBodyTaskId(entity.getBodyTaskId());
        record.setRmsCommandId(entity.getRmsCommandId());
        record.setActionType(entity.getActionType());
        record.setActionStatus(entity.getActionStatus());
        record.setResultReport(entity.getResultReport());
        record.setFailureMessage(entity.getFailureMsg());
        record.setRobotReportedMillis(entity.getRobotReportedMs());
        record.setCreatedAtMillis(entity.getCreatedAt() == null ? null : entity.getCreatedAt().toEpochMilli());
        return record;
    }
}
