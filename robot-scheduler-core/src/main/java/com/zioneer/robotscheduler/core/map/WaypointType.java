package com.zioneer.robotscheduler.core.map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 点位类型（对齐地图编辑器 json 的 13 种 waypoint type，数值编码保留用于对外兼容展示）。
 */
public enum WaypointType {

    PATH(0, "path"),
    WORKSTATION(1, "workstation"),
    CHARGE(2, "charge"),
    PARKING(3, "parking"),
    DOCK(4, "dock"),
    ROLLER(5, "roller"),
    PICKUP(6, "pickup"),
    DROPOFF(7, "dropoff"),
    STORAGE(8, "storage"),
    ELEVATOR(9, "elevator"),
    STAIRS(10, "stairs"),
    RAMP(11, "ramp"),
    RELOCATION(12, "relocation");

    private final int code;
    private final String wireName;

    WaypointType(int code, String wireName) {
        this.code = code;
        this.wireName = wireName;
    }

    public int code() {
        return code;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    /** 按字符串解析（json 反序列化用，匹配小写 wireName）。 */
    @JsonCreator
    public static WaypointType fromWireName(String name) {
        if (name == null) {
            return null;
        }
        for (WaypointType type : values()) {
            if (type.wireName.equals(name)) {
                return type;
            }
        }
        return null;
    }
}
