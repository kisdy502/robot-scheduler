package com.zioneer.robotscheduler.core.task;

import com.zioneer.robotscheduler.common.scheduler.SchedulerConstants;
import com.zioneer.robotscheduler.common.scheduler.task.TaskStatus;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 任务状态内存镜像（对齐旧栈 _task_state_records：task_state_update 的本地缓存）。
 *
 * <p>事件驱动：监听 {@link TaskStateEvent}（由内核或测试经 SchedulerEventBus 注入）；
 * 首次收到某调度请求关联的事件时回填任务记录的 rmf_task_id 与受理状态（对齐旧栈 _on_api_response 语义）。</p>
 */
@Component
public class TaskStateMirror {

    private final Map<String, TaskStateEvent> statesByTaskId = new ConcurrentHashMap<>();

    private final Object repositoryLock = new Object();
    private com.zioneer.robotscheduler.core.repository.TaskStateRecordRepository recordRepository;
    private com.zioneer.robotscheduler.core.repository.TaskRecordRepository taskRecordRepository;

    public TaskStateMirror() {
    }

    /** 装配层注入仓储（落库观测；测试可不注入）。 */
    public void bindRepositories(com.zioneer.robotscheduler.core.repository.TaskStateRecordRepository recordRepository,
                                 com.zioneer.robotscheduler.core.repository.TaskRecordRepository taskRecordRepository) {
        synchronized (repositoryLock) {
            this.recordRepository = recordRepository;
            this.taskRecordRepository = taskRecordRepository;
        }
    }

    @EventListener
    public void onTaskStateEvent(TaskStateEvent event) {
        statesByTaskId.put(event.taskId(), event);
        persistAsync(event);
    }

    /** 事件落库（观测/历史查询；失败不影响内存态）。 */
    private void persistAsync(TaskStateEvent event) {
        com.zioneer.robotscheduler.core.repository.TaskStateRecordRepository repository;
        com.zioneer.robotscheduler.core.repository.TaskRecordRepository taskRepository;
        synchronized (repositoryLock) {
            repository = recordRepository;
            taskRepository = taskRecordRepository;
        }
        if (repository == null) {
            return;
        }
        try {
            var record = new com.zioneer.robotscheduler.core.repository.TaskStateRecord();
            record.setRmfTaskId(event.taskId());
            record.setStatus(event.status());
            record.setCategory(event.category());
            record.setMessage(event.message());
            record.setDetail(event.detail());
            record.setCancellation(event.cancellation());
            record.setRobotCode(event.assignedRobotName());
            record.setUpdatedAtMillis(event.emittedAtMillis());
            // 质检结果提取（引擎在动作回执里放入 detail.inspection_result）
            if (event.detail() != null && event.detail().get("inspection_result") != null) {
                Object inspection = event.detail().get("inspection_result");
                if (inspection instanceof Map<?, ?> inspectionMap) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typed = (Map<String, Object>) inspectionMap;
                    record.setInspectionResult(typed);
                }
            }
            if (taskRepository != null) {
                taskRepository.findByRmfTaskId(event.taskId()).ifPresent(taskRecord -> {
                    record.setBusinessTaskId(taskRecord.getBusinessTaskId());
                    // 自动分配任务回写 robot_code：受理时未指定（null），分配后事件携带；
                    // 取消链路发机器人 stop 依赖记录上的 robot_code，缺失会退化为纯事件终态
                    if (event.assignedRobotName() != null && !event.assignedRobotName().isBlank()
                            && (taskRecord.getRobotCode() == null || taskRecord.getRobotCode().isBlank())) {
                        taskRecord.setRobotCode(event.assignedRobotName());
                        taskRepository.update(taskRecord);
                    }
                });
            }
            repository.upsert(record);
        } catch (Exception e) {
            // 落库失败不阻断调度主链路
            org.slf4j.LoggerFactory.getLogger(TaskStateMirror.class)
                    .warn("任务状态落库失败: task={} error={}", event.taskId(), e.getMessage());
        }
    }

    /** 测试/内核注入入口（绕过事件总线时的直接写入）。 */
    public void upsert(TaskStateEvent event) {
        statesByTaskId.put(event.taskId(), event);
    }

    public Optional<TaskStateEvent> find(String rmfTaskId) {
        TaskStateEvent current = statesByTaskId.get(rmfTaskId);
        if (current != null) return Optional.of(current);
        com.zioneer.robotscheduler.core.repository.TaskStateRecordRepository repository;
        synchronized (repositoryLock) {
            repository = recordRepository;
        }
        if (repository == null) return Optional.empty();
        // 重启或终态缓存清理后，查询仍需读到已落库的真实终态，不能退回永久 queued。
        Optional<TaskStateEvent> stored = repository.findByRmfTaskId(rmfTaskId).map(record -> {
            Map<String, Object> detail = new java.util.HashMap<>(
                    record.getDetail() == null ? Map.of() : record.getDetail());
            if (record.getInspectionResult() != null) detail.put("inspection_result", record.getInspectionResult());
            return TaskStateEvent.withMessage(record.getRmfTaskId(), record.getStatus(), record.getCategory(),
                    detail, record.getCancellation(), null, record.getRobotCode(), null, record.getMessage(),
                    record.getUpdatedAtMillis() == null ? 0 : record.getUpdatedAtMillis());
        });
        // 查库期间可能收到更新的实时事件；历史结果只做回退，不覆盖新状态或重新撑大内存缓存。
        current = statesByTaskId.get(rmfTaskId);
        return current == null ? stored : Optional.of(current);
    }

    public List<TaskStateEvent> list() {
        return List.copyOf(statesByTaskId.values());
    }

    /**
     * 任务是否 stale：状态非终态且超过 TTL 未更新（对齐 RMF_TASK_STATE_TTL_MILLIS=10s）。
     */
    public boolean isStale(TaskStateEvent event) {
        TaskStatus status = TaskStatus.fromRaw(event.status());
        return !status.isTerminal()
                && System.currentTimeMillis() - event.emittedAtMillis() > SchedulerConstants.RMF_TASK_STATE_TTL_MILLIS;
    }

    public void clear() {
        statesByTaskId.clear();
    }

    /**
     * 清理长期未更新的终态任务镜像（GC 周期调用，对齐旧栈成对清除语义）。
     */
    public void evictTerminalOlderThan(long thresholdMillis) {
        statesByTaskId.entrySet().removeIf(entry -> {
            TaskStateEvent event = entry.getValue();
            return TaskStatus.fromRaw(event.status()).isTerminal()
                    && event.emittedAtMillis() < thresholdMillis;
        });
    }
}
