package com.zioneer.robotscheduler.core.map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 跨层连接（elevator/stairs/ramp）；本期构建连接索引供校验与展示，跨层任务调度 P4+ 实现。
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class InterFloorConnection {

    private String id;

    /** elevator / stairs / ramp。 */
    private String type;

    private String name;

    /** 连接端点：floorId + waypointId。 */
    private List<ConnectionEndpoint> connections = List.of();

    /** 载重限制（千克），null 不限。 */
    private Double capacity;

    /** 通行速度（m/s）。 */
    private Double maxSpeed;

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ConnectionEndpoint {

        private String floorId;
        private String waypointId;
    }
}
