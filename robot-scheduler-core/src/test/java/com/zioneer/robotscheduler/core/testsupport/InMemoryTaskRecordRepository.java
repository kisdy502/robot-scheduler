package com.zioneer.robotscheduler.core.testsupport;

import com.zioneer.robotscheduler.core.repository.TaskRecord;
import com.zioneer.robotscheduler.core.repository.TaskRecordRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * 内存版任务记录仓储（测试夹具：模拟旧栈 bridge._records 的行为，包括"终态"近似判定）。
 */
public class InMemoryTaskRecordRepository implements TaskRecordRepository {

    private final Map<String, TaskRecord> recordsByRequestId = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(1);

    @Override
    public void save(TaskRecord record) {
        if (record.getId() == null) {
            record.setId(idGenerator.getAndIncrement());
        }
        recordsByRequestId.put(record.getSchedulerRequestId(), record);
    }

    @Override
    public void update(TaskRecord record) {
        recordsByRequestId.put(record.getSchedulerRequestId(), record);
    }

    @Override
    public Optional<TaskRecord> findBySchedulerRequestId(String schedulerRequestId) {
        return Optional.ofNullable(recordsByRequestId.get(schedulerRequestId));
    }

    @Override
    public Optional<TaskRecord> findByBusinessTaskId(String businessTaskId) {
        return latest(record -> businessTaskId.equals(record.getBusinessTaskId()));
    }

    @Override
    public Optional<TaskRecord> findByRmfTaskId(String rmfTaskId) {
        return latest(record -> rmfTaskId.equals(record.getRmfTaskId()));
    }

    @Override
    public List<TaskRecord> findByBusinessTaskIds(List<String> businessTaskIds) {
        return recordsByRequestId.values().stream()
                .filter(record -> businessTaskIds.contains(record.getBusinessTaskId()))
                .toList();
    }

    @Override
    public List<TaskRecord> findRecentByRobotCode(String robotCode, int limit) {
        return recordsByRequestId.values().stream()
                .filter(record -> robotCode.equals(record.getRobotCode()))
                .sorted(Comparator.comparing(TaskRecord::getId).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public List<TaskRecord> findAbandonCandidates(long createdBeforeMillis, int limit) {
        return recordsByRequestId.values().stream()
                .filter(record -> "published".equals(record.getDispatchStatus().wireName()))
                .filter(record -> record.getRmfTaskId() == null)
                .filter(record -> !record.isAbandoned())
                .filter(record -> record.getCreatedAtMillis() != null
                        && record.getCreatedAtMillis() < createdBeforeMillis)
                .sorted(Comparator.comparing(TaskRecord::getId))
                .limit(limit)
                .toList();
    }

    @Override
    public List<TaskRecord> findRetentionExpired(long updatedBeforeMillis, int limit) {
        return recordsByRequestId.values().stream()
                .filter(record -> record.getArchivedAtMillis() == null)
                .filter(record -> record.getUpdatedAtMillis() != null
                        && record.getUpdatedAtMillis() < updatedBeforeMillis)
                .sorted(Comparator.comparing(TaskRecord::getId))
                .limit(limit)
                .toList();
    }

    public List<TaskRecord> all() {
        return List.copyOf(recordsByRequestId.values());
    }

    public void clear() {
        recordsByRequestId.clear();
    }

    private Optional<TaskRecord> latest(Predicate<TaskRecord> filter) {
        return recordsByRequestId.values().stream()
                .filter(filter)
                .max(Comparator.comparing(TaskRecord::getId));
    }
}
