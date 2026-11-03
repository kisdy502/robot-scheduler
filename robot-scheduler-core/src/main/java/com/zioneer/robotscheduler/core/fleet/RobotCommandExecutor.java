package com.zioneer.robotscheduler.core.fleet;

import com.zioneer.robotscheduler.common.scheduler.SchedulerConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.zioneer.robotscheduler.core.repository.RobotRuntimeRepository;
import com.zioneer.robotscheduler.core.repository.InMemoryRobotRuntimeRepository;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 机器人命令执行器：命令下发（重试/每机器人串行）+ 完成回执匹配闭环
 * （对齐旧栈 robot_handle.py 的 attempt_cmd_until_success / completed_commands 匹配 / generation 取消）。
 *
 * <p>命令标识：cmdId（每机器人递增）与 commandUid（sched:robot:seq，写入 description 供 RMS 回传）；
 * 回执匹配顺序：rmf_command_uid 精确 → cmd_id → last_completed_request 兜底。</p>
 */
@Slf4j
@Component
public class RobotCommandExecutor {

    /** 回执成功状态集合（对齐旧栈 is_completion_successful）。 */
    private static final List<String> SUCCESS_STATUSES = List.of("COMPLETED", "SUCCESS", "SUCCEEDED");

    /** 回执失败状态集合（对齐旧栈 is_completion_failed）。 */
    private static final List<String> FAILED_STATUSES = List.of(
            "FAILED", "REJECTED", "TIMEOUT", "CANCELED", "CANCELLED", "ABORTED", "KILLED");

    private final RobotGateway gateway;
    private final RobotRuntimeRepository runtimeRepository;

    /** 每机器人当前待回执命令（串行：新命令提交前旧命令必须完成/取消）。 */
    private final Map<String, PendingCommand> pendingByRobot = new ConcurrentHashMap<>();

    /** 每机器人命令串行锁。 */
    private final Map<String, ReentrantLock> locksByRobot = new ConcurrentHashMap<>();

    public RobotCommandExecutor(RobotGateway gateway) {
        this(gateway, new InMemoryRobotRuntimeRepository());
    }

    @Autowired
    public RobotCommandExecutor(RobotGateway gateway,
            RobotRuntimeRepository runtimeRepository) {
        this.gateway = gateway;
        this.runtimeRepository = runtimeRepository;
    }

    /**
     * 下发导航命令（带重试）：返回该命令的完成 Future。
     *
     * @return future 正常完成=命令送达；随后回执经 onCompletedCommands 结束命令
     */
    public CommandHandle submitNavigation(String robotCode, String mapName, double x, double y, double yaw,
                                          Double speedLimit, Map<String, Object> description) {
        return submit(robotCode, "navigate",
                cmdId -> gateway.navigate(robotCode, cmdId, mapName, x, y, yaw, speedLimit, description));
    }

    /**
     * 下发业务动作命令：dock/quality_inspection/charge/swap_battery/pause_task/resume_task。
     */
    public CommandHandle submitActivity(String robotCode, String activity, String label,
                                        Map<String, Object> description) {
        return submit(robotCode, "start-activity",
                cmdId -> gateway.startActivity(robotCode, cmdId, activity, label, description));
    }

    /** 停止请求返回送达三态；成功接受后仍需状态帧确认实际停止。 */
    public RobotCmdResult stop(String robotCode, int runningCmdId, String bodyTaskId) {
        Integer cmdId = null;
        try {
            cmdId = nextCmdId(robotCode);
            RobotCmdResult result = gateway.stop(robotCode, runningCmdId, cmdId, bodyTaskId);
            if (result == null) result = RobotCmdResult.RETRY;
            log.info("停止请求: robot={} cmdId={} result={}", robotCode, cmdId, result);
            return result;
        } catch (Exception e) {
            log.warn("停止请求失败: robot={} cmdId={} error={}", robotCode, cmdId, e.getMessage());
            return RobotCmdResult.RETRY;
        }
    }

    /**
     * 状态帧回执处理：匹配当前 pending 命令 → 结束其 Future + ack 回执。
     *
     * @return true 若有待回执命令被本帧结束
     */
    public boolean onCompletedCommands(String robotCode, List<Map<String, Object>> completedCommands) {
        PendingCommand pending = pendingByRobot.get(robotCode);
        if (pending == null || completedCommands == null || completedCommands.isEmpty()) {
            return false;
        }
        for (Map<String, Object> completion : completedCommands) {
            if (!matches(pending, completion)) {
                continue;
            }
            String status = str(completion.get("status"));
            if (!SUCCESS_STATUSES.contains(normalize(status)) && !FAILED_STATUSES.contains(normalize(status))) {
                // 进行中或无法识别的状态不能结束命令，也不能 ack 后让 RMS 丢弃后续终态。
                continue;
            }
            String completionId = str(completion.get("id"));
            if (!pendingByRobot.remove(robotCode, pending)) return false;
            if (SUCCESS_STATUSES.contains(normalize(status))) {
                log.info("命令完成回执: robot={} uid={} status={}", robotCode, pending.uid(), status);
                pending.future().complete(CommandResult.ok(completion));
            } else {
                String detail = extractFailureDetail(completion);
                log.warn("命令失败回执: robot={} uid={} status={} detail={}", robotCode, pending.uid(),
                        status, detail);
                pending.future().complete(CommandResult.failed(detail == null
                        ? "命令回执失败: status=" + status : detail, completion));
            }
            if (completionId != null) {
                try {
                    gateway.ackCompletion(robotCode, completionId);
                } catch (RuntimeException failure) {
                    // 回执已匹配，ack 故障不能反向抹掉命令结果或中断后续机器人的状态融合。
                    log.warn("回执确认失败: robot_code={} cmd_id={} completion_id={} error={}",
                            robotCode, pending.cmdId(), completionId, failure.getMessage());
                }
            }
            return true;
        }
        return false;
    }

    /**
     * 失败原因提取：顶层 message/reject_reason（兼容）→ raw_result 嵌套（RMS 契约：
     * message/reject_reason/result_code）→ raw_result.data.msg（机器人 result_report 原文）。
     * 常见错误中文化（急停/停用），保留关键词可追溯。
     */
    private String extractFailureDetail(Map<String, Object> completion) {
        String detail = str(completion.get("message"));
        if (detail == null) {
            detail = str(completion.get("reject_reason"));
        }
        if (detail == null && completion.get("raw_result") instanceof Map<?, ?> rawMap) {
            detail = str(rawMap.get("message"));
            if (detail == null) {
                detail = str(rawMap.get("reject_reason"));
            }
            if (detail == null && rawMap.get("data") instanceof Map<?, ?> data) {
                detail = str(data.get("msg"));
            }
            if (detail != null) {
                String resultCode = str(rawMap.get("result_code"));
                if (resultCode != null) {
                    detail = detail + " [" + resultCode + "]";
                }
            }
        }
        return humanizeFailure(detail);
    }

    /** 常见拒绝原因中文化：RMS 返回英文原文，使用者需要一眼看懂。 */
    private String humanizeFailure(String detail) {
        if (detail == null) {
            return null;
        }
        if (detail.contains("emergency_stop")) {
            return "机器人急停中（emergency_stop），拒绝执行任务: " + detail;
        }
        if (detail.contains("decommissioned") && detail.contains("not accepting")) {
            return "机器人已停用（decommissioned），不接受新任务: " + detail;
        }
        if (detail.contains("已有在途任务")) {
            return detail + "（机器人导航栈有未完成目标，需等待或停止）";
        }
        return detail;
    }

    /** 当前是否有该机器人的待回执命令。 */
    public boolean hasPending(String robotCode) {
        return pendingByRobot.containsKey(robotCode);
    }

    /** 抛弃机器人当前 pending 命令（超时/取消时调用，后续迟到回执不再匹配）。 */
    public void abandon(String robotCode, String reason) {
        PendingCommand pending = pendingByRobot.remove(robotCode);
        if (pending != null) {
            pending.future().complete(CommandResult.failed(reason));
        }
    }

    private CommandHandle submit(String robotCode, String kind, CommandCall call) {
        ReentrantLock lock = locksByRobot.computeIfAbsent(robotCode, k -> new ReentrantLock());
        lock.lock();
        try {
            int cmdId = nextCmdId(robotCode);
            String uid = "sched:" + robotCode + ":" + cmdId;
            PendingCommand pending = new PendingCommand(robotCode, cmdId, uid, kind,
                    new CompletableFuture<>());
            if (pendingByRobot.putIfAbsent(robotCode, pending) != null) {
                pending.future().complete(CommandResult.failed("机器人已有待回执命令，拒绝覆盖: " + kind));
                return new CommandHandle(cmdId, uid, pending.future());
            }
            // 重试送达（RETRY → 3 次退避 1s；IMPOSSIBLE 立即失败）；cmdId 固定不随重试变化
            // 先登记再下发：完成帧可能先于 HTTP 响应到达，不能漏掉快速完成的命令。
            RobotCmdResult result;
            try {
                result = deliverWithRetry(robotCode, cmdId, call);
            } catch (RuntimeException failure) {
                pendingByRobot.remove(robotCode, pending);
                pending.future().complete(CommandResult.failed("命令下发异常: " + failure.getMessage()));
                throw failure;
            }
            if (result != RobotCmdResult.SUCCESS) {
                pendingByRobot.remove(robotCode, pending);
                String detail = result == RobotCmdResult.IMPOSSIBLE
                        ? "命令被拒绝（IMPOSSIBLE）: " + kind
                        : "命令重试耗尽（RETRY）: " + kind;
                pending.future().complete(CommandResult.failed(detail));
                return new CommandHandle(cmdId, uid, pending.future());
            }
            return new CommandHandle(cmdId, uid, pending.future());
        } finally {
            lock.unlock();
        }
    }

    private RobotCmdResult deliverWithRetry(String robotCode, int cmdId, CommandCall call) {
        RobotCmdResult result = null;
        for (int attempt = 1; attempt <= SchedulerConstants.COMMAND_RETRY_MAX_ATTEMPTS; attempt++) {
            result = call.invoke(cmdId);
            if (result == RobotCmdResult.SUCCESS || result == RobotCmdResult.IMPOSSIBLE) {
                return result;
            }
            if (attempt < SchedulerConstants.COMMAND_RETRY_MAX_ATTEMPTS) {
                try {
                    TimeUnit.MILLISECONDS.sleep(
                            (long) (SchedulerConstants.COMMAND_RETRY_BACKOFF_SECONDS * 1000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return RobotCmdResult.RETRY;
                }
            }
        }
        log.warn("命令重试耗尽: robot={} cmdId={} result={}", robotCode, cmdId, result);
        return result;
    }

    private boolean matches(PendingCommand pending, Map<String, Object> completion) {
        String completionUid = str(completion.get("rmf_command_uid"));
        if (completionUid != null && completionUid.equals(pending.uid())) {
            return true;
        }
        String completionCmdId = str(completion.get("cmd_id"));
        if (completionCmdId != null && completionCmdId.equals(String.valueOf(pending.cmdId()))) {
            return true;
        }
        Object lastRequest = completion.get("last_completed_request");
        return lastRequest != null && String.valueOf(lastRequest).equals(String.valueOf(pending.cmdId()));
    }

    private String normalize(String status) {
        return status == null ? "" : status.trim().toUpperCase();
    }

    private String str(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private int nextCmdId(String robotCode) {
        return runtimeRepository.nextCommandId(robotCode);
    }

    @FunctionalInterface
    private interface CommandCall {

        RobotCmdResult invoke(int cmdId);
    }

    /** 命令结果：携带失败原因与完整回执（质检结果等业务数据在 raw_result 里），供落库观测。 */
    public record CommandResult(boolean completed, String detail,
                                Map<String, Object> receipt) {

        public static CommandResult ok() {
            return new CommandResult(true, null, null);
        }

        public static CommandResult ok(Map<String, Object> receipt) {
            return new CommandResult(true, null, receipt);
        }

        public static CommandResult failed(String detail) {
            return new CommandResult(false, detail, null);
        }

        /** 失败结果携带回执（raw_result 落库观测：拒绝原因/机器人原文）。 */
        public static CommandResult failed(String detail, Map<String, Object> receipt) {
            return new CommandResult(false, detail, receipt);
        }
    }

    /** 命令句柄：uid 供 description 回传，future 在回执到达时结束。 */
    public record CommandHandle(int cmdId, String uid, CompletableFuture<CommandResult> future) {
    }

    private record PendingCommand(String robotCode, int cmdId, String uid, String kind,
                                  CompletableFuture<CommandResult> future) {
    }
}
