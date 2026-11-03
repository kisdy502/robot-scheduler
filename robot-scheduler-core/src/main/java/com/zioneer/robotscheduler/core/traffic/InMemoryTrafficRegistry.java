package com.zioneer.robotscheduler.core.traffic;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内默认交通占用实现（单实例部署的正本；多实例场景由 Redis 实现替换）。
 *
 * <p>并发说明：单锁保证申请/释放原子性即可满足本项目机器人规模（&lt;50），
 * 避免分段锁复杂度。路径段占用即天然互斥（同一窄道两台机器人不会同时占用同一段）。</p>
 */
public class InMemoryTrafficRegistry implements TrafficRegistry {

    private final Map<String, OccupiedResources> occupationByRobot = new ConcurrentHashMap<>();
    private final Map<String, String> segmentOwner = new ConcurrentHashMap<>();
    private final Map<String, String> waypointOwner = new ConcurrentHashMap<>();

    /** 静止机器人驻留点：robotCode → 吸附 waypoint id（与任务占用分开管理）。 */
    private final Map<String, String> dwellWaypointByRobot = new ConcurrentHashMap<>();

    /** 静止机器人驻留段：robotCode → 吸附 segment id（机器人停在路段中间的障碍化）。 */
    private final Map<String, String> dwellSegmentByRobot = new ConcurrentHashMap<>();

    /** 停止确认标记：false=停止请求尚未送达，true=已接受、仍待位置帧确认。 */
    private final Map<String, Boolean> stopHolds = new ConcurrentHashMap<>();

    /** 审计出口（可选，装配层注入）。 */
    private volatile TrafficEventSink eventSink = TrafficEventSink.NOOP;

    public void setEventSink(TrafficEventSink eventSink) {
        this.eventSink = eventSink == null ? TrafficEventSink.NOOP : eventSink;
    }

    /** 为持久化事务提供不可变快照；与观测快照不同，不合并预约和驻留。 */
    synchronized TrafficSnapshot stateOf(String robot) {
        OccupiedResources occupied = occupationByRobot.get(robot);
        return new TrafficSnapshot(robot, occupied == null ? Set.of() : occupied.segmentIds(),
                occupied == null ? Set.of() : occupied.waypointIds(), dwellWaypointByRobot.get(robot),
                dwellSegmentByRobot.get(robot), isAwaitingStop(robot), isStopAccepted(robot),
                occupied == null ? 0 : occupied.acquiredAtMillis(), null, null);
    }

    /** 仅由外层持久化锁在恢复或写入失败回滚时调用，不触发审计和停止确认门禁。 */
    synchronized void restoreState(TrafficSnapshot state) {
        String robot = state.robotCode();
        OccupiedResources previous = occupationByRobot.remove(robot);
        if (previous != null) {
            previous.segmentIds().forEach(segment -> segmentOwner.remove(segment, robot));
            previous.waypointIds().forEach(waypoint -> waypointOwner.remove(waypoint, robot));
        }
        dwellWaypointByRobot.remove(robot);
        dwellSegmentByRobot.remove(robot);
        stopHolds.remove(robot);
        if (!state.segmentIds().isEmpty() || !state.waypointIds().isEmpty()) {
            occupationByRobot.put(robot, new OccupiedResources(new HashSet<>(state.segmentIds()),
                    new HashSet<>(state.waypointIds()), state.acquiredAtMillis()));
            state.segmentIds().forEach(segment -> segmentOwner.put(segment, robot));
            state.waypointIds().forEach(waypoint -> waypointOwner.put(waypoint, robot));
        }
        if (state.dwellWaypointId() != null) dwellWaypointByRobot.put(robot, state.dwellWaypointId());
        if (state.dwellSegmentId() != null) dwellSegmentByRobot.put(robot, state.dwellSegmentId());
        if (state.awaitingStop()) stopHolds.put(robot, state.stopAccepted());
    }

    private void audit(String robotCode, String action, List<String> resourceIds) {
        audit(robotCode, action, resourceIds, null);
    }

    private void audit(String robotCode, String action, List<String> resourceIds, String detail) {
        try {
            eventSink.record(robotCode, action, resourceIds, detail);
        } catch (Exception ignored) {
            // 审计失败不阻断交通主链路
        }
    }

    @Override
    public synchronized boolean tryAcquire(String robotCode, List<String> segmentIds,
                                           Set<String> waypointIds, Duration lease) {
        if (isAwaitingStop(robotCode)) return false;
        for (String segment : segmentIds) {
            if (isOwnedByOther(segmentOwner.get(segment), robotCode)) {
                return false;
            }
            // 驻留段冲突：路径段被其他静止机器人占据（场景：故障/停靠在路段中间）
            String dwellSegOwner = dwellSegmentOwner(segment);
            if (dwellSegOwner != null && !dwellSegOwner.equals(robotCode)) {
                return false;
            }
        }
        for (String waypoint : waypointIds) {
            if (isOwnedByOther(waypointOwner.get(waypoint), robotCode)) {
                return false;
            }
            // 驻留点冲突：目标点是其他机器人的静止驻留点（场景：必经/目标点被静止机器人堵住）
            String dwellOwner = dwellOwner(waypoint);
            if (dwellOwner != null && !dwellOwner.equals(robotCode)) {
                return false;
            }
        }
        segmentIds.forEach(segment -> segmentOwner.put(segment, robotCode));
        waypointIds.forEach(waypoint -> waypointOwner.put(waypoint, robotCode));
        occupationByRobot.merge(robotCode,
                new OccupiedResources(new HashSet<>(segmentIds), new HashSet<>(waypointIds),
                        System.currentTimeMillis()),
                (oldRes, newRes) -> {
                    oldRes.segmentIds().addAll(newRes.segmentIds());
                    oldRes.waypointIds().addAll(newRes.waypointIds());
                    return oldRes;
                });
        // 审计：占用段 + 占用点分开记录（一次性锁定整条路线多点多段在审计里完整可见）
        if (!segmentIds.isEmpty()) {
            audit(robotCode, "ACQUIRE_SEGMENT", segmentIds);
        }
        if (!waypointIds.isEmpty()) {
            audit(robotCode, "ACQUIRE_WAYPOINT", List.copyOf(waypointIds));
        }
        return true;
    }

    @Override
    public synchronized void releaseBehind(String robotCode, String reachedWaypointId) {
        if (isAwaitingStop(robotCode)) return;
        OccupiedResources resources = occupationByRobot.get(robotCode);
        if (resources == null) {
            return;
        }
        if (resources.waypointIds().remove(reachedWaypointId)) {
            waypointOwner.remove(reachedWaypointId, robotCode);
            audit(robotCode, "RELEASE_WAYPOINT", List.of(reachedWaypointId));
        }
    }

    @Override
    public synchronized void releaseSegments(String robotCode, List<String> segmentIds) {
        if (isAwaitingStop(robotCode)) return;
        OccupiedResources resources = occupationByRobot.get(robotCode);
        if (resources == null || segmentIds == null || segmentIds.isEmpty()) {
            return;
        }
        for (String segment : segmentIds) {
            if (resources.segmentIds().remove(segment)) {
                segmentOwner.remove(segment, robotCode);
            }
        }
        audit(robotCode, "RELEASE_SEGMENT", segmentIds);
    }

    @Override
    public synchronized void releaseAll(String robotCode) {
        if (isAwaitingStop(robotCode)) return;
        doReleaseAll(robotCode);
    }

    @Override
    public synchronized void releaseAllAndDwell(String robotCode, String dwellWaypointId) {
        if (isAwaitingStop(robotCode)) return;
        doReleaseAll(robotCode);
        if (dwellWaypointId == null || dwellWaypointId.isBlank()) {
            return;
        }
        putDwellWaypoint(robotCode, dwellWaypointId);
    }

    @Override
    public synchronized boolean retainUntilStopped(String robotCode, String reason) {
        if (stopHolds.putIfAbsent(robotCode, false) != null) return false;
        audit(robotCode, "HOLD_UNTIL_STOPPED", List.of(), reason);
        return true;
    }

    @Override
    public boolean isAwaitingStop(String robotCode) {
        return robotCode != null && stopHolds.containsKey(robotCode);
    }

    @Override
    public synchronized void markStopAccepted(String robotCode) {
        stopHolds.computeIfPresent(robotCode, (robot, accepted) -> true);
    }

    @Override
    public boolean isStopAccepted(String robotCode) {
        return Boolean.TRUE.equals(stopHolds.get(robotCode));
    }

    @Override
    public synchronized boolean confirmStopped(String robotCode, String waypointId, String segmentId) {
        if (!isStopAccepted(robotCode) || (waypointId == null && segmentId == null)) return false;
        stopHolds.remove(robotCode);
        // 同一把锁内先接续物理驻留，再释放未来预约，其他机器人不会拿到空档。
        updateDwell(robotCode, waypointId, segmentId);
        doReleaseAll(robotCode);
        audit(robotCode, "STOP_CONFIRMED", List.of());
        return true;
    }

    private void doReleaseAll(String robotCode) {
        OccupiedResources resources = occupationByRobot.remove(robotCode);
        if (resources == null) {
            return;
        }
        resources.segmentIds().forEach(segment -> segmentOwner.remove(segment, robotCode));
        resources.waypointIds().forEach(waypoint -> waypointOwner.remove(waypoint, robotCode));
        if (!resources.segmentIds().isEmpty()) {
            audit(robotCode, "RELEASE_SEGMENT", resources.segmentIds().stream().toList());
        }
        if (!resources.waypointIds().isEmpty()) {
            audit(robotCode, "RELEASE_WAYPOINT", resources.waypointIds().stream().toList());
        }
    }

    /** 驻留登记（含同点冲突防御审计：多车同点 = 叠车已成事实，大声告警不静默）。 */
    private void putDwellWaypoint(String robotCode, String waypointId) {
        String existingOwner = dwellOwner(waypointId);
        if (existingOwner != null && !existingOwner.equals(robotCode)) {
            // 防御：不拒绝登记（拒绝会让第二台车对交通系统隐形 = 欠阻断更危险），
            // 保留双方占用（过阻断方向安全）+ 冲突审计暴露，等让位机制处理
            audit(robotCode, "DWELL_CONFLICT", List.of(waypointId),
                    "conflict with " + existingOwner + "（多车同点驻留，物理重叠）");
        }
        dwellWaypointByRobot.put(robotCode, waypointId);
    }

    @Override
    public Set<String> occupiedSegments() {
        java.util.Set<String> all = new java.util.HashSet<>(segmentOwner.keySet());
        all.addAll(dwellSegmentByRobot.values());
        return Set.copyOf(all);
    }

    @Override
    public Set<String> occupiedWaypoints() {
        java.util.Set<String> all = new java.util.HashSet<>(waypointOwner.keySet());
        all.addAll(dwellWaypointByRobot.values());
        return Set.copyOf(all);
    }

    @Override
    public Set<String> occupiedSegmentsExcluding(String robotCode) {
        java.util.Set<String> all = new java.util.HashSet<>();
        segmentOwner.forEach((segment, owner) -> {
            if (!owner.equals(robotCode)) {
                all.add(segment);
            }
        });
        dwellSegmentByRobot.forEach((robot, segment) -> {
            if (!robot.equals(robotCode)) {
                all.add(segment);
            }
        });
        return Set.copyOf(all);
    }

    @Override
    public Set<String> occupiedWaypointsExcluding(String robotCode) {
        java.util.Set<String> all = new java.util.HashSet<>();
        waypointOwner.forEach((waypoint, owner) -> {
            if (!owner.equals(robotCode)) {
                all.add(waypoint);
            }
        });
        dwellWaypointByRobot.forEach((robot, waypoint) -> {
            if (!robot.equals(robotCode)) {
                all.add(waypoint);
            }
        });
        return Set.copyOf(all);
    }

    @Override
    public synchronized void updateDwell(String robotCode, String waypointId, String segmentId) {
        if (isAwaitingStop(robotCode)) return;
        String oldWaypoint = dwellWaypointByRobot.get(robotCode);
        String oldSegment = dwellSegmentByRobot.get(robotCode);
        String newWaypoint = waypointId == null || waypointId.isBlank() ? null : waypointId;
        String newSegment = segmentId == null || segmentId.isBlank() ? null : segmentId;
        if (!java.util.Objects.equals(oldWaypoint, newWaypoint)) {
            if (oldWaypoint != null) {
                audit(robotCode, "RELEASE_WAYPOINT", List.of(oldWaypoint), "dwell");
            }
            if (newWaypoint != null) {
                putDwellWaypoint(robotCode, newWaypoint);
                audit(robotCode, "ACQUIRE_WAYPOINT", List.of(newWaypoint), "dwell");
            }
        }
        if (!java.util.Objects.equals(oldSegment, newSegment)) {
            if (oldSegment != null) {
                audit(robotCode, "RELEASE_SEGMENT", List.of(oldSegment), "dwell");
            }
            if (newSegment != null) {
                audit(robotCode, "ACQUIRE_SEGMENT", List.of(newSegment), "dwell");
            }
        }
        if (newWaypoint == null) {
            dwellWaypointByRobot.remove(robotCode);
        } else {
            dwellWaypointByRobot.put(robotCode, newWaypoint);
        }
        if (newSegment == null) {
            dwellSegmentByRobot.remove(robotCode);
        } else {
            dwellSegmentByRobot.put(robotCode, newSegment);
        }
    }

    /** 驻留点占用者（无则 null）。 */
    public String dwellOwner(String waypointId) {
        for (var entry : dwellWaypointByRobot.entrySet()) {
            if (waypointId.equals(entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** 驻留段占用者（无则 null）。 */
    public String dwellSegmentOwner(String segmentId) {
        for (var entry : dwellSegmentByRobot.entrySet()) {
            if (segmentId.equals(entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    @Override
    public synchronized Map<String, OccupiedResources> occupationSnapshot() {
        Map<String, OccupiedResources> snapshot = new HashMap<>();
        // 任务占用（可变集合重建：后续驻留 merge 需要写入，Set.copyOf 不可变会抛 UnsupportedOperationException）
        occupationByRobot.forEach((robot, res) -> snapshot.put(robot,
                new OccupiedResources(new HashSet<>(res.segmentIds()), new HashSet<>(res.waypointIds()),
                        res.acquiredAtMillis())));
        // 驻留占用（静止机器人的点/段障碍，观测可见；与任务占用合并展示）
        dwellWaypointByRobot.forEach((robot, wp) -> snapshot.merge(robot,
                new OccupiedResources(new HashSet<>(), new HashSet<>(Set.of(wp)),
                        System.currentTimeMillis()),
                (oldRes, add) -> {
                    oldRes.waypointIds().addAll(add.waypointIds());
                    return oldRes;
                }));
        dwellSegmentByRobot.forEach((robot, seg) -> snapshot.merge(robot,
                new OccupiedResources(new HashSet<>(Set.of(seg)), new HashSet<>(),
                        System.currentTimeMillis()),
                (oldRes, add) -> {
                    oldRes.segmentIds().addAll(add.segmentIds());
                    return oldRes;
                }));
        // 即使没有路线预约，停止确认也必须对切图门禁可见。
        stopHolds.keySet().forEach(robot -> snapshot.putIfAbsent(robot,
                new OccupiedResources(new HashSet<>(), new HashSet<>(), System.currentTimeMillis())));
        return Collections.unmodifiableMap(snapshot);
    }

    private boolean isOwnedByOther(String owner, String robotCode) {
        return owner != null && !owner.equals(robotCode);
    }
}
