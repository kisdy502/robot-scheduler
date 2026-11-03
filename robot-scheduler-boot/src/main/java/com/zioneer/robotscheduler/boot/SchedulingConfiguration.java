package com.zioneer.robotscheduler.boot;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** RMS 状态与清单轮询分别运行，避免慢请求阻塞失联检测、交通监控和其他定时工作。 */
@Configuration(proxyBeanMethods = false)
public class SchedulingConfiguration {

    @Bean
    public ThreadPoolTaskScheduler fleetStateScheduler() {
        return scheduler("fleet-state-", 1);
    }

    @Bean
    public ThreadPoolTaskScheduler fleetInventoryScheduler() {
        return scheduler("fleet-inventory-", 1);
    }

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        return scheduler("scheduler-maintenance-", 4);
    }

    private ThreadPoolTaskScheduler scheduler(String prefix, int poolSize) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix(prefix);
        scheduler.setPoolSize(poolSize);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
