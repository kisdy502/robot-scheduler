package com.zioneer.robotscheduler.core.config;

import com.zioneer.robotscheduler.core.charger.LowBatteryChargeSupervisor;
import com.zioneer.robotscheduler.core.assign.TaskAssignProperties;
import com.zioneer.robotscheduler.core.event.SchedulerEventBus;
import com.zioneer.robotscheduler.core.fleet.FleetPollingProperties;
import com.zioneer.robotscheduler.core.traffic.PersistentTrafficRegistry;
import com.zioneer.robotscheduler.core.repository.RobotRuntimeRepository;
import com.zioneer.robotscheduler.core.map.MapManager;
import com.zioneer.robotscheduler.core.traffic.TrafficRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

/**
 * 调度内核自动装配：内核基础 bean + 配置属性注册。
 */
@AutoConfiguration
@EnableConfigurationProperties({FleetPollingProperties.class, TaskAssignProperties.class,
        LowBatteryChargeSupervisor.ChargeProperties.class})
public class SchedulerCoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SchedulerEventBus schedulerEventBus(ApplicationEventPublisher publisher) {
        return new SchedulerEventBus(publisher);
    }

    @Bean
    @ConditionalOnMissingBean(TrafficRegistry.class)
    public TrafficRegistry trafficRegistry(
            RobotRuntimeRepository repository,
            MapManager maps) {
        return new PersistentTrafficRegistry(repository, maps);
    }
}
