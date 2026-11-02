package com.zioneer.robotscheduler.core.map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

/**
 * 区域约束（多边形）：forbidden 禁行 / slowDown 限速 / ramp 坡道。
 *
 * <p>速度优先级（地图格式文档 §十）：path.maxSpeed &gt; zone.maxSpeed &gt; floor.defaultMaxSpeed &gt; 机器人硬件默认；
 * 多重叠加取最小值；forbidden 即 maxSpeed=0 的禁行区。</p>
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class ZoneMetadata {

    private String id;

    private String name;

    /** forbidden / slowDown / ramp。 */
    private String type;

    /** 优先级，数值越小优先级越高；区域重叠时高优先级生效。 */
    private Integer priority;

    /** 区域内最大速度（m/s），forbidden 为 0。 */
    private Double maxSpeed;

    /** 多边形顶点 [[x, y], ...]。 */
    private double[][] vertices;

    private String description;
}
