package com.zioneer.robotscheduler.api.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 入站 HTTP 访问日志配置（前缀 robot.scheduler.api.access-log）。
 *
 * <p>问题定位需求：低频调用（任务下发/取消/资源管理/地图上传）全量 INFO 打印；
 * 前端大屏与任务流的状态轮询端点默认排除，避免刷屏。</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "robot.scheduler.api.access-log")
public class AccessLogProperties {

    /** 是否启用入站访问日志。 */
    private boolean enabled = true;

    /** 排除路径（Ant 风格）：默认为大屏/任务流的定时轮询端点。 */
    private List<String> excludePaths = new ArrayList<>(List.of(
            // 调度大屏轮询：任务/交通事件/实时占用/路线/机器人事件/充电桩
            "/api/v1/observability/**",
            // 车队快照（大屏 1Hz 位置刷新）
            "/api/v1/runtime/**",
            // 地图元数据/图片/几何（大屏加载与刷新拉取）
            "/api/v1/maps/current",
            "/api/v1/maps/current/*",
            // 任务状态查询（任务流下发后轮询终态）
            "/api/v1/scheduler/rmf/tasks",
            "/api/v1/scheduler/tasks/**"
    ));
}
