package com.zioneer.robotscheduler.persistence.repository;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.zioneer.robotscheduler.persistence.entity.TrafficEventEntity;
import com.zioneer.robotscheduler.persistence.mapper.TrafficEventMapper;
import com.zioneer.robotscheduler.persistence.support.SnowflakeIdGenerator;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 仅在显式配置的本机一次性测试库运行，默认跳过，不连接现有调度库。 */
class TrafficEventPostgresTest {
    @TempDir
    Path migrations;

    // 验证 V1 的 128 字符限制可复现，V10 保留历史记录并支持整条路线的完整审计列表。
    @Test
    void migrateResourceIdsToText_preservesHistoryAndStoresLongRoutes() throws Exception {
        String url = System.getenv("SCHEDULER_RECOVERY_TEST_DB_URL");
        assumeTrue(url != null && url.startsWith("jdbc:postgresql://127.0.0.1:")
                && url.endsWith("/scheduler_recovery_test"), "仅对一次性 scheduler_recovery_test 库启用");
        String schema = "traffic_it_" + UUID.randomUUID().toString().replace("-", "");
        var source = new UnpooledDataSource("org.postgresql.Driver", url, "scheduler_test", "test-only");
        for (String name : List.of("V1__init_core_tables.sql", "V10__traffic_event_resource_ids_to_text.sql")) {
            try (var script = getClass().getResourceAsStream("/db/migration/" + name)) {
                assertThat(script).isNotNull();
                Files.copy(script, migrations.resolve(name));
            }
        }
        Flyway.configure().dataSource(source).locations("filesystem:" + migrations)
                .schemas(schema).defaultSchema(schema).target("1").load().migrate();
        var scoped = new UnpooledDataSource("org.postgresql.Driver",
                url + "?currentSchema=" + schema + "&stringtype=unspecified", "scheduler_test", "test-only");
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("traffic-test", new JdbcTransactionFactory(), scoped));
        config.addMapper(TrafficEventMapper.class);
        var factory = new MybatisSqlSessionFactoryBuilder().build(config);
        List<String> resources = IntStream.range(0, 100)
                .mapToObj(i -> "path_" + i + ":P" + i + "->P" + (i + 1)).toList();
        String joined = String.join(",", resources);
        assertThat(joined.length()).isGreaterThan(128);
        try (var session = factory.openSession(true)) {
            var mapper = session.getMapper(TrafficEventMapper.class);
            var historical = new TrafficEventEntity();
            historical.setId(1L);
            historical.setRobotCode("R1");
            historical.setResourceId("P1");
            historical.setAction("ACQUIRE_WAYPOINT");
            historical.setCreatedAt(Instant.parse("2026-11-04T00:00:00Z"));
            mapper.insert(historical);
            var sink = new DbTrafficEventSink(mapper, new SnowflakeIdGenerator());
            assertThatThrownBy(() -> sink.record("R1", "ACQUIRE_SEGMENT", resources, "long route"))
                    .hasStackTraceContaining("character varying(128)");
            Flyway.configure().dataSource(source).locations("filesystem:" + migrations)
                    .schemas(schema).defaultSchema(schema).load().migrate();
            sink.record("R1", "ACQUIRE_SEGMENT", resources, "long route");
            sink.record("R1", "RELEASE_SEGMENT", resources, null);
            assertThat(mapper.selectById(1L).getResourceId()).isEqualTo("P1");
            assertThat(sink.findPage("R1", "ACQUIRE_SEGMENT", 1, 10)).singleElement().satisfies(event -> {
                assertThat(event.getResourceId()).isEqualTo(joined);
                assertThat(event.getDetail()).containsEntry("message", "long route");
            });
            assertThat(sink.findPage("R1", "RELEASE_SEGMENT", 1, 10)).singleElement()
                    .satisfies(event -> assertThat(event.getResourceId()).isEqualTo(joined));
        }
    }
}
