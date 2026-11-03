package com.zioneer.robotscheduler.core.repository;

import com.zioneer.robotscheduler.core.traffic.TrafficSnapshot;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** 独立构造内核时的隔离存储；不注册为 Spring Bean，正式装配必须提供持久化实现。 */
public class InMemoryRobotRuntimeRepository implements RobotRuntimeRepository {
    private final Map<String, RobotUsageSnapshot> usages = new ConcurrentHashMap<>();
    private final Map<String, TrafficSnapshot> traffic = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> sequences = new ConcurrentHashMap<>();
    private record Execution(String taskId, String mapName, String mapVersion) { }
    private final Map<String, Execution> executing = new ConcurrentHashMap<>();

    @Override
    public List<RobotUsageSnapshot> findUsages() { return List.copyOf(usages.values()); }
    @Override
    public void saveUsage(RobotUsageSnapshot snapshot) { usages.put(snapshot.robotCode(), snapshot); }
    @Override
    public List<TrafficSnapshot> findTraffic() {
        Map<String, TrafficSnapshot> recovered = new java.util.HashMap<>(traffic);
        executing.forEach((robot, execution) -> {
            TrafficSnapshot state = recovered.getOrDefault(robot, new TrafficSnapshot(robot,
                    java.util.Set.of(), java.util.Set.of(), null, null, false, false, 0,
                    execution.mapName(), execution.mapVersion()));
            recovered.put(robot, new TrafficSnapshot(robot, state.segmentIds(), state.waypointIds(),
                    state.dwellWaypointId(), state.dwellSegmentId(), true, false, state.acquiredAtMillis(),
                    execution.mapName(), execution.mapVersion()));
        });
        return List.copyOf(recovered.values());
    }
    @Override
    public void saveTraffic(TrafficSnapshot snapshot) {
        TrafficSnapshot old = traffic.put(snapshot.robotCode(), snapshot);
        if (old != null && old.awaitingStop() && !snapshot.awaitingStop()) executing.remove(snapshot.robotCode());
    }
    @Override
    public int nextCommandId(String robotCode) {
        return sequences.computeIfAbsent(robotCode, ignored -> new AtomicInteger()).incrementAndGet();
    }
    @Override
    public void beginSchedulerExecution(String robot, String taskId, String mapName, String mapVersion) {
        executing.put(robot, new Execution(taskId, mapName, mapVersion));
    }
    @Override
    public void finishSchedulerExecution(String robot, String taskId) {
        executing.computeIfPresent(robot, (key, current) -> taskId.equals(current.taskId()) ? null : current);
    }
}
