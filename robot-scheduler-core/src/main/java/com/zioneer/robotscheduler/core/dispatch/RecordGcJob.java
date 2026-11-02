package com.zioneer.robotscheduler.core.dispatch;

import com.zioneer.robotscheduler.common.scheduler.SchedulerConstants;
import com.zioneer.robotscheduler.core.repository.TaskRecord;
import com.zioneer.robotscheduler.core.repository.TaskRecordRepository;
import com.zioneer.robotscheduler.core.task.TaskStateMirror;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 任务记录 GC（对齐旧栈 _evict_and_archive：60s 周期）：
 * 非终态且超龄无内核信号（1800s）→ abandoned 归档（查询接口隐藏）；
 * 长期未更新（7200s）→ 归档标记；镜像中终态任务同步清理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecordGcJob {

    private final TaskRecordRepository taskRecordRepository;
    private final TaskStateMirror taskStateMirror;

    /** GC 入口（60s 周期调度）：放弃超龄 published 记录 → 归档到期记录 → 清理终态镜像。 */
    @Scheduled(fixedDelayString = "${robot.scheduler.gc.interval-millis:60000}")
    public void gc() {
        long now = System.currentTimeMillis();
        abandonStalePublished(now);
        archiveExpired(now);
        taskStateMirror.evictTerminalOlderThan(
                now - SchedulerConstants.RECORD_RETENTION_SECONDS * 1000);
    }

    private void abandonStalePublished(long now) {
        long olderThan = now - (long)
                (SchedulerConstants.DEFAULT_TASK_ABANDON_SECONDS * 1000);
        List<TaskRecord> candidates = taskRecordRepository.findAbandonCandidates(olderThan, 100);
        for (TaskRecord record : candidates) {
            record.setAbandoned(true);
            record.setArchivedAtMillis(now);
            record.setUpdatedAtMillis(now);
            taskRecordRepository.update(record);
            log.warn("任务记录超龄放弃归档: task={} requestId={} abandonAfter={}s",
                    record.getBusinessTaskId(), record.getSchedulerRequestId(),
                    (long) SchedulerConstants.DEFAULT_TASK_ABANDON_SECONDS);
        }
    }

    private void archiveExpired(long now) {
        long olderThan = now - SchedulerConstants.RECORD_RETENTION_SECONDS * 1000;
        List<TaskRecord> expired = taskRecordRepository.findRetentionExpired(olderThan, 200);
        for (TaskRecord record : expired) {
            record.setArchivedAtMillis(now);
            record.setUpdatedAtMillis(now);
            taskRecordRepository.update(record);
            log.info("任务记录到期归档: task={} requestId={}", record.getBusinessTaskId(),
                    record.getSchedulerRequestId());
        }
    }
}
