package com.zioneer.robotscheduler.core.fleet;

import com.zioneer.robotscheduler.common.scheduler.SchedulerConstants;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * 车队轮询配置（对齐旧栈 yaml 的 robot_state_update_frequency / robot_inventory_update_frequency / stale_after）。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "robot.scheduler.fleet")
public class FleetPollingProperties {

    /** 状态轮询周期（毫秒），旧栈部署默认 1Hz。 */
    private long stateIntervalMillis = SchedulerConstants.ROBOT_STATE_UPDATE_INTERVAL_MILLIS;

    /** 清单轮询周期（毫秒），旧栈部署默认 0.1Hz。 */
    private long inventoryIntervalMillis = SchedulerConstants.ROBOT_INVENTORY_UPDATE_INTERVAL_MILLIS;

    /** 机器人状态过期阈值（秒）：超过未收到有效帧则临时下线。 */
    private double staleAfterSeconds = SchedulerConstants.ROBOT_STATE_STALE_AFTER_SECONDS;

    /** 离图告警限频（秒）。 */
    private double offGraphWarningIntervalSeconds = 30.0;

    /**
     * RMS 地图编码 → 调度地图 floorId 映射（如 L1=F1）；
     * 空表示 RMS map_name 与 floorId 一致，直接透传。
     */
    private Map<String, String> mapNameMapping = Map.of();

    public String resolveFloorId(String rmsMapName) {
        if (rmsMapName == null || rmsMapName.isBlank()) {
            return null;
        }
        return mapNameMapping.getOrDefault(rmsMapName, rmsMapName);
    }
}
