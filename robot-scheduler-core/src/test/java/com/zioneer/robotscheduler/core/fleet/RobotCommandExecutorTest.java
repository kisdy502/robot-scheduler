package com.zioneer.robotscheduler.core.fleet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 机器人命令执行器测试：重试/回执匹配（uid/cmd_id）/失败回执/ack/abandon/stop。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RobotCommandExecutorTest {

    @Mock
    private RobotGateway gateway;

    private RobotCommandExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new RobotCommandExecutor(gateway);
        when(gateway.navigate(anyString(), anyInt(), anyString(), anyDouble(), anyDouble(), anyDouble(),
                any(), any())).thenReturn(RobotCmdResult.SUCCESS);
        when(gateway.startActivity(anyString(), anyInt(), anyString(), any(), any()))
                .thenReturn(RobotCmdResult.SUCCESS);
        when(gateway.ackCompletion(anyString(), anyString())).thenReturn(RobotCmdResult.SUCCESS);
    }

    @Test
    void submitNavigation_success_completionByCmdIdCompletesFutureAndAcks() {
        RobotCommandExecutor.CommandHandle handle = executor.submitNavigation("R001", "F1", 1, 2, 3,
                null, Map.of());

        assertThat(handle.uid()).isEqualTo("sched:R001:1");
        boolean matched = executor.onCompletedCommands("R001", List.of(Map.of(
                "cmd_id", "1",
                "status", "COMPLETED",
                "id", "cmp-100")));

        assertThat(matched).isTrue();
        assertThat(handle.future().join().completed()).isTrue();
        verify(gateway).ackCompletion("R001", "cmp-100");
    }

    @Test
    void failureReceipt_rawResultNestedMessage_extractedAndHumanized() {
        // RMS 契约：message/reject_reason/result_code 在 raw_result 嵌套对象里
        RobotCommandExecutor.CommandHandle handle = executor.submitNavigation("R001", "F1", 1, 2, 3,
                null, Map.of());

        executor.onCompletedCommands("R001", List.of(Map.of(
                "cmd_id", "1",
                "status", "REJECTED",
                "id", "cmp-200",
                "raw_result", Map.of(
                        "message", "robot is decommissioned (emergency_stop), not accepting tasks",
                        "reject_reason", "robot is decommissioned (emergency_stop), not accepting tasks",
                        "result_code", "BODY_START_TASK_REJECTED"))));

        RobotCommandExecutor.CommandResult result = handle.future().join();
        assertThat(result.completed()).isFalse();
        assertThat(result.detail()).contains("急停").contains("emergency_stop");
        assertThat(result.detail()).contains("BODY_START_TASK_REJECTED");
        // 失败回执也携带（落库观测）
        assertThat(result.receipt()).isNotNull();
    }

    @Test
    void failureReceipt_robotDataMsg_extractedAsFallback() {
        // 机器人 result_report 原文在 raw_result.data.msg
        RobotCommandExecutor.CommandHandle handle = executor.submitNavigation("R001", "F1", 1, 2, 3,
                null, Map.of());

        executor.onCompletedCommands("R001", List.of(Map.of(
                "cmd_id", "1",
                "status", "FAILED",
                "id", "cmp-201",
                "raw_result", Map.of(
                        "data", Map.of("msg", "已有在途任务（1 条），机器人同一时刻只允许一个 goal")))));

        RobotCommandExecutor.CommandResult result = handle.future().join();
        assertThat(result.completed()).isFalse();
        assertThat(result.detail()).contains("已有在途任务");
    }

    @Test
    void completionByCommandUid_matches() {
        RobotCommandExecutor.CommandHandle handle = executor.submitActivity("R001", "charge", "tf-1", Map.of());

        boolean matched = executor.onCompletedCommands("R001", List.of(Map.of(
                "rmf_command_uid", handle.uid(),
                "status", "SUCCEEDED",
                "id", "cmp-101")));

        assertThat(matched).isTrue();
        assertThat(handle.future().join().completed()).isTrue();
    }

    @Test
    void failureReceipt_completesFailed() {
        RobotCommandExecutor.CommandHandle handle = executor.submitActivity("R001", "dock", null, Map.of());

        executor.onCompletedCommands("R001", List.of(Map.of(
                "cmd_id", String.valueOf(handle.cmdId()),
                "status", "REJECTED",
                "message", "dock not supported")));

        assertThat(handle.future().join().completed()).isFalse();
    }

    @Test
    void nonMatchingReceipt_keepsPending() {
        RobotCommandExecutor.CommandHandle handle = executor.submitActivity("R001", "dock", null, Map.of());

        boolean matched = executor.onCompletedCommands("R001", List.of(Map.of(
                "cmd_id", "999",
                "status", "COMPLETED",
                "id", "cmp-x")));

        assertThat(matched).isFalse();
        assertThat(handle.future()).isNotDone();
    }

    @Test
    void retryOnRetryableResult_untilSuccess() {
        when(gateway.navigate(anyString(), anyInt(), anyString(), anyDouble(), anyDouble(), anyDouble(),
                any(), any()))
                .thenReturn(RobotCmdResult.RETRY)
                .thenReturn(RobotCmdResult.RETRY)
                .thenReturn(RobotCmdResult.SUCCESS);

        RobotCommandExecutor.CommandHandle handle = executor.submitNavigation("R001", "F1", 0, 0, 0, null, Map.of());

        verify(gateway, times(3)).navigate(eq("R001"), anyInt(), eq("F1"), anyDouble(), anyDouble(),
                anyDouble(), isNull(), any());
        assertThat(executor.hasPending("R001")).isTrue();
    }

    @Test
    void impossibleResult_failsImmediatelyWithoutRetry() {
        when(gateway.startActivity(anyString(), anyInt(), anyString(), any(), any()))
                .thenReturn(RobotCmdResult.IMPOSSIBLE);

        RobotCommandExecutor.CommandHandle handle = executor.submitActivity("R001", "teleop", null, Map.of());

        verify(gateway, times(1)).startActivity(eq("R001"), anyInt(), eq("teleop"), isNull(), any());
        assertThat(handle.future().join().completed()).isFalse();
        assertThat(executor.hasPending("R001")).isFalse();
    }

    @Test
    void abandon_completesFailedAndClearsPending() {
        RobotCommandExecutor.CommandHandle handle = executor.submitActivity("R001", "dock", null, Map.of());

        executor.abandon("R001", "test abandon");

        assertThat(handle.future().join().completed()).isFalse();
        assertThat(executor.hasPending("R001")).isFalse();
    }

    @Test
    void stop_dispatchesStopCommandWithBodyTaskId() {
        executor.submitNavigation("R001", "F1", 0, 0, 0, null, Map.of());

        executor.stop("R001", 1, "biz-task-9");

        // stop_cmd_id 递增（下一个 seq），body_task_id 透传
        verify(gateway).stop(eq("R001"), eq(1), anyInt(), eq("biz-task-9"));
    }

    @Test
    void cmdId_incrementsPerRobot() {
        RobotCommandExecutor.CommandHandle first = executor.submitActivity("R001", "dock", null, Map.of());
        executor.onCompletedCommands("R001", List.of(Map.of(
                "cmd_id", String.valueOf(first.cmdId()), "status", "COMPLETED", "id", "c1")));
        RobotCommandExecutor.CommandHandle second = executor.submitActivity("R001", "dock", null, Map.of());

        assertThat(first.cmdId()).isEqualTo(1);
        assertThat(second.cmdId()).isEqualTo(2);
        assertThat(second.uid()).isEqualTo("sched:R001:2");
    }

    @Test
    void lateReceipt_afterAbandon_doesNotAffectNewCommand() {
        RobotCommandExecutor.CommandHandle first = executor.submitActivity("R001", "dock", null, Map.of());
        executor.abandon("R001", "test abandon");
        RobotCommandExecutor.CommandHandle second = executor.submitActivity("R001", "dock", null, Map.of());

        // 旧命令的迟到回执（cmd_id=1）：当前 pending 是 cmd_id=2，不匹配 → 新命令不受影响
        boolean matched = executor.onCompletedCommands("R001", List.of(Map.of(
                "cmd_id", String.valueOf(first.cmdId()), "status", "FAILED", "id", "late")));

        assertThat(matched).isFalse();
        assertThat(second.future()).isNotDone();
    }
}
