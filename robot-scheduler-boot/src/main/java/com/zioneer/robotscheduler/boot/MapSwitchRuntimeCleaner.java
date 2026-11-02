package com.zioneer.robotscheduler.boot;

import com.zioneer.robotscheduler.core.map.MapManager;
import com.zioneer.robotscheduler.core.traffic.ActiveRouteRegistry;
import com.zioneer.robotscheduler.core.traffic.TrafficRegistry;
import com.zioneer.robotscheduler.core.traffic.WaitIntentRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 地图热切换运行态清理：新图激活时清空旧图的交通占用与等待意图
 * （旧图资源 ID 在新图中无意义，保留会导致规划误判阻塞）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MapSwitchRuntimeCleaner {

    private final MapManager mapManager;
    private final TrafficRegistry trafficRegistry;
    private final WaitIntentRegistry waitIntentRegistry;
    private final ActiveRouteRegistry routeRegistry;

    @PostConstruct
    void register() {
        mapManager.setOnActivated(() -> {
            trafficRegistry.clearAll();
            waitIntentRegistry.clearAll();
            routeRegistry.clearAll();
            log.info("地图热切换：已清空交通占用与等待意图");
        });
    }
}
