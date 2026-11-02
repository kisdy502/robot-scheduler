package com.zioneer.robotscheduler.core.assign;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 分配决策（含评分明细，分配透明化）：
 * 候选代价 = ETA + 电量惩罚 + 忙碌惩罚，取最小者；
 * candidates 按总分升序，selected 标记胜出者。
 */
public record AssignmentDecision(Optional<AssignmentCandidate> selected, List<AssignmentCandidate> candidates) {

    /** 单候选评分快照。 */
    public record AssignmentCandidate(String robotCode, String robotName, Double batteryPercent,
                                      String mode, double etaSeconds, double batteryPenaltySeconds,
                                      double busyPenaltySeconds, double totalCost, boolean selected) {

        public Map<String, Object> toWireJson() {
            return new java.util.LinkedHashMap<>(Map.of(
                    "robot_code", robotCode,
                    "robot_name", robotName == null ? "" : robotName,
                    "battery_percent", batteryPercent == null ? -1 : batteryPercent,
                    "mode", mode == null ? "" : mode,
                    "eta_seconds", Math.round(etaSeconds * 10) / 10.0,
                    "battery_penalty_seconds", Math.round(batteryPenaltySeconds * 10) / 10.0,
                    "busy_penalty_seconds", Math.round(busyPenaltySeconds * 10) / 10.0,
                    "total_cost", Math.round(totalCost * 10) / 10.0,
                    "selected", selected));
        }
    }

    /** 评分明细 wire JSON（事件 detail.assignment_trace 用）。 */
    public List<Map<String, Object>> traceJson() {
        return candidates.stream().map(AssignmentCandidate::toWireJson).toList();
    }

    /** 摘要文案（事件 message / 日志用）。 */
    public String summary() {
        return selected.map(win -> {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("自动分配给 %s（%s）：ETA %.0fs，电量 %s，总分 %.0f",
                    win.robotName() == null || win.robotName().isBlank() ? win.robotCode() : win.robotName(),
                    win.robotCode(), win.etaSeconds(),
                    win.batteryPercent() == null ? "未知" : String.format("%.0f%%", win.batteryPercent()),
                    win.totalCost()));
            if (candidates.size() > 1) {
                sb.append("，").append(candidates.size()).append(" 台候选中最优");
            }
            return sb.toString();
        }).orElse("无可用候选");
    }
}
