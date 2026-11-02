package com.zioneer.robotscheduler.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zioneer.robotscheduler.core.repository.TaskStateRecord;
import com.zioneer.robotscheduler.core.repository.TaskStateRecordRepository;
import com.zioneer.robotscheduler.persistence.entity.TaskStateEntity;
import com.zioneer.robotscheduler.persistence.mapper.TaskStateMapper;
import com.zioneer.robotscheduler.persistence.support.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 任务状态记录仓储 MyBatis-Plus 实现（rmf_task_id 唯一 upsert）。
 */
@Repository
@RequiredArgsConstructor
public class MybatisTaskStateRecordRepository implements TaskStateRecordRepository {

    /** 同键并发落库串行化（分条锁，避免全表写竞争）。 */
    private static final int LOCK_STRIPES = 64;
    private final Object[] lockStripes = createLockStripes();

    private final TaskStateMapper mapper;
    private final SnowflakeIdGenerator idGenerator;

    private static Object[] createLockStripes() {
        Object[] stripes = new Object[LOCK_STRIPES];
        for (int i = 0; i < stripes.length; i++) {
            stripes[i] = new Object();
        }
        return stripes;
    }

    private Object lockFor(String rmfTaskId) {
        return lockStripes[Math.floorMod(rmfTaskId.hashCode(), LOCK_STRIPES)];
    }

    @Override
    public void upsert(TaskStateRecord record) {
        // 同一任务的状态事件可能多线程并发到达（如自动分配链路 queued 事件先于分配结果发布），
        // 先查后写存在竞态：并发 select 均为空 → 双 insert → 撞 uk_task_state_rmf_task_id。
        // 同键锁串行化 + 撞唯一键兜底转更新（跨实例/极端时序保险）。
        synchronized (lockFor(record.getRmfTaskId())) {
            TaskStateEntity existing = selectByRmfTaskId(record.getRmfTaskId());
            if (existing == null) {
                TaskStateEntity entity = toEntity(record);
                entity.setId(idGenerator.nextId());
                try {
                    mapper.insert(entity);
                } catch (RuntimeException e) {
                    if (!isDuplicateKey(e)) {
                        throw e;
                    }
                    TaskStateEntity winner = selectByRmfTaskId(record.getRmfTaskId());
                    if (winner == null) {
                        throw e;
                    }
                    applyUpdate(record, winner);
                }
            } else {
                applyUpdate(record, existing);
            }
        }
    }

    private TaskStateEntity selectByRmfTaskId(String rmfTaskId) {
        return mapper.selectOne(new LambdaQueryWrapper<TaskStateEntity>()
                .eq(TaskStateEntity::getRmfTaskId, rmfTaskId));
    }

    private void applyUpdate(TaskStateRecord record, TaskStateEntity existing) {
        TaskStateEntity patch = toEntity(record);
        patch.setId(existing.getId());
        patch.setUpdatedAt(Instant.now());
        mapper.updateById(patch);
    }

    /** 沿因果链识别唯一键冲突（Mapper 未走 Spring 异常翻译，拿到的是 PersistenceException 包装）。 */
    private boolean isDuplicateKey(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() != t ? t.getCause() : null) {
            if (t instanceof org.springframework.dao.DuplicateKeyException) {
                return true;
            }
            String message = t.getMessage();
            if (message != null && message.contains("duplicate key")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<TaskStateRecord> findPage(String robotCode, String status, String category,
                                          int offset, int limit) {
        LambdaQueryWrapper<TaskStateEntity> wrapper = baseWrapper(robotCode, status, category)
                .orderByDesc(TaskStateEntity::getUpdatedAt)
                .last("limit " + limit + " offset " + offset);
        return mapper.selectList(wrapper).stream().map(this::toDomain).toList();
    }

    @Override
    public long count(String robotCode, String status, String category) {
        // count 不带 ORDER BY（PG 对带 ORDER BY 的聚合查询报 GROUP BY 错误）
        return mapper.selectCount(baseWrapper(robotCode, status, category));
    }

    private LambdaQueryWrapper<TaskStateEntity> baseWrapper(String robotCode, String status, String category) {
        LambdaQueryWrapper<TaskStateEntity> wrapper = new LambdaQueryWrapper<>();
        if (robotCode != null && !robotCode.isBlank()) {
            wrapper.eq(TaskStateEntity::getRobotCode, robotCode);
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(TaskStateEntity::getStatus, status);
        }
        if (category != null && !category.isBlank()) {
            wrapper.eq(TaskStateEntity::getCategory, category);
        }
        return wrapper;
    }

    @Override
    public Optional<TaskStateRecord> findByRmfTaskId(String rmfTaskId) {
        return Optional.ofNullable(mapper.selectOne(findByRmfWrapper(rmfTaskId)))
                .map(this::toDomain);
    }

    @Override
    public int markInterrupted(String message) {
        // 启动对账：非终态批量置 failed（只 set 非空字段，LambdaUpdateWrapper，无手写 SQL）
        TaskStateEntity patch = new TaskStateEntity();
        patch.setStatus(com.zioneer.robotscheduler.common.scheduler.task.TaskStatus.FAILED.wireName());
        patch.setMessage(message);
        patch.setUpdatedAt(Instant.now());
        return mapper.update(patch, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<TaskStateEntity>()
                .in(TaskStateEntity::getStatus,
                        com.zioneer.robotscheduler.common.scheduler.task.TaskStatus.RUNNING.wireName(),
                        com.zioneer.robotscheduler.common.scheduler.task.TaskStatus.QUEUED.wireName()));
    }

    private LambdaQueryWrapper<TaskStateEntity> findByRmfWrapper(String rmfTaskId) {
        return new LambdaQueryWrapper<TaskStateEntity>()
                .eq(TaskStateEntity::getRmfTaskId, rmfTaskId);
    }

    private TaskStateEntity toEntity(TaskStateRecord record) {
        TaskStateEntity entity = new TaskStateEntity();
        entity.setRmfTaskId(record.getRmfTaskId());
        entity.setBusinessTaskId(record.getBusinessTaskId());
        entity.setRobotCode(record.getRobotCode());
        entity.setStatus(record.getStatus());
        entity.setCategory(record.getCategory());
        entity.setMessage(record.getMessage());
        entity.setDetail(record.getDetail());
        entity.setCancellation(record.getCancellation());
        entity.setInspectionResult(record.getInspectionResult());
        entity.setUpdatedAt(Instant.now());
        return entity;
    }

    private TaskStateRecord toDomain(TaskStateEntity entity) {
        TaskStateRecord record = new TaskStateRecord();
        record.setId(entity.getId());
        record.setRmfTaskId(entity.getRmfTaskId());
        record.setBusinessTaskId(entity.getBusinessTaskId());
        record.setRobotCode(entity.getRobotCode());
        record.setStatus(entity.getStatus());
        record.setCategory(entity.getCategory());
        record.setMessage(entity.getMessage());
        record.setDetail(entity.getDetail());
        record.setCancellation(entity.getCancellation());
        record.setInspectionResult(entity.getInspectionResult());
        record.setUpdatedAtMillis(entity.getUpdatedAt() == null ? null : entity.getUpdatedAt().toEpochMilli());
        return record;
    }
}
