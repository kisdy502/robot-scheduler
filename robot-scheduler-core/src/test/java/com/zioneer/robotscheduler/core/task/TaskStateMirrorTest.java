package com.zioneer.robotscheduler.core.task;

import com.zioneer.robotscheduler.core.repository.TaskRecord;
import com.zioneer.robotscheduler.core.repository.TaskRecordRepository;
import com.zioneer.robotscheduler.core.repository.TaskStateRecord;
import com.zioneer.robotscheduler.core.repository.TaskStateRecordRepository;
import com.zioneer.robotscheduler.core.testsupport.InMemoryTaskRecordRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任务状态镜像落库行为：自动分配任务的 robot_code 回写（取消链路发 stop 依赖它）。
 */
class TaskStateMirrorTest {

    // 重启后查询历史终态仍需保留取消标记，避免业务把取消产生的 completed 当作到点成功。
    @Test
    void emptyRuntimeMirror_readsPersistedTerminalAndCancellation() {
        var states = new InMemoryTaskStateRepository();
        var record = new TaskStateRecord();
        record.setRmfTaskId("move.old");
        record.setStatus("completed");
        record.setCategory("go_to_place");
        record.setCancellation(Map.of("labels", List.of("reason=operator")));
        record.setMessage("活动任务已取消");
        record.setUpdatedAtMillis(123L);
        states.upsert(record);
        var restarted = new TaskStateMirror();
        restarted.bindRepositories(states, new InMemoryTaskRecordRepository());
        assertThat(restarted.find("move.old")).isPresent().get().satisfies(event -> {
            assertThat(event.status()).isEqualTo("completed");
            assertThat(event.cancellation()).isEqualTo(record.getCancellation());
            assertThat(event.message()).isEqualTo("活动任务已取消");
            assertThat(event.emittedAtMillis()).isEqualTo(123L);
        });
        assertThat(restarted.list()).isEmpty();
    }

    // 历史查库期间若收到新实时事件，查询必须返回新事件，不能被旧库记录回退覆盖。
    @Test
    void liveEventArrivingDuringHistoricalRead_winsOverStoredRecord() {
        var states = org.mockito.Mockito.mock(TaskStateRecordRepository.class);
        var mirror = new TaskStateMirror();
        mirror.bindRepositories(states, new InMemoryTaskRecordRepository());
        var old = new TaskStateRecord();
        old.setRmfTaskId("move.current");
        old.setStatus("queued");
        org.mockito.Mockito.when(states.findByRmfTaskId("move.current")).thenAnswer(call -> {
            mirror.onTaskStateEvent(TaskStateEvent.withMessage("move.current", "underway", "go_to_place",
                    Map.of(), null, "fleet", "R1", null, "导航中", 200L));
            return Optional.of(old);
        });
        assertThat(mirror.find("move.current")).isPresent().get()
                .satisfies(event -> assertThat(event.status()).isEqualTo("underway"));
    }

    // 状态事件携带机器人：robot_code 回写自动分配的任务记录（已有值不覆盖），状态镜像同步落库
    @Test
    void eventWithRobot_writesBackRobotCodeToRecord() {
        TaskStateMirror mirror = new TaskStateMirror();
        InMemoryTaskRecordRepository taskRecords = new InMemoryTaskRecordRepository();
        InMemoryTaskStateRepository states = new InMemoryTaskStateRepository();
        mirror.bindRepositories(states, taskRecords);

        // 自动分配任务：受理时 robot_code 为空，rmf_task_id 分配后回填
        TaskRecord record = new TaskRecord();
        record.setSchedulerRequestId("req-1");
        record.setBusinessTaskId("task-1");
        record.setCategory("charge");
        record.setRmfTaskId("charge.abc");
        taskRecords.save(record);

        mirror.onTaskStateEvent(TaskStateEvent.withMessage("charge.abc", "underway", "charge",
                Map.of(), null, "RG001", "R001", "req-1", "开始执行", System.currentTimeMillis()));

        assertThat(taskRecords.findByRmfTaskId("charge.abc"))
                .hasValueSatisfying(updated -> assertThat(updated.getRobotCode()).isEqualTo("R001"));
        assertThat(states.findByRmfTaskId("charge.abc")).isPresent();

        // 已有 robot_code 的记录不被覆盖（指定机器人任务）
        mirror.onTaskStateEvent(TaskStateEvent.withMessage("charge.abc", "underway", "charge",
                Map.of(), null, "RG001", "R999", "req-1", "进度更新", System.currentTimeMillis()));
        assertThat(taskRecords.findByRmfTaskId("charge.abc"))
                .hasValueSatisfying(updated -> assertThat(updated.getRobotCode()).isEqualTo("R001"));
    }

    private static class InMemoryTaskStateRepository implements TaskStateRecordRepository {
        private final Map<String, TaskStateRecord> byTaskId = new ConcurrentHashMap<>();

        @Override
        public void upsert(TaskStateRecord record) {
            byTaskId.put(record.getRmfTaskId(), record);
        }

        @Override
        public List<TaskStateRecord> findPage(String robotCode, String status, String category,
                                              int offset, int limit) {
            return List.copyOf(byTaskId.values());
        }

        @Override
        public long count(String robotCode, String status, String category) {
            return byTaskId.size();
        }

        @Override
        public Optional<TaskStateRecord> findByRmfTaskId(String rmfTaskId) {
            return Optional.ofNullable(byTaskId.get(rmfTaskId));
        }

        @Override
        public int markInterrupted(String message) {
            return 0;
        }
    }
}
