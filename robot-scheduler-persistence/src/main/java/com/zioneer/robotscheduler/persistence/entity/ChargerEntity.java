package com.zioneer.robotscheduler.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zioneer.robotscheduler.persistence.domain.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 充电桩（t_charger；正本持久化，内存缓存加速分配）。
 */
@Getter
@Setter
@TableName("t_charger")
public class ChargerEntity extends BaseEntity {

    @TableField("charger_id")
    private String chargerId;

    @TableField("charger_name")
    private String chargerName;

    @TableField("rmf_waypoint")
    private String rmfWaypoint;

    @TableField("point_code")
    private String pointCode;

    @TableField("rmf_level")
    private String rmfLevel;

    @TableField("map_code")
    private String mapCode;

    @TableField("scene_code")
    private String sceneCode;

    @TableField("resource_type")
    private String resourceType;

    @TableField("connector_type")
    private String connectorType;

    /** PG text[] 兼容：以逗号分隔存储 compatible_fleets。 */
    @TableField("compatible_fleets")
    private String compatibleFleets;

    @TableField("fleet_name")
    private String fleetName;

    @TableField("status")
    private Integer status;

    @TableField("occupied_by")
    private String occupiedBy;

    @TableField("reserved_by")
    private String reservedBy;

    @TableField("enabled")
    private Boolean enabled;

    @TableField("updated_at")
    private Instant updatedAt;
}
