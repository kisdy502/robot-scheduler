package com.zioneer.robotscheduler.core.map;

import com.zioneer.robotscheduler.core.repository.MapArchiveRepository;
import com.zioneer.robotscheduler.core.traffic.InMemoryTrafficRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MapManagerTest {
    @TempDir Path temporary;

    private final MapArchiveRepository repository = mock(MapArchiveRepository.class);
    private final MapManager manager = new MapManager(new MapMetadataParser(), repository);

    private static String json(String version, double goalX) {
        return """
                {"versionInfo":{"version":"%s","status":"published"},
                 "mapInfo":{"mapName":"BindingMap","floors":[{
                   "floorId":"F1","defaultMaxSpeed":1.0,
                   "waypoints":[{"id":"A","type":"path","position":[0,0,0]},
                                {"id":"B","type":"path","position":[%s,0,0]}],
                   "paths":[{"id":"P","waypoints":["A","B"],"isBidirectional":true}]
                }]}}
                """.formatted(version, goalX);
    }

    // 场景：存在任务绑定时激活地图（同版本 v1 与新版本 v2 各一次）
    // 预期：两次均被拒且无持久化/清理副作用；解绑后 v2 激活成功、代数递增、新旧快照隔离
    @Test
    void bindingRejectsSameAndNewVersion_withoutPersistenceOrCleanup() {
        manager.activateFromJson(json("v1", 1), "old.png", "old.yaml");
        MapSnapshot old = manager.activeSnapshot().orElseThrow();
        Runnable cleanup = mock(Runnable.class);
        manager.setOnActivated(cleanup);
        clearInvocations(repository);
        var binding = manager.bindTask();
        try {
            for (String version : new String[]{"v1", "v2"}) {
                assertThatThrownBy(() -> manager.activateFromJson(json(version, 10), null, null))
                        .isInstanceOf(MapActivationConflictException.class);
            }
            assertThat(manager.activeSnapshot().orElseThrow()).isSameAs(old);
            assertThat(binding.snapshot().orElseThrow()).isSameAs(old);
            verifyNoInteractions(repository, cleanup);
        } finally {
            binding.close();
            binding.close();
        }
        manager.activateFromJson(json("v2", 10), "new.png", "new.yaml");
        MapSnapshot next = manager.activeSnapshot().orElseThrow();
        assertThat(next.version()).isEqualTo("v2");
        assertThat(next.generation()).isEqualTo(old.generation() + 1);
        assertThat(next.graph().waypoint("F1", "B").orElseThrow().getX()).isEqualTo(10);
        assertThat(next.pgmPath()).isEqualTo("new.png");
        assertThat(old.graph().waypoint("F1", "B").orElseThrow().getX()).isEqualTo(1);
        verify(cleanup).run();
    }

    // 场景：持久化挂起期间并发 bindTask；预期：绑定阻塞等待激活提交，完成后读到版本一致的 v2 新快照
    @Test
    void bindWaitsForActivationCommit_andSeesCoherentNewSnapshot() throws Exception {
        manager.activateFromJson(json("v1", 1), null, null);
        CountDownLatch saving = new CountDownLatch(1);
        CountDownLatch allowSave = new CountDownLatch(1);
        CountDownLatch bindingAttempted = new CountDownLatch(1);
        doAnswer(call -> {
            saving.countDown();
            if (!allowSave.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("save timeout");
            return null;
        }).when(repository).save(any());
        try (var threads = Executors.newFixedThreadPool(2)) {
            var activation = threads.submit(() -> manager.activateFromJson(json("v2", 10), null, null));
            try {
                assertThat(saving.await(5, TimeUnit.SECONDS)).isTrue();
                var bind = threads.submit(() -> {
                    bindingAttempted.countDown();
                    return manager.bindTask();
                });
                assertThat(bindingAttempted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> bind.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                assertThat(manager.activeSnapshot().orElseThrow().version()).isEqualTo("v1");
                allowSave.countDown();
                activation.get(5, TimeUnit.SECONDS);
                try (var binding = bind.get(5, TimeUnit.SECONDS)) {
                    MapSnapshot snapshot = binding.snapshot().orElseThrow();
                    assertThat(snapshot.version()).isEqualTo("v2");
                    assertThat(snapshot.metadata().getVersionInfo().getVersion()).isEqualTo("v2");
                    assertThat(snapshot.graph().waypoint("F1", "B").orElseThrow().getX()).isEqualTo(10);
                }
            } finally {
                allowSave.countDown();
            }
        }
    }

    // 场景：数据库保存失败时激活新版本；预期：抛异常且旧快照原样保留
    @Test
    void storageFailure_keepsOldSnapshotAndGeneration() {
        manager.activateFromJson(json("v1", 1), null, null);
        MapSnapshot old = manager.activeSnapshot().orElseThrow();
        doThrow(new IllegalStateException("database unavailable")).when(repository).save(any());
        assertThatThrownBy(() -> manager.activateFromJson(json("v2", 10), null, null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(manager.activeSnapshot().orElseThrow()).isSameAs(old);
    }

    // 场景：存在交通占用（含驻留）时激活新地图；预期：激活被拒并提示交通占用，占用清空后激活成功
    @Test
    void trafficIncludingDwell_blocksActivation() {
        manager.activateFromJson(json("v1", 1), null, null);
        InMemoryTrafficRegistry traffic = new InMemoryTrafficRegistry();
        manager.setActivationBlocked(() -> !traffic.occupationSnapshot().isEmpty());
        traffic.updateDwell("R1", "A", null);
        assertThatThrownBy(() -> manager.activateFromJson(json("v2", 10), null, null))
                .isInstanceOf(MapActivationConflictException.class).hasMessageContaining("交通占用");
        traffic.updateDwell("R1", null, null);
        manager.activateFromJson(json("v2", 10), null, null);
        assertThat(manager.activeSnapshot().orElseThrow().version()).isEqualTo("v2");
    }

    // 场景：任务绑定期间执行启动加载（文件/存档）以及 draft 状态地图激活
    // 预期：加载被绑定拦截、draft 被解析拒绝，均不产生激活快照
    @Test
    void startupLoadersCannotBypassBinding_andDraftCannotActivate() throws Exception {
        Path file = temporary.resolve("metadata.json");
        Files.writeString(file, json("v1", 1));
        try (var binding = manager.bindTask()) {
            assertThatThrownBy(() -> manager.loadFromFiles(file.toString(), null, null))
                    .isInstanceOf(MapActivationConflictException.class);
            var archive = new com.zioneer.robotscheduler.core.repository.MapArchive();
            archive.setMetadataJson(json("v1", 1));
            when(repository.findActive()).thenReturn(Optional.of(archive));
            assertThatThrownBy(manager::loadFromArchive).isInstanceOf(MapActivationConflictException.class);
            assertThat(binding.snapshot()).isEmpty();
        }
        Files.writeString(file, json("v1", 1).replace("published", "draft"));
        assertThatThrownBy(() -> manager.loadFromFiles(file.toString(), null, null))
                .isInstanceOf(MapMetadataException.class);
        assertThat(manager.activeSnapshot()).isEmpty();
    }
}
