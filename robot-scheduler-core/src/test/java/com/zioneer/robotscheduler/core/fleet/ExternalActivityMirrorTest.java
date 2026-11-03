package com.zioneer.robotscheduler.core.fleet;

import com.zioneer.robotscheduler.core.repository.ExternalActivityRecord;
import com.zioneer.robotscheduler.core.repository.InMemoryExternalActivityRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 外部动作回执镜像测试：过滤调度自身命令 / 幂等去重 / 字段映射（timestamp 在 raw_result 根层）。
 */
class ExternalActivityMirrorTest {

    private final InMemoryExternalActivityRepository repository = new InMemoryExternalActivityRepository();
    private final ExternalActivityMirror mirror = new ExternalActivityMirror(repository);

    // 调度自身命令的回执（uid 带 sched: 前缀）不进镜像——那是任务执行链路自己的事
    @Test
    void schedulerOwnedReceipts_areNotMirrored() {
        mirror.onReceipts("R1", List.of(Map.of(
                "id", "41937",
                "rmf_command_uid", "sched:R1:1264",
                "body_task_id", "zone-rest-1",
                "status", "COMPLETED")));
        assertThat(repository.count(null, null)).isZero();
    }

    // 上层应用直连 RMS 的回执存档：action_type/status/result_report 从 raw_result.data 取，
    // timestamp 从 raw_result 根层取（RMS 契约）
    @Test
    void externalReceipt_isMirroredWithFieldMapping() {
        mirror.onReceipts("R1", List.of(Map.of(
                "id", "42004",
                "rmf_command_uid", "R1:qcsim-qi-1:1",
                "body_task_id", "qcsim-qi-1",
                "status", "COMPLETED",
                "raw_result", Map.of(
                        "status", 0,
                        "timestamp", 1793669356069L,
                        "data", Map.of(
                                "action_type", "action",
                                "action_status", 3,
                                "result_report", Map.of("inspection_result", "NG", "wireharness_type", "WH-HV-B01"))))));
        assertThat(repository.count(null, null)).isEqualTo(1);
        ExternalActivityRecord record = repository.findPage(null, null, 0, 10).get(0);
        assertThat(record.getRobotCode()).isEqualTo("R1");
        assertThat(record.getBodyTaskId()).isEqualTo("qcsim-qi-1");
        assertThat(record.getRmsCommandId()).isEqualTo("42004");
        assertThat(record.getActionType()).isEqualTo("action");
        assertThat(record.getActionStatus()).isEqualTo(3);
        assertThat(record.getRobotReportedMillis()).isEqualTo(1793669356069L);
        assertThat(record.getResultReport()).containsEntry("inspection_result", "NG");
    }

    // RMS 未被 ack 前重复投递同一回执 → 按 rms_command_id 幂等去重
    @Test
    void duplicateReceipt_isDeduplicated() {
        Map<String, Object> receipt = Map.of(
                "id", "42005",
                "rmf_command_uid", "R1:qcsim-qi-2:1",
                "body_task_id", "qcsim-qi-2",
                "status", "COMPLETED");
        mirror.onReceipts("R1", List.of(receipt));
        mirror.onReceipts("R1", List.of(receipt));
        mirror.onReceipts("R1", List.of(receipt));
        assertThat(repository.count(null, null)).isEqualTo(1);
    }

    // 空入参直接返回；缺 raw_result 的裸回执也存档（result_report 留空）
    @Test
    void bareReceiptWithoutRawResult_isMirroredSafely() {
        mirror.onReceipts("R1", List.of());
        mirror.onReceipts("R1", null);
        mirror.onReceipts(null, List.of(Map.of("id", "1")));
        mirror.onReceipts("R1", List.of(Map.of(
                "id", "42006",
                "rmf_command_uid", "R1:qcsim-qi-3:1",
                "body_task_id", "qcsim-qi-3",
                "status", "FAILED")));
        assertThat(repository.count(null, null)).isEqualTo(1);
        assertThat(repository.findPage(null, null, 0, 10).get(0).getResultReport()).isNull();
    }
}
