package com.zioneer.robotscheduler.boot;

import com.zioneer.robotscheduler.api.config.MapStorageProperties;
import com.zioneer.robotscheduler.core.map.MapActivationConflictException;
import com.zioneer.robotscheduler.core.map.MapManager;
import com.zioneer.robotscheduler.core.map.MapMetadataParser;
import com.zioneer.robotscheduler.core.repository.MapArchiveRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class RecoveryMapBootstrapTest {
    // 有恢复占用却找不到原地图时必须停止启动，不能继续无图服务并接受新业务。
    @Test
    void recoveredOccupancyCannotFallBackToNoMapStartup() {
        var maps = new MapManager(new MapMetadataParser(), mock(MapArchiveRepository.class));
        maps.requireRecoveryMap("old-map", "v1");
        var properties = new MapStorageProperties();
        assertThatThrownBy(() -> new MapBootstrapRunner(maps, properties)
                .run(new DefaultApplicationArguments(new String[0])))
                .isInstanceOf(MapActivationConflictException.class);
        assertThat(maps.isLoaded()).isFalse();
    }
}
