package com.zioneer.robotscheduler.core.traffic;

import java.util.Set;

/** 分开保存路线预约与物理驻留，避免重启后把整条旧路线误当作一个驻留点。 */
public record TrafficSnapshot(String robotCode, Set<String> segmentIds, Set<String> waypointIds,
                              String dwellWaypointId, String dwellSegmentId, boolean awaitingStop,
                              boolean stopAccepted, long acquiredAtMillis, String mapName, String mapVersion) {
    public TrafficSnapshot {
        segmentIds = Set.copyOf(segmentIds);
        waypointIds = Set.copyOf(waypointIds);
    }

    public boolean hasResources() {
        return !segmentIds.isEmpty() || !waypointIds.isEmpty() || dwellWaypointId != null || dwellSegmentId != null;
    }

    public boolean needsStopConfirmation() {
        return awaitingStop || !segmentIds.isEmpty() || !waypointIds.isEmpty();
    }
}
