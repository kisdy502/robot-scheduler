package com.zioneer.robotscheduler.core.fleet;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * 机器人实时状态快照（融合 RMS status 轮询帧与机器人清单，对齐 fleet_states 契约）。
 */
@Getter
@Setter
public class RobotSnapshot {

    /** 业务机器人编码（如 RB-001）。 */
    private String robotCode;

    /** 展示名。 */
    private String robotName;

    private String fleetName;

    /** 机器人所属地图（RMS 原始 map_name，映射前）。 */
    private String map;

    /** 机器人 SLAM 地图标识（RMS current_map_code，如 map1027S）：与调度激活地图比对，不一致不可派发。 */
    private String slamMapCode;

    /** 本体充电中标记（robot_status.battery[0].is_charging）：充电任务接管判定输入。 */
    private Boolean bodyCharging;

    /** 调度楼层（RMS map_name 经楼层映射解析后的 floorId）。 */
    private String floorId;

    private double x;
    private double y;
    private double yaw;

    /** 电量百分比 0-100（对 RMS 的 0-100 值不做换算，对外输出保留 1 位小数）。 */
    private Double battery;

    /** 归一化 mode：idle/charging/running/paused/waiting/error/unknown。 */
    private String mode;

    /** 当前正在执行的内核任务 ID。 */
    private String currentTaskId;

    /** 目标点（waypoint id）。 */
    private String targetPoint;

    /** 兼容充电桩列表。 */
    private List<String> compatibleChargers = List.of();

    /** 家点 waypoint。 */
    private String homeWaypoint;

    /** RMS 停用标记（decommission）；停用后不派任务、不参与规划。 */
    private Boolean decommissioned;

    /** RMS 侧明确离线（online_status=offline）。 */
    private Boolean rmsOffline;

    /** RMS 请求重规划信号。 */
    private boolean replanRequested;

    /** 是否在线（15s TTL 判定）。 */
    private boolean online;

    /** 是否可接受任务派发（下线/错误/暂停/未知模式/离图时为 false）。 */
    private boolean dispatchable;
    /** 基础导航就绪状态，独立于任务流使用权；旧镜像为空时兼容 dispatchable。 */
    private Boolean navigationReady;

    /** 状态更新时间（毫秒）。 */
    private long updatedAtMillis;

    /** 最近一帧 RMS 完成命令回执（用于执行闭环，raw 结构对齐 RMS）。 */
    private List<Map<String, Object>> completedCommands = List.of();
}
