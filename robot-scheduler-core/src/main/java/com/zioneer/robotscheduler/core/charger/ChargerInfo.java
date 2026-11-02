package com.zioneer.robotscheduler.core.charger;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 充电桩资源（对齐 chargers.json 与 charger_pool 的 ChargerInfo 契约）。
 */
@Getter
@Setter
public class ChargerInfo {

    /** 充电桩业务 ID。 */
    private String chargerId;

    /** 展示名。 */
    private String name;

    /** 拓扑 waypoint（RMF 索引）。 */
    private String rmfWaypoint;

    /** 场景点位编码。 */
    private String pointCode;

    /** 楼层 / 地图编码。 */
    private String rmfLevel;
    private String mapCode;
    private String sceneCode;

    /** 资源类型：charger（充电）/ swap_station（换电）。 */
    private String resourceType;

    /** 接口类型。 */
    private String connectorType;

    /** 兼容车队。 */
    private List<String> compatibleFleets = List.of();

    /** 所属车队。 */
    private String fleetName;

    /** 占用状态：0=可用 1=占用 -1=异常（对外契约）。 */
    private int status;

    /** 占用者机器人编码。 */
    private String occupiedBy;

    /** 预留者机器人编码。 */
    private String reservedBy;

    private boolean enabled = true;

    private long updatedAtMillis;
}
