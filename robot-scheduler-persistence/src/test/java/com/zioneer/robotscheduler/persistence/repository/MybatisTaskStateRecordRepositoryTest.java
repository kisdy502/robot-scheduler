package com.zioneer.robotscheduler.persistence.repository;

import com.zioneer.robotscheduler.core.repository.TaskStateRecord;
import com.zioneer.robotscheduler.persistence.entity.TaskStateEntity;
import com.zioneer.robotscheduler.persistence.mapper.TaskStateMapper;
import com.zioneer.robotscheduler.persistence.support.SnowflakeIdGenerator;
import org.apache.ibatis.exceptions.PersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务状态 upsert 仓储测试：首事件插入 / 已存在更新 / 并发撞唯一键兜底转更新。
 */
@ExtendWith(MockitoExtension.class)
class MybatisTaskStateRecordRepositoryTest {

    @Mock
    private TaskStateMapper mapper;

    private SnowflakeIdGenerator idGenerator;

    private MybatisTaskStateRecordRepository repository;

    @BeforeEach
    void setUp() {
        idGenerator = new SnowflakeIdGenerator();
        repository = new MybatisTaskStateRecordRepository(mapper, idGenerator);
    }

    @Test
    void upsert_firstEvent_insertsWithGeneratedId() {
        when(mapper.selectOne(any())).thenReturn(null);

        repository.upsert(record("qi.1", "queued"));

        ArgumentCaptor<TaskStateEntity> captor = ArgumentCaptor.forClass(TaskStateEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getId()).isNotNull();
        assertThat(captor.getValue().getRmfTaskId()).isEqualTo("qi.1");
        assertThat(captor.getValue().getStatus()).isEqualTo("queued");
        verify(mapper, never()).updateById(any(TaskStateEntity.class));
    }

    @Test
    void upsert_existingEvent_updatesByIdKeepsRowId() {
        TaskStateEntity existing = entity(42L, "qi.1", "queued");
        when(mapper.selectOne(any())).thenReturn(existing);

        repository.upsert(record("qi.1", "underway"));

        ArgumentCaptor<TaskStateEntity> captor = ArgumentCaptor.forClass(TaskStateEntity.class);
        verify(mapper).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(42L);
        assertThat(captor.getValue().getStatus()).isEqualTo("underway");
        verify(mapper, never()).insert(any(TaskStateEntity.class));
    }

    @Test
    void upsert_concurrentInsertDuplicateKey_fallsBackToUpdate() {
        // 竞态兜底：insert 撞唯一键（对端已提交）→ 重查命中 → 转 updateById
        TaskStateEntity winner = entity(7L, "qi.1", "queued");
        when(mapper.selectOne(any())).thenReturn(null, winner);
        when(mapper.insert(any(TaskStateEntity.class))).thenThrow(new PersistenceException(
                "duplicate key value violates unique constraint uk_task_state_rmf_task_id"));

        repository.upsert(record("qi.1", "queued"));

        ArgumentCaptor<TaskStateEntity> captor = ArgumentCaptor.forClass(TaskStateEntity.class);
        verify(mapper).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(7L);
    }

    @Test
    void upsert_nonDuplicateKeyFailure_propagates() {
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insert(any(TaskStateEntity.class))).thenThrow(new PersistenceException("connection refused"));

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> repository.upsert(record("qi.1", "queued")))
                .isInstanceOf(PersistenceException.class);
    }

    private TaskStateRecord record(String rmfTaskId, String status) {
        TaskStateRecord record = new TaskStateRecord();
        record.setRmfTaskId(rmfTaskId);
        record.setStatus(status);
        record.setCategory("quality_inspection");
        record.setRobotCode("R001");
        record.setUpdatedAtMillis(System.currentTimeMillis());
        return record;
    }

    private TaskStateEntity entity(Long id, String rmfTaskId, String status) {
        TaskStateEntity entity = new TaskStateEntity();
        entity.setId(id);
        entity.setRmfTaskId(rmfTaskId);
        entity.setStatus(status);
        return entity;
    }
}
