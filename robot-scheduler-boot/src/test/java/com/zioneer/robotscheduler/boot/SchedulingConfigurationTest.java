package com.zioneer.robotscheduler.boot;

import com.zioneer.robotscheduler.core.fleet.FleetManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulingConfigurationTest {

    // 清单轮询阻塞时，状态轮询与维护任务仍可运行；同时校验生产方法实际绑定到隔离的调度器。
    @Test
    void slowInventoryDoesNotBlockStateOrMaintenance() throws Exception {
        assertThat(FleetManager.class.getMethod("pollStates").getAnnotation(Scheduled.class).scheduler())
                .isEqualTo("fleetStateScheduler");
        assertThat(FleetManager.class.getMethod("pollInventory").getAnnotation(Scheduled.class).scheduler())
                .isEqualTo("fleetInventoryScheduler");
        new ApplicationContextRunner().withUserConfiguration(SchedulingConfiguration.class).run(context -> {
            CountDownLatch inventoryEntered = new CountDownLatch(1);
            CountDownLatch releaseInventory = new CountDownLatch(1);
            CountDownLatch stateRan = new CountDownLatch(1);
            CountDownLatch maintenanceRan = new CountDownLatch(1);
            try {
                context.getBean("fleetInventoryScheduler", ThreadPoolTaskScheduler.class).schedule(() -> {
                    inventoryEntered.countDown();
                    try {
                        releaseInventory.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }, Instant.now());
                assertThat(inventoryEntered.await(5, TimeUnit.SECONDS)).isTrue();
                context.getBean("fleetStateScheduler", ThreadPoolTaskScheduler.class)
                        .schedule(stateRan::countDown, Instant.now());
                context.getBean("taskScheduler", ThreadPoolTaskScheduler.class)
                        .schedule(maintenanceRan::countDown, Instant.now());
                assertThat(stateRan.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(maintenanceRan.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                releaseInventory.countDown();
            }
        });
    }
}
