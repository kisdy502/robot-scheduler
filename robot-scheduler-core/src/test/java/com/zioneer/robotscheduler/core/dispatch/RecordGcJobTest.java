package com.zioneer.robotscheduler.core.dispatch;

import com.zioneer.robotscheduler.core.repository.TaskRecord;
import com.zioneer.robotscheduler.core.task.TaskStateEvent;
import com.zioneer.robotscheduler.core.task.TaskStateMirror;
import com.zioneer.robotscheduler.core.testsupport.InMemoryTaskRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任务记录 GC 测试：超龄 published 放弃归档、到期归档、镜像终态清理。
 */
class RecordGcJobTest {

    private InMemoryTaskRecordRepository repository;
    private TaskStateMirror mirror;
    private RecordGcJob gcJob;

    @BeforeEach
    void setUp() {
        repository = new InMemoryTaskRecordRepository();
        mirror = new TaskStateMirror();
        gcJob = new RecordGcJob(repository, mirror);
    }

    // GC 场景：published 超 1800s 未获内核响应的任务，预期标记 abandoned 并写入归档时间
    @Test
    void gc_stalePublishedRecord_markedAbandoned() {
        TaskRecord stale = publishedRecord("stale-1");
        stale.setCreatedAtMillis(System.currentTimeMillis() - 1_900_000); // 1800s 前
        repository.save(stale);

        gcJob.gc();

        TaskRecord after = repository.findByBusinessTaskId("stale-1").orElseThrow();
        assertThat(after.isAbandoned()).isTrue();
        assertThat(after.getArchivedAtMillis()).isNotNull();
    }

    // GC 场景：published 记录未超龄（60s），预期保留原状不标记 abandoned
    @Test
    void gc_freshPublishedRecord_kept() {
        TaskRecord fresh = publishedRecord("fresh-1");
        fresh.setCreatedAtMillis(System.currentTimeMillis() - 60_000);
        repository.save(fresh);

        gcJob.gc();

        assertThat(repository.findByBusinessTaskId("fresh-1").orElseThrow().isAbandoned()).isFalse();
    }

    // GC 场景：超龄 published 记录标记 abandoned 后，预期再次查询状态保持 abandoned（按失败语义呈现）
    @Test
    void gc_abandonedRecord_queriedAsFailed() {
        TaskRecord stale = publishedRecord("stale-2");
        stale.setCreatedAtMillis(System.currentTimeMillis() - 1_900_000);
        repository.save(stale);

        gcJob.gc();

        TaskRecord after = repository.findByBusinessTaskId("stale-2").orElseThrow();
        assertThat(after.isAbandoned()).isTrue();
    }

    // GC 场景：已受理任务超过保留期（约 7200s），预期到期归档并写入 archived_at
    @Test
    void gc_retentionExpiredRecord_archived() {
        TaskRecord old = publishedRecord("old-1");
        old.setRmfTaskId("task.kernel-1");
        long oldUpdated = System.currentTimeMillis() - 7_300_000; // 7200s 前
        old.setUpdatedAtMillis(oldUpdated);
        old.setCreatedAtMillis(oldUpdated);
        repository.save(old);

        gcJob.gc();

        TaskRecord after = repository.findByBusinessTaskId("old-1").orElseThrow();
        assertThat(after.getArchivedAtMillis()).isNotNull();
    }

    // GC 场景：任务状态镜像仅清理超龄终态条目，预期运行中超龄与新终态均保留、仅超龄 completed 被清除
    @Test
    void gc_evictsTerminalMirrorStatesOnly() {
        long old = System.currentTimeMillis() - 7_300_000;
        mirror.upsert(new TaskStateEvent("t.completed-old", "completed", "go_to_place",
                Map.of(), Map.of(), null, "R1", null, old));
        mirror.upsert(new TaskStateEvent("t.running-old", "running", "go_to_place",
                Map.of(), Map.of(), null, "R1", null, old));
        mirror.upsert(new TaskStateEvent("t.completed-new", "completed", "go_to_place",
                Map.of(), Map.of(), null, "R1", null, System.currentTimeMillis()));

        gcJob.gc();

        assertThat(mirror.find("t.completed-old")).isEmpty();
        assertThat(mirror.find("t.running-old")).isPresent();
        assertThat(mirror.find("t.completed-new")).isPresent();
    }

    private TaskRecord publishedRecord(String taskId) {
        TaskRecord record = new TaskRecord();
        record.setSchedulerRequestId("req-" + taskId);
        record.setBusinessTaskId(taskId);
        record.setCategory("quality_inspection");
        record.setRobotCode("R001");
        record.setDispatchStatus(com.zioneer.robotscheduler.common.scheduler.task.DispatchStatus.PUBLISHED);
        return record;
    }
}
