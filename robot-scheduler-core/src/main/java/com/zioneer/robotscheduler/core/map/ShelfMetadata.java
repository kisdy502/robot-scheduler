package com.zioneer.robotscheduler.core.map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 货架（最小化消费：调度仅使用货架足迹与接入边信息生成/校验导航目标，货位与商品归业务系统管理）。
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class ShelfMetadata {

    private String id;

    private String name;

    /** 货架足迹多边形顶点（楼层平面坐标）。 */
    private double[][] footprintVertices;

    /** 各层接入边索引（楼层基准高度偏移）。 */
    private List<Integer> accessEdges = List.of();
}
