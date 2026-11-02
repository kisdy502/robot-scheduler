package com.zioneer.robotscheduler.boot;

import com.zioneer.robotscheduler.core.repository.TaskRecordRepository;
import com.zioneer.robotscheduler.core.repository.TaskStateRecordRepository;
import com.zioneer.robotscheduler.core.task.TaskStateMirror;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 任务状态落库装配：镜像事件 → t_task_state（历史查询/上层观测）。
 *
 * <p>启动对账：重启后内核任务态全部丢失，DB 里遗留的非终态记录（underway/queued）
 * 会成为永久僵尸——任务列表挂"执行中"、取消接口永远 cancel_requested。
 * 启动时统一标记为 failed（中断收口），机器人侧残留任务由各机器人的对账链路自理。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskStatePersistenceBinder {

    private final TaskStateMirror taskStateMirror;
    private final TaskStateRecordRepository taskStateRecordRepository;
    private final TaskRecordRepository taskRecordRepository;

    @PostConstruct
    void bind() {
        taskStateMirror.bindRepositories(taskStateRecordRepository, taskRecordRepository);
        int interrupted = taskStateRecordRepository.markInterrupted("调度服务重启，任务中断（启动对账收口）");
        if (interrupted > 0) {
            log.warn("启动对账：{} 条非终态任务标记为 failed（上次运行中断遗留）", interrupted);
        }
    }
}
