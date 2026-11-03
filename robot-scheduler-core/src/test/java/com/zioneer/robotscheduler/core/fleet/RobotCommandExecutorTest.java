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

    // 回执先于下发 HTTP 响应到达时，也必须匹配成功，不能留下永久等待的命令。
    @Test
    void completionBeforeDeliveryReturns_isNotLost() {
        when(gateway.navigate(anyString(), anyInt(), anyString(), anyDouble(), anyDouble(), anyDouble(),
                any(), any())).thenAnswer(call -> {
                    executor.onCompletedCommands("R001", List.of(Map.of(
                            "cmd_id", call.getArgument(1), "status", "COMPLETED")));
                    return RobotCmdResult.SUCCESS;
                });
        var handle = executor.submitNavigation("R001", "F1", 1, 2, 0, null, Map.of());
        assertThat(handle.future().join().completed()).isTrue();
        assertThat(executor.hasPending("R001")).isFalse();
    }

    // 中间状态不能被当成失败并确认消费，后续终态仍应正常完成同一命令。
    @Test
    void intermediateReceipt_doesNotFinishOrAckCommand() {
        var handle = executor.submitNavigation("R001", "F1", 1, 2, 0, null, Map.of());
        assertThat(executor.onCompletedCommands("R001", List.of(Map.of(
                "cmd_id", handle.cmdId(), "status", "RUNNING", "id", "intermediate")))).isFalse();
        assertThat(handle.future().isDone()).isFalse();
        org.mockito.Mockito.verify(gateway, org.mockito.Mockito.never()).ackCompletion(anyString(), anyString());
        executor.onCompletedCommands("R001", List.of(Map.of("cmd_id", handle.cmdId(), "status", "COMPLETED")));
        assertThat(handle.future().join().completed()).isTrue();
    }

    // ack 抛异常不能抹掉已经确认的执行结果，也不能让状态轮询因该车中断。
    @Test
    void ackFailure_keepsMatchedCommandResult() {
        var handle = executor.submitNavigation("R001", "F1", 1, 2, 0, null, Map.of());
        when(gateway.ackCompletion("R001", "receipt")).thenThrow(new IllegalStateException("ack unavailable"));
        assertThat(executor.onCompletedCommands("R001", List.of(Map.of(
                "cmd_id", handle.cmdId(), "status", "COMPLETED", "id", "receipt")))).isTrue();
        assertThat(handle.future().join().completed()).isTrue();
        assertThat(executor.hasPending("R001")).isFalse();
    }

    // 同一机器人仍有待回执命令时，第二次下发应拒绝，不能覆盖第一条命令的等待状态。
    @Test
    void pendingCommand_cannotBeOverwrittenByAnotherSubmission() {
        var first = executor.submitNavigation("R001", "F1", 1, 2, 0, null, Map.of());
        var second = executor.submitNavigation("R001", "F1", 3, 4, 0, null, Map.of());
        assertThat(second.future().join().completed()).isFalse();
        assertThat(first.future().isDone()).isFalse();
        executor.onCompletedCommands("R001", List.of(Map.of("cmd_id", first.cmdId(), "status", "COMPLETED")));
        assertThat(first.future().join().completed()).isTrue();
        org.mockito.Mockito.verify(gateway, org.mockito.Mockito.times(1)).navigate(anyString(), anyInt(),
                anyString(), anyDouble(), anyDouble(), anyDouble(), any(), any());
    }

    @BeforeEach
    void setUp() {
        executor = new RobotCommandExecutor(gateway);
        when(gateway.navigate(anyString(), anyInt(), anyString(), anyDouble(), anyDouble(), anyDouble(),
                any(), any())).thenReturn(RobotCmdResult.SUCCESS);
        when(gateway.startActivity(anyString(), anyInt(), anyString(), any(), any()))
                .thenReturn(RobotCmdResult.SUCCESS);
        when(gateway.ackCompletion(anyString(), anyString())).thenReturn(RobotCmdResult.SUCCESS);
    }

    // 导航命令按 cmd_id 匹配 COMPLETED 回执：future 成功完成并自动 ack RMS
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

    // 失败回执嵌套 raw_result.message：提取并人性化翻译（急停/错误码），结果携带回执
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

    // 失败回执降级提取 raw_result.data.msg：机器人原文进入 detail
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

    // 按 rmf_command_uid 匹配成功回执：future 成功完成
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

    // 失败回执（REJECTED）：future 以失败完成
    @Test
    void failureReceipt_completesFailed() {
        RobotCommandExecutor.CommandHandle handle = executor.submitActivity("R001", "dock", null, Map.of());

        executor.onCompletedCommands("R001", List.of(Map.of(
                "cmd_id", String.valueOf(handle.cmdId()),
                "status", "REJECTED",
                "message", "dock not supported")));

        assertThat(handle.future().join().completed()).isFalse();
    }

    // 回执 cmd_id 与在途命令不匹配：不消费该回执，命令保持 pending
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

    // 网关返回 RETRY：自动重试下发直至成功，命令仍 pending 等待回执
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

    // 网关返回 IMPOSSIBLE：立即失败不重试，pending 清除
    @Test
    void impossibleResult_failsImmediatelyWithoutRetry() {
        when(gateway.startActivity(anyString(), anyInt(), anyString(), any(), any()))
                .thenReturn(RobotCmdResult.IMPOSSIBLE);

        RobotCommandExecutor.CommandHandle handle = executor.submitActivity("R001", "teleop", null, Map.of());

        verify(gateway, times(1)).startActivity(eq("R001"), anyInt(), eq("teleop"), isNull(), any());
        assertThat(handle.future().join().completed()).isFalse();
        assertThat(executor.hasPending("R001")).isFalse();
    }

    // abandon 主动放弃：future 以失败完成且 pending 清除
    @Test
    void abandon_completesFailedAndClearsPending() {
        RobotCommandExecutor.CommandHandle handle = executor.submitActivity("R001", "dock", null, Map.of());

        executor.abandon("R001", "test abandon");

        assertThat(handle.future().join().completed()).isFalse();
        assertThat(executor.hasPending("R001")).isFalse();
    }

    // stop 透传网关送达结果（各枚举原样返回）：网络异常映射为 RETRY 可重试
    @Test
    void stop_returnsDeliveryResult_andNetworkFailureIsRetryable() {
        for (RobotCmdResult result : RobotCmdResult.values()) {
            when(gateway.stop(eq("R001"), anyInt(), anyInt(), isNull())).thenReturn(result);
            assertThat(executor.stop("R001", 0, null)).isEqualTo(result);
        }
        when(gateway.stop(eq("R001"), anyInt(), anyInt(), isNull()))
                .thenThrow(new IllegalStateException("network unavailable"));
        assertThat(executor.stop("R001", 0, null)).isEqualTo(RobotCmdResult.RETRY);
    }

    // stop 下发：stop_cmd_id 取递增 seq，body_task_id 透传
    @Test
    void stop_dispatchesStopCommandWithBodyTaskId() {
        executor.submitNavigation("R001", "F1", 0, 0, 0, null, Map.of());

        executor.stop("R001", 1, "biz-task-9");

        // stop_cmd_id 递增（下一个 seq），body_task_id 透传
        verify(gateway).stop(eq("R001"), eq(1), anyInt(), eq("biz-task-9"));
    }

    // cmd_id 按机器人单调递增：第二条命令 cmd_id=2 且 uid 同步为 sched:R001:2
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

    // abandon 后旧命令迟到回执：与新在途命令 cmd_id 不匹配，新命令不受影响
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
