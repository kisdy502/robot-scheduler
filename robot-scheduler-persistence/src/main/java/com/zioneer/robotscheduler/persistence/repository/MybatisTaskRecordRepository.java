package com.zioneer.robotscheduler.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.zioneer.robotscheduler.common.scheduler.task.DispatchStatus;
import com.zioneer.robotscheduler.core.repository.TaskRecord;
import com.zioneer.robotscheduler.core.repository.TaskRecordRepository;
import com.zioneer.robotscheduler.persistence.entity.TaskRecordEntity;
import com.zioneer.robotscheduler.persistence.mapper.TaskRecordMapper;
import com.zioneer.robotscheduler.persistence.support.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 调度请求记录仓储 MyBatis-Plus 实现（全部 Lambda 查询，无 XML、无手写 SQL）。
 */
@Repository
@RequiredArgsConstructor
public class MybatisTaskRecordRepository implements TaskRecordRepository {

    private final TaskRecordMapper mapper;
    private final SnowflakeIdGenerator idGenerator;

    @Override
    public void save(TaskRecord record) {
        TaskRecordEntity entity = toEntity(record);
        entity.setId(idGenerator.nextId());
        mapper.insert(entity);
    }

    @Override
    public void update(TaskRecord record) {
        TaskRecordEntity entity = toEntity(record);
        LambdaUpdateWrapper<TaskRecordEntity> wrapper = new LambdaUpdateWrapper<TaskRecordEntity>()
                .eq(TaskRecordEntity::getSchedulerRequestId, record.getSchedulerRequestId());
        entity.setSchedulerRequestId(null);
        mapper.update(entity, wrapper);
    }

    @Override
    public Optional<TaskRecord> findBySchedulerRequestId(String schedulerRequestId) {
        return selectOne(TaskRecordEntity::getSchedulerRequestId, schedulerRequestId);
    }

    @Override
    public Optional<TaskRecord> findByBusinessTaskId(String businessTaskId) {
        return selectOne(TaskRecordEntity::getBusinessTaskId, businessTaskId);
    }

    @Override
    public Optional<TaskRecord> findByRmfTaskId(String rmfTaskId) {
        return selectOne(TaskRecordEntity::getRmfTaskId, rmfTaskId);
    }

    @Override
    public List<TaskRecord> findByBusinessTaskIds(List<String> businessTaskIds) {
        if (businessTaskIds == null || businessTaskIds.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<TaskRecordEntity> wrapper = new LambdaQueryWrapper<TaskRecordEntity>()
                .in(TaskRecordEntity::getBusinessTaskId, businessTaskIds);
        return mapper.selectList(wrapper).stream().map(this::toDomain).toList();
    }

    @Override
    public List<TaskRecord> findRecentByRobotCode(String robotCode, int limit) {
        LambdaQueryWrapper<TaskRecordEntity> wrapper = new LambdaQueryWrapper<TaskRecordEntity>()
                .eq(TaskRecordEntity::getRobotCode, robotCode)
                .orderByDesc(TaskRecordEntity::getId)
                .last("limit " + limit);
        return mapper.selectList(wrapper).stream().map(this::toDomain).toList();
    }

    @Override
    public List<TaskRecord> findAbandonCandidates(long createdBeforeMillis, int limit) {
        // 放弃候选：published 且无内核任务 ID（从未收到内核受理）且超龄未归档
        LambdaQueryWrapper<TaskRecordEntity> wrapper = new LambdaQueryWrapper<TaskRecordEntity>()
                .eq(TaskRecordEntity::getDispatchStatus, DispatchStatus.PUBLISHED.wireName())
                .isNull(TaskRecordEntity::getRmfTaskId)
                .eq(TaskRecordEntity::getAbandoned, false)
                .lt(TaskRecordEntity::getCreatedAt, Instant.ofEpochMilli(createdBeforeMillis))
                .orderByAsc(TaskRecordEntity::getId)
                .last("limit " + limit);
        return mapper.selectList(wrapper).stream().map(this::toDomain).toList();
    }

    @Override
    public List<TaskRecord> findRetentionExpired(long updatedBeforeMillis, int limit) {
        // 保留到期：长期未更新且未归档的记录（终态语义近似：无人再轮询）
        LambdaQueryWrapper<TaskRecordEntity> wrapper = new LambdaQueryWrapper<TaskRecordEntity>()
                .isNull(TaskRecordEntity::getArchivedAt)
                .lt(TaskRecordEntity::getUpdatedAt, Instant.ofEpochMilli(updatedBeforeMillis))
                .orderByAsc(TaskRecordEntity::getId)
                .last("limit " + limit);
        return mapper.selectList(wrapper).stream().map(this::toDomain).toList();
    }

    private Optional<TaskRecord> selectOne(SFunction<TaskRecordEntity, ?> column, String value) {
        LambdaQueryWrapper<TaskRecordEntity> wrapper = new LambdaQueryWrapper<TaskRecordEntity>()
                .eq(column, value)
                .orderByDesc(TaskRecordEntity::getId)
                .last("limit 1");
        return Optional.ofNullable(mapper.selectOne(wrapper)).map(this::toDomain);
    }

    private TaskRecordEntity toEntity(TaskRecord record) {
        TaskRecordEntity entity = new TaskRecordEntity();
        entity.setSchedulerRequestId(record.getSchedulerRequestId());
        entity.setBusinessTaskId(record.getBusinessTaskId());
        entity.setRmfTaskId(record.getRmfTaskId());
        entity.setCategory(record.getCategory());
        entity.setRobotCode(record.getRobotCode());
        entity.setDispatchStatus(record.getDispatchStatus() == null ? null : record.getDispatchStatus().wireName());
        entity.setPayload(record.getPayload());
        entity.setResponse(record.getResponse());
        entity.setCancelRequest(record.getCancelRequest());
        entity.setMessage(record.getMessage());
        entity.setSceneCode(record.getSceneCode());
        entity.setAbandoned(record.isAbandoned());
        entity.setArchivedAt(record.getArchivedAtMillis() == null ? null
                : Instant.ofEpochMilli(record.getArchivedAtMillis()));
        return entity;
    }

    private TaskRecord toDomain(TaskRecordEntity entity) {
        TaskRecord record = new TaskRecord();
        record.setId(entity.getId());
        record.setSchedulerRequestId(entity.getSchedulerRequestId());
        record.setBusinessTaskId(entity.getBusinessTaskId());
        record.setRmfTaskId(entity.getRmfTaskId());
        record.setCategory(entity.getCategory());
        record.setRobotCode(entity.getRobotCode());
        record.setDispatchStatus(DispatchStatus.PUBLISHED.wireName().equals(entity.getDispatchStatus())
                ? DispatchStatus.PUBLISHED
                : DispatchStatus.CANCEL_REQUESTED.wireName().equals(entity.getDispatchStatus())
                ? DispatchStatus.CANCEL_REQUESTED : DispatchStatus.RMF_RESPONSE_RECEIVED);
        record.setPayload(entity.getPayload());
        record.setResponse(entity.getResponse());
        record.setCancelRequest(entity.getCancelRequest());
        record.setMessage(entity.getMessage());
        record.setSceneCode(entity.getSceneCode());
        record.setAbandoned(Boolean.TRUE.equals(entity.getAbandoned()));
        record.setArchivedAtMillis(entity.getArchivedAt() == null ? null : entity.getArchivedAt().toEpochMilli());
        record.setCreatedAtMillis(entity.getCreatedAt() == null ? null : entity.getCreatedAt().toEpochMilli());
        record.setUpdatedAtMillis(entity.getUpdatedAt() == null ? null : entity.getUpdatedAt().toEpochMilli());
        return record;
    }
}
