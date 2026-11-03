package com.zioneer.robotscheduler.boot;

import com.zioneer.robotscheduler.core.fleet.TaskAssignmentRegistry;
import com.zioneer.robotscheduler.core.map.MapManager;
import com.zioneer.robotscheduler.core.traffic.ActiveRouteRegistry;
import com.zioneer.robotscheduler.core.traffic.TrafficRegistry;
import com.zioneer.robotscheduler.core.traffic.WaitIntentRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 地图热切换运行态清理：仅无任务绑定、无交通占用时允许切图，并清理旧等待意图与路线
 * （旧图资源 ID 在新图中无意义，保留会导致规划误判阻塞）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MapSwitchRuntimeCleaner {

    private final TaskAssignmentRegistry assignmentRegistry;
    private final MapManager mapManager;
    private final TrafficRegistry trafficRegistry;
    private final WaitIntentRegistry waitIntentRegistry;
    private final ActiveRouteRegistry routeRegistry;

    @PostConstruct
    void register() {
        mapManager.setActivationBlocked(() -> assignmentRegistry.hasExternalOperations() || !trafficRegistry.occupationSnapshot().isEmpty());
        mapManager.setOnActivated(() -> {
            waitIntentRegistry.clearAll();
            routeRegistry.clearAll();
            log.info("地图切换前：已清理旧等待意图与活动路线");
        });
    }
}
