package com.zioneer.robotscheduler.persistence.repository;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.zioneer.robotscheduler.core.fleet.TaskAssignmentRegistry.OperationUse;
import com.zioneer.robotscheduler.core.repository.RobotUsageSnapshot;
import com.zioneer.robotscheduler.core.traffic.TrafficSnapshot;
import com.zioneer.robotscheduler.persistence.mapper.RobotRuntimeMapper;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 仅连接显式提供的本机一次性测试库，默认跳过；不能把现有调度库传给本测试。 */
class RobotRuntimePostgresTest {
    @TempDir
    Path migrations;

    // 在临时 PostgreSQL 执行真实 V6，再经真实 MyBatis 验证 JSONB、NULL 清空和序号跨仓储重建接续。
    @Test
    void migrationAndRuntimeRoundTripOnDisposablePostgres() throws Exception {
        String url = System.getenv("SCHEDULER_RECOVERY_TEST_DB_URL");
        assumeTrue(url != null && url.startsWith("jdbc:postgresql://127.0.0.1:")
                && url.endsWith("/scheduler_recovery_test"), "仅对一次性 scheduler_recovery_test 库启用");
        var source = new UnpooledDataSource("org.postgresql.Driver", url, "scheduler_test", "test-only");
        String schema = "recovery_it_" + java.util.UUID.randomUUID().toString().replace("-", "");
        for (String name : java.util.List.of("V6__robot_runtime_recovery.sql",
                "V7__scheduler_execution_recovery_marker.sql")) {
            try (var script = getClass().getResourceAsStream("/db/migration/" + name)) {
                assertThat(script).isNotNull();
                Files.copy(script, migrations.resolve(name));
            }
        }
        Flyway.configure().dataSource(source).locations("filesystem:" + migrations.toAbsolutePath())
                .schemas(schema).defaultSchema(schema).load().migrate();
        var scoped = new UnpooledDataSource("org.postgresql.Driver",
                url + "?currentSchema=" + schema + "&stringtype=unspecified",
                "scheduler_test", "test-only");
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("recovery-test", new SpringManagedTransactionFactory(), scoped));
        configuration.addMapper(RobotRuntimeMapper.class);
        var factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        var template = new SqlSessionTemplate(factory);
        var manager = new DataSourceTransactionManager(scoped);
        int first;
        {
            var repository = new MybatisRobotRuntimeRepository(template.getMapper(RobotRuntimeMapper.class), manager);
            var active = new OperationUse("R1", "flow", "photo", "running", 1000, null);
            repository.saveUsage(new RobotUsageSnapshot("R1", "flow", active, null, "map", "v1"));
            repository.saveTraffic(new TrafficSnapshot("R1", Set.of("S"), Set.of("W"), "H", null,
                    true, true, 2000, "map", "v1"));
            first = repository.nextCommandId("R1");
        }
        {
            var repository = new MybatisRobotRuntimeRepository(template.getMapper(RobotRuntimeMapper.class), manager);
            assertThat(repository.findUsages()).singleElement()
                    .satisfies(state -> assertThat(state.activeOperation().operationId()).isEqualTo("photo"));
            assertThat(repository.findTraffic()).singleElement().satisfies(state -> {
                assertThat(state.segmentIds()).containsExactly("S");
                assertThat(state.waypointIds()).containsExactly("W");
                assertThat(state.stopAccepted()).isTrue();
            });
            var finished = new OperationUse("R1", "flow", "photo", "finished", 1000, 3000L);
            repository.saveUsage(new RobotUsageSnapshot("R1", null, null, finished, "map", "v1"));
            repository.saveTraffic(new TrafficSnapshot("R1", Set.of(), Set.of(), null, "S", false,
                    false, 0, "map", "v1"));
            assertThat(repository.nextCommandId("R1")).isEqualTo(first + 1);
        }
        {
            var repository = new MybatisRobotRuntimeRepository(template.getMapper(RobotRuntimeMapper.class), manager);
            assertThat(repository.findUsages()).singleElement().satisfies(state -> {
                assertThat(state.taskflowId()).isNull();
                assertThat(state.activeOperation()).isNull();
                assertThat(state.completedOperation().finishedAt()).isEqualTo(3000);
            });
            assertThat(repository.findTraffic()).singleElement().satisfies(state -> {
                assertThat(state.segmentIds()).isEmpty();
                assertThat(state.dwellWaypointId()).isNull();
                assertThat(state.dwellSegmentId()).isEqualTo("S");
            });
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                repository.saveUsage(new RobotUsageSnapshot("R2", "flow-other", null, null, "map", "v1"));
                status.setRollbackOnly();
            });
            assertThat(repository.findUsages()).anySatisfy(state -> {
                assertThat(state.robotCode()).isEqualTo("R2");
                assertThat(state.taskflowId()).isEqualTo("flow-other");
            });
            assertThatThrownBy(() -> repository.saveUsage(
                    new RobotUsageSnapshot("R3", "flow-other", null, null, "map", "v1")))
                    .isInstanceOf(RuntimeException.class);
            assertThat(repository.findUsages()).noneMatch(state -> "R3".equals(state.robotCode()));
            // 并发分配序号时，事务必须在锁内提交，不能因后一个请求读到旧值而重号。
            try (var callers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var requests = java.util.stream.IntStream.range(0, 30)
                        .mapToObj(i -> (java.util.concurrent.Callable<Integer>) () -> repository.nextCommandId("R4"))
                        .toList();
                java.util.Set<Integer> ids = new java.util.TreeSet<>();
                for (var reply : callers.invokeAll(requests, 20, java.util.concurrent.TimeUnit.SECONDS)) {
                    ids.add(reply.get());
                }
                assertThat(ids).hasSize(30);
                assertThat(java.util.Collections.max(ids) - java.util.Collections.min(ids)).isEqualTo(29);
                assertThat(new MybatisRobotRuntimeRepository(template.getMapper(RobotRuntimeMapper.class), manager)
                        .nextCommandId("R4")).isEqualTo(java.util.Collections.max(ids) + 1);
            }
            repository.beginSchedulerExecution("R5", "old-task", "map", "v1");
            repository.beginSchedulerExecution("R5", "new-task", "map", "v1");
            repository.finishSchedulerExecution("R5", "old-task");
            assertThat(repository.findTraffic()).anySatisfy(state -> {
                assertThat(state.robotCode()).isEqualTo("R5");
                assertThat(state.awaitingStop()).isTrue();
            });
            repository.saveTraffic(new TrafficSnapshot("R5", Set.of(), Set.of(), "H5", null,
                    true, true, 0, "map", "v1"));
            repository.saveTraffic(new TrafficSnapshot("R5", Set.of(), Set.of(), "H5", null,
                    false, false, 0, "map", "v1"));
            assertThat(repository.findTraffic()).anySatisfy(state -> {
                assertThat(state.robotCode()).isEqualTo("R5");
                assertThat(state.awaitingStop()).isFalse();
            });
        }
    }
}
