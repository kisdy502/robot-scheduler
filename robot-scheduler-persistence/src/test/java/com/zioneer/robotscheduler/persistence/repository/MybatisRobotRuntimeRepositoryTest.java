package com.zioneer.robotscheduler.persistence.repository;

import com.zioneer.robotscheduler.core.fleet.TaskAssignmentRegistry.OperationUse;
import com.zioneer.robotscheduler.core.repository.RobotUsageSnapshot;
import com.zioneer.robotscheduler.core.traffic.TrafficSnapshot;
import com.zioneer.robotscheduler.persistence.entity.RobotRuntimeEntity;
import com.zioneer.robotscheduler.persistence.mapper.RobotRuntimeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MybatisRobotRuntimeRepositoryTest {
    private final Map<String, RobotRuntimeEntity> rows = new HashMap<>();
    private RobotRuntimeMapper mapper;
    private MybatisRobotRuntimeRepository repository;

    @BeforeEach
    void setUp() {
        mapper = mock(RobotRuntimeMapper.class);
        repository = new MybatisRobotRuntimeRepository(mapper);
        when(mapper.selectById(anyString())).thenAnswer(call -> copy(rows.get(call.getArgument(0))));
        when(mapper.selectList(any())).thenAnswer(call -> rows.values().stream().map(this::copy).toList());
        when(mapper.insert(any(RobotRuntimeEntity.class))).thenAnswer(call -> save(call.getArgument(0)));
        when(mapper.updateById(any(RobotRuntimeEntity.class))).thenAnswer(call -> save(call.getArgument(0)));
    }

    // 使用权和交通写入同一机器人行时互不覆盖，结束与释放必须实际清空可空字段。
    @Test
    void usageTrafficAndReleasePreserveEachOthersColumns() {
        var active = new OperationUse("R1", "flow", "photo", "running", 100L, null);
        repository.saveUsage(new RobotUsageSnapshot("R1", "flow", active, null, "map", "v1"));
        repository.saveTraffic(new TrafficSnapshot("R1", Set.of("S"), Set.of("W"), "H", null,
                false, false, 200L, "map", "v1"));
        var finished = new OperationUse("R1", "flow", "photo", "finished", 100L, 300L);
        repository.saveUsage(new RobotUsageSnapshot("R1", null, null, finished, "map", "v1"));
        assertThat(repository.findUsages()).singleElement().satisfies(state -> {
            assertThat(state.taskflowId()).isNull();
            assertThat(state.activeOperation()).isNull();
            assertThat(state.completedOperation()).isEqualTo(finished);
        });
        assertThat(repository.findTraffic()).singleElement().satisfies(state -> {
            assertThat(state.segmentIds()).containsExactly("S");
            assertThat(state.dwellWaypointId()).isEqualTo("H");
        });
        assertThat(rows.get("R1").getOperationStartedAt()).isNull();
        assertThat(rows.get("R1").getCompletedFinishedAt()).isEqualTo(Instant.ofEpochMilli(300));
    }

    // 序号落库接续且不会被随后保存的使用权或交通快照重置。
    @Test
    void commandSequenceIsDurableAndNotResetByOtherWrites() {
        int first = repository.nextCommandId("R1");
        repository.saveUsage(new RobotUsageSnapshot("R1", "flow", null, null, "map", "v1"));
        repository.saveTraffic(new TrafficSnapshot("R1", Set.of(), Set.of(), "H", null,
                false, false, 0, "map", "v1"));
        int next = new MybatisRobotRuntimeRepository(mapper).nextCommandId("R1");
        assertThat(first).isPositive();
        assertThat(next).isEqualTo(first + 1);
        assertThat(repository.findUsages().getFirst().taskflowId()).isEqualTo("flow");
    }

    // 数据库未实际更新行时必须报错，不能对上层假报占用或序号已经持久化。
    @Test
    void zeroRowsWrittenIsFailure() {
        when(mapper.insert(any(RobotRuntimeEntity.class))).thenReturn(0);
        assertThatThrownBy(() -> repository.nextCommandId("R1")).isInstanceOf(IllegalStateException.class);
        assertThat(rows).isEmpty();
    }

    private int save(RobotRuntimeEntity row) {
        rows.put(row.getRobotCode(), copy(row));
        return 1;
    }

    private RobotRuntimeEntity copy(RobotRuntimeEntity row) {
        if (row == null) return null;
        var copied = new RobotRuntimeEntity();
        org.springframework.beans.BeanUtils.copyProperties(row, copied);
        return copied;
    }
}
