package com.zioneer.robotscheduler.core.traffic;

import com.zioneer.robotscheduler.core.map.MapManager;
import com.zioneer.robotscheduler.core.repository.RobotRuntimeRepository;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** 单实例交通正本：写盘成功才确认申请/释放，提交结果不明时保守冻结候选资源，不能无记录放车。 */
@lombok.extern.slf4j.Slf4j
public class PersistentTrafficRegistry implements TrafficRegistry {
    private final InMemoryTrafficRegistry delegate = new InMemoryTrafficRegistry();
    private final RobotRuntimeRepository repository;
    private final MapManager maps;
    private TrafficEventSink eventSink = TrafficEventSink.NOOP;
    private final java.util.List<Audit> pendingEvents = new java.util.ArrayList<>();
    private record Audit(String robot, String action, List<String> resources, String detail) { }

    public PersistentTrafficRegistry(RobotRuntimeRepository repository, MapManager maps) {
        this.repository = repository;
        this.maps = maps;
        delegate.setEventSink((robot, action, resources, detail) ->
                pendingEvents.add(new Audit(robot, action, resources, detail)));
        for (TrafficSnapshot state : repository.findTraffic()) {
            if (!state.hasResources() && !state.awaitingStop()) continue;
            maps.requireRecoveryMap(state.mapName(), state.mapVersion());
            // 不能信任上次进程的 stop 接受标记；必须重新停止、重新采集连续位置帧。
            delegate.restoreState(new TrafficSnapshot(state.robotCode(), state.segmentIds(), state.waypointIds(),
                    state.dwellWaypointId(), state.dwellSegmentId(), state.needsStopConfirmation(), false,
                    state.acquiredAtMillis(), state.mapName(), state.mapVersion()));
        }
    }

    public synchronized void setEventSink(TrafficEventSink sink) {
        eventSink = sink == null ? TrafficEventSink.NOOP : sink;
    }

    private <T> T change(String robot, Supplier<T> action) {
        TrafficSnapshot before = delegate.stateOf(robot);
        TrafficSnapshot candidate = before;
        boolean storageAttempted = false;
        pendingEvents.clear();
        try {
            T result = action.get();
            TrafficSnapshot after = delegate.stateOf(robot);
            candidate = after;
            if (!before.equals(after)) {
                var map = maps.activeSnapshot().orElseThrow(() ->
                        new IllegalStateException("交通占用持久化需要已加载的地图版本"));
                storageAttempted = true;
                repository.saveTraffic(new TrafficSnapshot(robot, after.segmentIds(), after.waypointIds(),
                        after.dwellWaypointId(), after.dwellSegmentId(), after.awaitingStop(), after.stopAccepted(),
                        after.acquiredAtMillis(), map.mapName(), map.version()));
            }
            for (Audit event : pendingEvents) {
                try {
                    eventSink.record(event.robot(), event.action(), event.resources(), event.detail());
                } catch (RuntimeException failure) {
                    // 审计不是运行正本，写盘已经成功，审计故障不能回滚已确认的占用。
                    log.warn("交通审计失败: robot_code={} action={} error={}",
                            event.robot(), event.action(), failure.getMessage());
                }
            }
            return result;
        } catch (RuntimeException failure) {
            delegate.restoreState(before);
            if (storageAttempted) {
                // 连接在提交时断开，结果可能不确定；保守保留新旧资源并集，确认停止并成功落库后才收缩。
                Set<String> segments = new java.util.HashSet<>(before.segmentIds());
                segments.addAll(candidate.segmentIds());
                Set<String> points = new java.util.HashSet<>(before.waypointIds());
                points.addAll(candidate.waypointIds());
                if (before.dwellWaypointId() != null) points.add(before.dwellWaypointId());
                if (candidate.dwellWaypointId() != null) points.add(candidate.dwellWaypointId());
                if (before.dwellSegmentId() != null) segments.add(before.dwellSegmentId());
                if (candidate.dwellSegmentId() != null) segments.add(candidate.dwellSegmentId());
                delegate.restoreState(new TrafficSnapshot(robot, segments, points, before.dwellWaypointId(),
                        before.dwellSegmentId(), true, false, candidate.acquiredAtMillis(), null, null));
                log.error("运行正本写入失败，冻结候选资源等待停止确认: robot_code={}", robot, failure);
            }
            throw failure;
        } finally {
            pendingEvents.clear();
        }
    }

    @Override
    public synchronized boolean tryAcquire(String robot, List<String> segments, Set<String> points, Duration lease) {
        return change(robot, () -> delegate.tryAcquire(robot, segments, points, lease));
    }
    @Override
    public synchronized void releaseBehind(String robot, String point) {
        change(robot, () -> { delegate.releaseBehind(robot, point); return null; });
    }
    @Override
    public synchronized void releaseSegments(String robot, List<String> segments) {
        change(robot, () -> { delegate.releaseSegments(robot, segments); return null; });
    }
    @Override
    public synchronized void releaseAll(String robot) {
        change(robot, () -> { delegate.releaseAll(robot); return null; });
    }
    @Override
    public synchronized void releaseAllAndDwell(String robot, String point) {
        change(robot, () -> { delegate.releaseAllAndDwell(robot, point); return null; });
    }
    @Override
    public synchronized void updateDwell(String robot, String point, String segment) {
        change(robot, () -> { delegate.updateDwell(robot, point, segment); return null; });
    }
    @Override
    public synchronized boolean retainUntilStopped(String robot, String reason) {
        return change(robot, () -> delegate.retainUntilStopped(robot, reason));
    }
    @Override
    public synchronized void markStopAccepted(String robot) {
        change(robot, () -> { delegate.markStopAccepted(robot); return null; });
    }
    @Override
    public synchronized boolean confirmStopped(String robot, String point, String segment) {
        return change(robot, () -> delegate.confirmStopped(robot, point, segment));
    }
    @Override
    public synchronized boolean isAwaitingStop(String robot) { return delegate.isAwaitingStop(robot); }
    @Override
    public synchronized boolean isStopAccepted(String robot) { return delegate.isStopAccepted(robot); }
    @Override
    public synchronized Set<String> occupiedSegments() { return delegate.occupiedSegments(); }
    @Override
    public synchronized Set<String> occupiedWaypoints() { return delegate.occupiedWaypoints(); }
    @Override
    public synchronized Set<String> occupiedSegmentsExcluding(String robot) {
        return delegate.occupiedSegmentsExcluding(robot);
    }
    @Override
    public synchronized Set<String> occupiedWaypointsExcluding(String robot) {
        return delegate.occupiedWaypointsExcluding(robot);
    }
    @Override
    public synchronized Map<String, OccupiedResources> occupationSnapshot() { return delegate.occupationSnapshot(); }
    @Override
    public synchronized String dwellOwner(String point) { return delegate.dwellOwner(point); }
    @Override
    public synchronized String dwellSegmentOwner(String segment) { return delegate.dwellSegmentOwner(segment); }
}
