package com.zioneer.robotscheduler.core.routing;

import java.util.List;

/**
 * 导航规划结果：waypoint 序列 + 逐段速度/长度 + 匀速 ETA。
 */
public record NavigationRoute(

        String floorId,

        /** 途经 waypoint id 序列（含起终点）。 */
        List<String> waypointIds,

        /** 途经路径段（与 waypointIds 相邻对一一对应）。 */
        List<Segment> segments,

        /** 总长度（米）。 */
        double totalLengthMeters,

        /** 预计耗时（秒，匀速模型）。 */
        double etaSeconds) {

    /**
     * 路径段：id 为全局唯一段标识（供交通占用使用）。
     */
    public record Segment(

            String id,

            String pathId,

            String fromWaypointId,

            String toWaypointId,

            /** 段长度（米）。 */
            double lengthMeters,

            /** 有效段限速（m/s，已合并地图约束与本次规划的机器人速度上限）。 */
            double maxSpeedMetersPerSecond,

            /** 是否允许动态避障偏移。 */
            boolean obstacleAvoidanceEnabled) {
    }
}
