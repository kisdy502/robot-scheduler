package com.zioneer.robotscheduler.core.traffic;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 交通资源占用端口（集中式：路径段 + 关键点位两级占用，替代 RMF 的时空轨迹+分布式协商）。
 *
 * <p>资源粒度：路径段（pathId#from->to）与 waypoint id；到达回报释放后方资源。</p>
 */
/**
 * 交通资源占用端口（调度交通管制的核心抽象，替代 RMF 的时空轨迹 + 分布式协商）。
 *
 * <p>资源模型：路径段（segment，格式 {@code PATH_x#{minId}-{maxId}}，双向统一 ID 对向互斥）
 * + 点位（waypoint）两级离散占用；另含静止机器人驻留（dwell 点/段）。
 * 用法：任务开始前 {@link #tryAcquire} 原子占用整条路径，逐段通过后
 * {@link #releaseSegments} 渐进释放，任务终态 {@link #releaseAll} 全量释放。</p>
 *
 * <p>实现：单实例用 {@link InMemoryTrafficRegistry}；多实例演进见 docs/multi-instance-roadmap.md。</p>
 */
public interface TrafficRegistry {

    /**
     * 为机器人整条路径申请资源占用（全有或全无）。
     *
     * @param robotCode    机器人编码
     * @param segmentIds   途经路径段 id 序列
     * @param waypointIds  途经关键点位 id（目标点、充电位等）
     * @param lease        租约时长（超时自动释放，防机器人失联死锁）
     * @return true=占用成功；false=存在冲突（任务进入等待，不抛异常）
     */
    boolean tryAcquire(String robotCode, List<String> segmentIds, Set<String> waypointIds, Duration lease);

    /**
     * 机器人到达某 waypoint 后释放其身后的路径段资源。
     */
    void releaseBehind(String robotCode, String reachedWaypointId);

    /**
     * 精确释放机器人已走过的路径段（分段导航每完成一段调用，渐进释放交通资源）。
     */
    void releaseSegments(String robotCode, List<String> segmentIds);

    /**
     * 释放机器人全部占用（任务结束/取消/下线）。
     */
    void releaseAll(String robotCode);

    /**
     * 清空全部占用（地图热切换时旧图资源 ID 已失效）。
     */
    default void clearAll() {
        occupationSnapshot().keySet().forEach(this::releaseAll);
    }

    /** 当前被占路径段集合（规划约束输入）。 */
    Set<String> occupiedSegments();

    /** 当前被占点位集合（任务占用 + 静止机器人驻留点，规划约束输入）。 */
    Set<String> occupiedWaypoints();

    /**
     * 排除机器人自身占用后的被占段集合（规划约束输入）：
     * 机器人常驻留在自己门口的路段/点位上，若把自身占用也当障碍，
     * 会封死自家相邻边导致大幅绕路（规划必须允许"从自己的驻留段上出发"）。
     */
    Set<String> occupiedSegmentsExcluding(String robotCode);

    /** 排除机器人自身占用后的被占点位集合（语义同 {@link #occupiedSegmentsExcluding}）。 */
    Set<String> occupiedWaypointsExcluding(String robotCode);

    /**
     * 更新机器人驻留（静止机器人的吸附点/段注册为交通障碍，均传 null 清除）。
     * 驻留与任务占用分开管理：机器人开始移动/离线时清除，由 FleetManager 每帧驱动。
     *
     * @param waypointId 吸附到的点位（机器人在点上）；可为 null
     * @param segmentId  吸附到的路径段（机器人停在两个点之间的路段中间）；可为 null
     */
    void updateDwell(String robotCode, String waypointId, String segmentId);

    /** 各机器人占用明细（快照/审计）。 */
    Map<String, OccupiedResources> occupationSnapshot();

    /** 资源占用明细。 */
    record OccupiedResources(Set<String> segmentIds, Set<String> waypointIds, long acquiredAtMillis) {
    }
}
