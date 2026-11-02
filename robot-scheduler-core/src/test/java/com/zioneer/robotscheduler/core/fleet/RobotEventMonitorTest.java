package com.zioneer.robotscheduler.core.fleet;

import com.zioneer.robotscheduler.core.repository.RobotEventRecord;
import com.zioneer.robotscheduler.core.repository.RobotEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 机器人事件监测器测试：状态/上下线变化独立成行、电量 ±1% 窗口聚合、阈值穿越必记。
 */
@ExtendWith(MockitoExtension.class)
class RobotEventMonitorTest {

    @Mock
    private RobotEventRepository repository;

    private RobotEventMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new RobotEventMonitor(repository);
        monitor.batteryAggregationWindowMillis = 30_000;
    }

    // mode 变化（idle→running）：独立记录一条 MODE 事件，含新旧值
    @Test
    void modeChange_recordsIndependentEvent() {
        monitor.onFrame("R001", true, "idle", 80.0, true, "running", 80.0);

        ArgumentCaptor<RobotEventRecord> captor = ArgumentCaptor.forClass(RobotEventRecord.class);
        verify(repository).insert(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("MODE");
        assertThat(captor.getValue().getReason()).isEqualTo("MODE_CHANGE");
        assertThat(captor.getValue().getOldValue()).isEqualTo("idle");
        assertThat(captor.getValue().getNewValue()).isEqualTo("running");
        assertThat(captor.getValue().getEventCount()).isEqualTo(1);
    }

    // 机器人下线：记录 WARN 级 OFFLINE 事件，消息携带末次电量百分比
    @Test
    void offline_recordsWarnEvent() {
        monitor.onFrame("R001", true, "running", 15.0, false, "unknown", 15.0);

        // 下线帧同时触发 MODE_CHANGE（running→unknown）与 OFFLINE 两条事件
        ArgumentCaptor<RobotEventRecord> captor = ArgumentCaptor.forClass(RobotEventRecord.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).insert(captor.capture());
        RobotEventRecord offline = captor.getAllValues().stream()
                .filter(e -> "OFFLINE".equals(e.getReason()))
                .findFirst().orElseThrow();
        assertThat(offline.getSeverity()).isEqualTo("WARN");
        assertThat(offline.getMessage()).contains("15%");
    }

    // 电量变化在聚合窗口内：不插新行，更新最新聚合行（计数+1、新值刷新）
    @Test
    void batteryDeltaWithinWindow_aggregatesIntoLatestRow() {
        RobotEventRecord latest = event(7L, "R001", "BATTERY_DELTA", 20_000);
        when(repository.findLatest("R001", "BATTERY", "BATTERY_DELTA"))
                .thenReturn(Optional.of(latest));

        monitor.onFrame("R001", true, "idle", 80.4, true, "idle", 79.4);

        verify(repository, never()).insert(any(RobotEventRecord.class));
        ArgumentCaptor<RobotEventRecord> captor = ArgumentCaptor.forClass(RobotEventRecord.class);
        verify(repository).updateAggregated(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(7L);
        assertThat(captor.getValue().getEventCount()).isEqualTo(21);
        assertThat(captor.getValue().getNewValue()).isEqualTo("79%");
    }

    // 电量变化超出聚合窗口：插入新事件行而非聚合旧行
    @Test
    void batteryDeltaOutsideWindow_insertsNewRow() {
        when(repository.findLatest(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(event(7L, "R001", "BATTERY_DELTA", 31_000)));

        monitor.onFrame("R001", true, "idle", 80.4, true, "idle", 79.4);

        verify(repository).insert(any(RobotEventRecord.class));
        verify(repository, never()).updateAggregated(any());
    }

    // 电量跌破低电量阈值（20%）：独立记录 WARN 行，不并入聚合
    @Test
    void lowBatteryThresholdCrossing_recordsIndependentWarnRow() {
        monitor.onFrame("R001", true, "idle", 20.5, true, "idle", 19.8);

        ArgumentCaptor<RobotEventRecord> captor = ArgumentCaptor.forClass(RobotEventRecord.class);
        verify(repository).insert(captor.capture());
        assertThat(captor.getValue().getReason()).isEqualTo("BATTERY_LOW");
        assertThat(captor.getValue().getSeverity()).isEqualTo("WARN");
    }

    // 电量跌破严重阈值（10%）：记录 ERROR 级独立事件行
    @Test
    void criticalBatteryThreshold_recordsErrorSeverity() {
        monitor.onFrame("R001", true, "idle", 10.5, true, "idle", 9.9);

        ArgumentCaptor<RobotEventRecord> captor = ArgumentCaptor.forClass(RobotEventRecord.class);
        verify(repository).insert(captor.capture());
        assertThat(captor.getValue().getReason()).isEqualTo("BATTERY_CRITICAL");
        assertThat(captor.getValue().getSeverity()).isEqualTo("ERROR");
    }

    // 状态与电量均无明显变化：不插入也不更新任何事件
    @Test
    void unchangedState_recordsNothing() {
        monitor.onFrame("R001", true, "idle", 80.2, true, "idle", 80.4);

        verify(repository, never()).insert(any(RobotEventRecord.class));
        verify(repository, never()).updateAggregated(any());
    }

    // 在图/离图仅在翻转时记录：离图 WARN 带坐标原因、回图 INFO，持续状态不重复
    @Test
    void graphStateTransitions_recordedOnlyOnFlip() {
        // 首帧在图=初始态不记录；离图 WARN（带原因坐标）；持续离图不重复；回图 INFO
        monitor.onGraphState("R001", true, null);
        monitor.onGraphState("R001", false, "位置吸附失败: (9.17, 8.58)");
        monitor.onGraphState("R001", false, "位置吸附失败: (9.17, 8.58)");
        monitor.onGraphState("R001", true, null);

        ArgumentCaptor<RobotEventRecord> captor = ArgumentCaptor.forClass(RobotEventRecord.class);
        verify(repository, org.mockito.Mockito.times(2)).insert(captor.capture());
        var offGraph = captor.getAllValues().get(0);
        assertThat(offGraph.getEventType()).isEqualTo("GRAPH");
        assertThat(offGraph.getReason()).isEqualTo("OFF_GRAPH");
        assertThat(offGraph.getSeverity()).isEqualTo("WARN");
        assertThat(offGraph.getMessage()).contains("9.17");
        var onGraph = captor.getAllValues().get(1);
        assertThat(onGraph.getReason()).isEqualTo("ON_GRAPH");
        assertThat(onGraph.getSeverity()).isEqualTo("INFO");
    }

    // 地图标识不一致：ERROR 记录两侧地图名，恢复记 INFO，首帧一致/标识缺失不记录
    @Test
    void mapIdentityMismatch_recordsErrorAndRecovery() {
        // 不一致 ERROR（带两侧地图名）；恢复 INFO；未上报/持续一致不记录
        monitor.onMapIdentity("R001", "map1025L", "map1027S");   // 不一致
        monitor.onMapIdentity("R001", "map1025L", "map1027S");   // 无变化
        monitor.onMapIdentity("R001", null, "map1027S");          // 标识缺失不判
        monitor.onMapIdentity("R001", "map1027S", "map1027S");    // 恢复
        monitor.onMapIdentity("R002", "map1027S", "map1027S");    // 首帧一致：不记录

        ArgumentCaptor<RobotEventRecord> captor = ArgumentCaptor.forClass(RobotEventRecord.class);
        verify(repository, org.mockito.Mockito.times(2)).insert(captor.capture());
        var mismatch = captor.getAllValues().get(0);
        assertThat(mismatch.getEventType()).isEqualTo("MAP");
        assertThat(mismatch.getReason()).isEqualTo("MAP_MISMATCH");
        assertThat(mismatch.getSeverity()).isEqualTo("ERROR");
        assertThat(mismatch.getMessage()).contains("map1025L").contains("map1027S");
        var recovered = captor.getAllValues().get(1);
        assertThat(recovered.getReason()).isEqualTo("MAP_MATCHED");
    }

    // 事件仓储异常：异常被吞不向车队主链路传播
    @Test
    void repositoryFailure_doesNotPropagate() {
        when(repository.findLatest(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("db down"));

        monitor.onFrame("R001", true, "idle", 80.4, true, "idle", 79.4);
        // 不抛异常即通过（事件失败不影响车队主链路）
    }

    private RobotEventRecord event(long id, String robotCode, String reason, long lastAtAgo) {
        RobotEventRecord record = new RobotEventRecord();
        record.setId(id);
        record.setRobotCode(robotCode);
        record.setEventType("BATTERY");
        record.setReason(reason);
        record.setSeverity("INFO");
        record.setEventCount(20);
        record.setFirstAtMillis(lastAtAgo - 1000);
        record.setLastAtMillis(System.currentTimeMillis() - lastAtAgo);
        return record;
    }
}
