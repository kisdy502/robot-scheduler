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
     */
    public void onReceipts(String robotCode, List<Map<String, Object>> completedCommands) {
        if (robotCode == null || completedCommands == null || completedCommands.isEmpty()) {
            return;
        }
        for (Map<String, Object> completion : completedCommands) {
            try {
                mirrorOne(robotCode, completion);
            } catch (Exception e) {
                log.warn("外部动作镜像失败: robot={} error={}", robotCode, e.getMessage());
            }
        }
    }

    private void mirrorOne(String robotCode, Map<String, Object> completion) {
        String uid = str(completion.get("rmf_command_uid"));
        if (uid != null && uid.startsWith(SCHEDULER_UID_PREFIX)) {
            return;
        }
        String rmsCommandId = str(completion.get("id"));
        if (rmsCommandId == null || repository.existsByRmsCommandId(rmsCommandId)) {
            return;
        }
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
            }
            // RMS 契约：timestamp 在 raw_result 根层（机器人上报时间）
            Object ts = raw.get("timestamp");
            if (ts != null) {
                record.setRobotReportedMillis((long) Double.parseDouble(String.valueOf(ts)));
            }
        }
        record.setCreatedAtMillis(System.currentTimeMillis());
        repository.insert(record);
        log.info("外部动作回执镜像: robot={} body_task_id={} action={} status={}",
                robotCode, record.getBodyTaskId(), record.getActionType(), record.getActionStatus());
    }

    private String str(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }
}
