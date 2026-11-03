package com.zioneer.robotscheduler.core.repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 外部动作镜像内存实现（测试与无持久化装配兜底）。
 */
public class InMemoryExternalActivityRepository implements ExternalActivityRepository {

    private final Map<String, ExternalActivityRecord> byCommandId = new ConcurrentHashMap<>();

    @Override
    public boolean existsByRmsCommandId(String rmsCommandId) {
        return byCommandId.containsKey(rmsCommandId);
    }

    @Override
    public void insert(ExternalActivityRecord record) {
        byCommandId.put(record.getRmsCommandId(), record);
    }

    @Override
    public List<ExternalActivityRecord> findPage(String robotCode, String bodyTaskId, int offset, int limit) {
        return byCommandId.values().stream()
                .filter(r -> robotCode == null || robotCode.isBlank() || robotCode.equals(r.getRobotCode()))
                .filter(r -> bodyTaskId == null || bodyTaskId.isBlank() || bodyTaskId.equals(r.getBodyTaskId()))
                .sorted(Comparator.comparing(ExternalActivityRecord::getCreatedAtMillis,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .skip(offset)
                .limit(limit)
                .toList();
    }

    @Override
    public long count(String robotCode, String bodyTaskId) {
        return byCommandId.values().stream()
                .filter(r -> robotCode == null || robotCode.isBlank() || robotCode.equals(r.getRobotCode()))
                .filter(r -> bodyTaskId == null || bodyTaskId.isBlank() || bodyTaskId.equals(r.getBodyTaskId()))
                .count();
    }

    public List<ExternalActivityRecord> all() {
        return new ArrayList<>(byCommandId.values());
    }
}
