package com.zioneer.robotscheduler.core.traffic;

import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/** 等待中的候选路线/段入口；资源占用者在检测时重新解析，避免沿用已释放的旧关系。 */
@Component
public class WaitIntentRegistry {
    private static final long INTENT_TTL_MILLIS = 120_000;
    private final Map<String, WaitIntent> intentsByRobot = new ConcurrentHashMap<>();

    public void record(String robotCode, List<String> segments) {
        record(robotCode, null, segments, Set.of());
    }

    public void record(String robotCode, List<String> segments, Set<String> waypoints) {
        record(robotCode, null, segments, waypoints);
    }

    public void record(String robotCode, String taskId, List<String> segments, Set<String> waypoints) {
        intentsByRobot.put(robotCode, new WaitIntent(robotCode, taskId, List.copyOf(segments),
                Set.copyOf(waypoints), System.currentTimeMillis()));
    }

    public void clear(String robotCode) {
        intentsByRobot.remove(robotCode);
    }

    /** 只清理所属任务，避免上一任务退出时清掉同机下一任务的等待。 */
    public void clear(String robotCode, String taskId) {
        if (robotCode != null) intentsByRobot.computeIfPresent(robotCode,
                (robot, intent) -> Objects.equals(taskId, intent.taskId()) ? null : intent);
    }

    public void clearAll() {
        intentsByRobot.clear();
    }

    public List<WaitIntent> snapshot() {
        intentsByRobot.entrySet().removeIf(entry -> expired(entry.getValue()));
        return intentsByRobot.values().stream()
                .sorted(java.util.Comparator.comparing(WaitIntent::robotCode)).toList();
    }

    public List<Blocker> blockers(String robotCode, Map<String, TrafficRegistry.OccupiedResources> occupied) {
        WaitIntent intent = intentsByRobot.get(robotCode);
        return intent == null || expired(intent) ? List.of() : blockers(intent, occupied);
    }

    /** 同一个资源可能有多个物理驻留者，必须全部返回，且排除申请者自己。 */
    public List<Blocker> blockers(WaitIntent intent, Map<String, TrafficRegistry.OccupiedResources> occupied) {
        List<Blocker> result = new ArrayList<>();
        occupied.forEach((owner, resources) -> {
            if (owner.equals(intent.robotCode())) return;
            Set<String> segments = new TreeSet<>(resources.segmentIds());
            segments.retainAll(intent.segmentIds());
            Set<String> waypoints = new TreeSet<>(resources.waypointIds());
            waypoints.retainAll(intent.waypointIds());
            if (!segments.isEmpty() || !waypoints.isEmpty()) result.add(new Blocker(owner,
                    Set.copyOf(segments), Set.copyOf(waypoints)));
        });
        return result.stream().sorted(java.util.Comparator.comparing(Blocker::robotCode)).toList();
    }

    private boolean expired(WaitIntent intent) {
        return System.currentTimeMillis() - intent.recordedAtMillis() > INTENT_TTL_MILLIS;
    }

    public record Blocker(String robotCode, Set<String> segmentIds, Set<String> waypointIds) { }

    public record WaitIntent(String robotCode, String taskId, List<String> segmentIds,
                             Set<String> waypointIds, long recordedAtMillis) {
        public WaitIntent(String robotCode, List<String> segments, long recordedAtMillis) {
            this(robotCode, null, List.copyOf(segments), Set.of(), recordedAtMillis);
        }
    }
}
