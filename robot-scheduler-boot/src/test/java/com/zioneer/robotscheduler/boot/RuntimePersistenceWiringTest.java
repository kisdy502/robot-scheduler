package com.zioneer.robotscheduler.boot;

import com.zioneer.robotscheduler.core.config.SchedulerCoreAutoConfiguration;
import com.zioneer.robotscheduler.core.fleet.RobotCommandExecutor;
import com.zioneer.robotscheduler.core.fleet.RobotGateway;
import com.zioneer.robotscheduler.core.fleet.TaskAssignmentRegistry;
import com.zioneer.robotscheduler.core.map.MapManager;
import com.zioneer.robotscheduler.core.map.MapMetadataParser;
import com.zioneer.robotscheduler.core.repository.InMemoryRobotRuntimeRepository;
import com.zioneer.robotscheduler.core.repository.MapArchiveRepository;
import com.zioneer.robotscheduler.core.repository.RobotRuntimeRepository;
import com.zioneer.robotscheduler.core.traffic.PersistentTrafficRegistry;
import com.zioneer.robotscheduler.core.traffic.TrafficRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RuntimePersistenceWiringTest {
    // 正式自动装配必须选择持久化交通正本，并把同一仓储注入使用权和命令执行器，不能走独立内存构造器。
    @Test
    void coreComponentsShareConfiguredRuntimeRepository() {
        var store = new InMemoryRobotRuntimeRepository();
        RobotGateway gateway = mock(RobotGateway.class);
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(SchedulerCoreAutoConfiguration.class))
                .withBean(RobotRuntimeRepository.class, () -> store)
                .withBean(MapManager.class, () -> new MapManager(new MapMetadataParser(), mock(MapArchiveRepository.class)))
                .withBean(RobotGateway.class, () -> gateway)
                .withUserConfiguration(TaskAssignmentRegistry.class, RobotCommandExecutor.class).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(TrafficRegistry.class)).isInstanceOf(PersistentTrafficRegistry.class);
                    context.getBean(TaskAssignmentRegistry.class).assign("flow", "R1");
                    assertThat(store.findUsages()).singleElement()
                            .satisfies(state -> assertThat(state.taskflowId()).isEqualTo("flow"));
                    assertThat(store.nextCommandId("R1")).isEqualTo(1);
                    context.getBean(RobotCommandExecutor.class).stop("R1", 0, null);
                    verify(gateway).stop(eq("R1"), eq(0), eq(2), isNull());
                });
    }
}
