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
 * {@link #releaseSegments} 渐进释放；正常到达接续驻留，异常由停止确认后释放多余预约。</p>
 *
 * <p>实现：单实例正式装配用 {@link PersistentTrafficRegistry}，隔离测试可用 {@link InMemoryTrafficRegistry}；多实例演进见 docs/multi-instance-roadmap.md。</p>
 */
public interface TrafficRegistry {

    /**
     * 为机器人整条路径申请资源占用（全有或全无）。
     *
     * @param robotCode    机器人编码
     * @param segmentIds   途经路径段 id 序列
     * @param waypointIds  途经关键点位 id（目标点、充电位等）
     * @param lease        预约时长提示（当前内存实现不自动到期释放，失联由停止确认流程处理）
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
     * 释放机器人路线预约；停止确认期间保留，物理驻留独立管理。
     */
    void releaseAll(String robotCode);

    /**
     * 任务终态原子切换：同一把锁内"释放全部路线租约 + 登记终点驻留"。
     *
     * <p>解决幽灵窗口：先 releaseAll 再等状态帧登记驻留之间存在 0~2s 空档，
     * 并发任务在该窗口拿到"空点"租约直达，与原地不动的车物理叠车（压测实测 P4 双车同点）。
     * 本方法把驻留登记提前到引擎终态瞬间，窗口归零。</p>
     *
     * @param dwellWaypointId 终点 waypoint id（null/空 = 等价普通 releaseAll）
     */
    void releaseAllAndDwell(String robotCode, String dwellWaypointId);

    /** 异常退出后冻结剩余预约和最后驻留；返回是否首次进入停止确认。 */
    boolean retainUntilStopped(String robotCode, String reason);

    boolean isAwaitingStop(String robotCode);

    /** 仅表示停止请求被接受，仍需新状态帧确认实际静止。 */
    void markStopAccepted(String robotCode);

    boolean isStopAccepted(String robotCode);

    /** 确认停止后，原子转为实际点/段驻留，释放多余预约。 */
    boolean confirmStopped(String robotCode, String waypointId, String segmentId);

    /**
     * 清理可释放的路线预约；不强制解除停止确认保留或物理驻留。
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
     * 驻留与任务占用分开管理，由 FleetManager 每帧驱动；停止确认期间冻结，离线不清空。
     *
     * @param waypointId 吸附到的点位（机器人在点上）；可为 null
     * @param segmentId  吸附到的路径段（机器人停在两个点之间的路段中间）；可为 null
     */
    void updateDwell(String robotCode, String waypointId, String segmentId);

    /** 各机器人占用明细（快照/审计）。 */
    Map<String, OccupiedResources> occupationSnapshot();

    /** 驻留正本查询，持久化实现也必须向等待诊断和路径执行提供相同语义。 */
    default String dwellOwner(String waypointId) { return null; }
    default String dwellSegmentOwner(String segmentId) { return null; }

    /** 资源占用明细。 */
    record OccupiedResources(Set<String> segmentIds, Set<String> waypointIds, long acquiredAtMillis) {
    }
}
