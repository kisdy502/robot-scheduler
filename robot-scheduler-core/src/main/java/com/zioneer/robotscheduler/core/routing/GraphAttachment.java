package com.zioneer.robotscheduler.core.routing;

import java.util.Optional;

/**
 * 机器人实际位置吸附到导航图的结果（waypoint 优先，其次路径段）。
 */
public record GraphAttachment(

        /** 吸附到的 waypoint id。 */
        String waypointId,

        /** 吸附到的路径段 id。 */
        String segmentId,

        /** 吸附距离（米）。 */
        double distanceMeters) {

    public boolean attached() {
        return waypointId != null || segmentId != null;
    }

    public Optional<String> waypointIdOptional() {
        return Optional.ofNullable(waypointId);
    }

    public Optional<String> segmentIdOptional() {
        return Optional.ofNullable(segmentId);
    }

    public static GraphAttachment none() {
        return new GraphAttachment(null, null, Double.MAX_VALUE);
    }
}
