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
