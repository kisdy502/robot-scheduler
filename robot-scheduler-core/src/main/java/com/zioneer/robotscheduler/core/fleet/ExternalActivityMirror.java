package com.zioneer.robotscheduler.core.fleet;

import com.zioneer.robotscheduler.core.repository.ExternalActivityRecord;
import com.zioneer.robotscheduler.core.repository.ExternalActivityRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 外部动作回执镜像（观测站，非编排）：
 * RMS 状态帧回执信箱里非调度下发（rmf_command_uid 无 sched: 前缀）的回执 → 存档。
 * 上层应用负责 ack（清空信箱）；未 ack 的重复投递按 rms_command_id 幂等去重。
 * 镜像失败只告警，绝不影响车队主链路。
 */
@Slf4j
@Component
public class ExternalActivityMirror {

    /** 调度下发命令的 uid 前缀（RobotCommandExecutor 约定）。 */
    static final String SCHEDULER_UID_PREFIX = "sched:";

    private final ExternalActivityRepository repository;

    public ExternalActivityMirror() {
        this(new com.zioneer.robotscheduler.core.repository.InMemoryExternalActivityRepository());
    }

    @Autowired
    public ExternalActivityMirror(ExternalActivityRepository repository) {
        this.repository = repository;
    }

    /**
     * 回执信箱镜像入口（FleetManager 每帧调用，先于调度自身命令认领）。
     *
     * @param pendingCmdId 调度当前待回执命令的 cmdId（无则 null）——真机回执不回显
     *                     sched: 前缀 uid，需按 cmd_id 二次排除调度自身的命令
     * @return 本帧中属于外部（上层应用）命令的回执 ID 列表（含已镜像过的重复投递），
     *         供调用方兜底 ack 清空信箱——上层应用可能中止/离场未读，不能让信箱永久积压
     */
    public List<String> onReceipts(String robotCode, List<Map<String, Object>> completedCommands, Integer pendingCmdId) {
        if (robotCode == null || completedCommands == null || completedCommands.isEmpty()) {
            return List.of();
        }
        List<String> externalIds = new java.util.ArrayList<>(completedCommands.size());
        for (Map<String, Object> completion : completedCommands) {
            try {
                if (mirrorOne(robotCode, completion, pendingCmdId)) {
                    externalIds.add(str(completion.get("id")));
                }
            } catch (Exception e) {
                log.warn("外部动作镜像失败: robot={} error={}", robotCode, e.getMessage());
            }
        }
        return externalIds;
    }

    /**
     * @return true 表示该回执属于外部命令（无论是否新入库，重复投递也算外部）
     */
    private boolean mirrorOne(String robotCode, Map<String, Object> completion, Integer pendingCmdId) {
        String uid = str(completion.get("rmf_command_uid"));
        if (uid != null && uid.startsWith(SCHEDULER_UID_PREFIX)) {
            return false;
        }
        // 调度自身命令（mock 回显 sched: 前缀 uid；真机生成自有 uid，按 pending cmd_id 匹配排除）
        if (pendingCmdId != null) {
            String completionCmdId = str(completion.get("cmd_id"));
            if (String.valueOf(pendingCmdId).equals(completionCmdId)) {
                return false;
            }
        }
        String rmsCommandId = str(completion.get("id"));
        if (rmsCommandId == null) {
            return false;
        }
        if (!repository.existsByRmsCommandId(rmsCommandId)) {
            ExternalActivityRecord record = buildRecord(robotCode, completion, rmsCommandId);
            repository.insert(record);
            log.info("外部动作回执镜像: robot={} body_task_id={} action={} status={} msg={}",
                    robotCode, record.getBodyTaskId(), record.getActionType(),
                    record.getActionStatus(), record.getFailureMessage());
        }
        return true;
    }

    private ExternalActivityRecord buildRecord(String robotCode, Map<String, Object> completion, String rmsCommandId) {
        ExternalActivityRecord record = new ExternalActivityRecord();
        record.setRobotCode(robotCode);
        record.setRmsCommandId(rmsCommandId);
        record.setBodyTaskId(str(completion.get("body_task_id")));
        if (completion.get("raw_result") instanceof Map<?, ?> raw) {
            if (raw.get("data") instanceof Map<?, ?> data) {
                record.setActionType(str(data.get("action_type")));
                Object status = data.get("action_status");
                record.setActionStatus(status == null ? null : (int) Double.parseDouble(String.valueOf(status)));
                if (data.get("result_report") instanceof Map<?, ?> report) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> reportMap = (Map<String, Object>) report;
                    record.setResultReport(reportMap);
                }
                // 失败原因（如"任务模板不存在"）——上层从镜像读结果时能看到失败细节
                String msg = str(data.get("msg"));
                if (msg != null && record.getActionStatus() != null && record.getActionStatus() != 3) {
                    record.setFailureMessage(msg);
                }
            }
            // RMS 契约：timestamp 在 raw_result 根层（机器人上报时间）
            Object ts = raw.get("timestamp");
            if (ts != null) {
                record.setRobotReportedMillis((long) Double.parseDouble(String.valueOf(ts)));
            }
        }
        record.setCreatedAtMillis(System.currentTimeMillis());
        return record;
    }

    private String str(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }
}
