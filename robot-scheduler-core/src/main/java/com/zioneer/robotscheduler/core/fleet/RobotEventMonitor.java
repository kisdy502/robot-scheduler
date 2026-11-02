package com.zioneer.robotscheduler.core.fleet;

import com.zioneer.robotscheduler.core.repository.RobotEventRecord;
import com.zioneer.robotscheduler.core.repository.RobotEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 机器人事件监测器（K8s Events 风格）：
 * 帧处理时对比前帧，记录 状态变化 / 电量变化 / 上下线 三类事件。
 *
 * <p>节流聚合：同类事件（同 robot + type + reason）在聚合窗口内不插新行，
 * count++ 并刷新 last_at——电量 1% 流水与充电高速刷新（mock 10%/s）不刷屏；
 * 阈值穿越（低电 20%/极低 10%/满充 98%）用独立 reason，必产生新事件行。</p>
 *
 * <p>保留期 7 天，小时级 GC。事件失败不影响车队主链路。</p>
 */
@Slf4j
@Component
public class RobotEventMonitor {

    /** 电量变化聚合窗口（毫秒）。 */
    long batteryAggregationWindowMillis = 30_000;

    /** 机器人图状态（离图/回图事件去重：只在翻转时记录）。 */
    private final java.util.Map<String, Boolean> onGraphByRobot = new java.util.concurrent.ConcurrentHashMap<>();

    /** 机器人地图一致性状态（不一致/恢复事件去重）。 */
    private final java.util.Map<String, Boolean> mapMatchByRobot = new java.util.concurrent.ConcurrentHashMap<>();

    /** 低电预警阈值（百分比）。 */
    double lowBatteryThresholdPercent = 20.0;

    /** 极低电阈值（百分比）。 */
    double criticalBatteryThresholdPercent = 10.0;

    /** 满充阈值（百分比）。 */
    double fullBatteryThresholdPercent = 98.0;

    /** 事件保留期（毫秒，7 天）。 */
    static final long RETENTION_MILLIS = 7L * 24 * 3600 * 1000;

    private final RobotEventRepository repository;

    public RobotEventMonitor(RobotEventRepository repository) {
        this.repository = repository;
    }

    /**
     * 帧变化检测入口（FleetManager 每帧调用；prev* 为 null 表示首帧，跳过对应检测）。
     */
    public void onFrame(String robotCode, Boolean prevOnline, String prevMode, Double prevBattery,
                        boolean online, String mode, Double battery) {
        try {
            if (robotCode == null || robotCode.isBlank()) {
                return;
            }
            detectOnline(robotCode, prevOnline, online, prevBattery, battery);
            detectMode(robotCode, prevMode, mode);
            detectBattery(robotCode, prevBattery, battery);
        } catch (Exception e) {
            log.warn("机器人事件记录失败: robot={} error={}", robotCode, e.getMessage());
        }
    }

    private void detectOnline(String robotCode, Boolean prevOnline, boolean online,
                              Double prevBattery, Double battery) {
        if (prevOnline == null || prevOnline == online) {
            return;
        }
        record(robotCode, "ONLINE", online ? "ONLINE" : "OFFLINE", online ? "INFO" : "WARN",
                String.valueOf(prevOnline), String.valueOf(online),
                online ? "机器人上线" : "机器人下线" + lowBatteryHint(battery), false);
    }

    private void detectMode(String robotCode, String prevMode, String mode) {
        if (prevMode == null || prevMode.isBlank() || mode == null || mode.isBlank()
                || prevMode.equals(mode)) {
            return;
        }
        String severity = "error".equals(mode) ? "ERROR" : "INFO";
        record(robotCode, "MODE", "MODE_CHANGE", severity, prevMode, mode,
                "状态变化: " + zhMode(prevMode) + " → " + zhMode(mode), false);
    }

    private void detectBattery(String robotCode, Double prevBattery, Double battery) {
        if (prevBattery == null || battery == null) {
            return;
        }
        int prevPercent = (int) Math.floor(prevBattery);
        int percent = (int) Math.floor(battery);
        // 阈值穿越：原始值比较（取整会吞掉 20.5→19.8 这类穿越），独立 reason，不聚合
        if (battery <= criticalBatteryThresholdPercent && prevBattery > criticalBatteryThresholdPercent) {
            record(robotCode, "BATTERY", "BATTERY_CRITICAL", "ERROR",
                    prevPercent + "%", percent + "%", "电量极低（≤" + (int) criticalBatteryThresholdPercent + "%）", false);
        } else if (battery <= lowBatteryThresholdPercent && prevBattery > lowBatteryThresholdPercent) {
            record(robotCode, "BATTERY", "BATTERY_LOW", "WARN",
                    prevPercent + "%", percent + "%", "低电预警（≤" + (int) lowBatteryThresholdPercent + "%）", false);
        } else if (battery >= fullBatteryThresholdPercent && prevBattery < fullBatteryThresholdPercent) {
            record(robotCode, "BATTERY", "BATTERY_FULL", "INFO",
                    prevPercent + "%", percent + "%", "满充（≥" + (int) fullBatteryThresholdPercent + "%）", false);
        } else if (prevPercent != percent) {
            // 常规 ±1% 变化：窗口内聚合计数
            String direction = percent > prevPercent ? "充电中" : "放电中";
            record(robotCode, "BATTERY", "BATTERY_DELTA", "INFO", prevPercent + "%", percent + "%",
                    "电量变化 " + direction, true);
        }
    }

    /** 下线时电量太低给出提示（排查"饿死"场景）。 */
    private String lowBatteryHint(Double battery) {
        if (battery != null && battery <= lowBatteryThresholdPercent) {
            return String.format("（电量 %.0f%%，低于安全线）", battery);
        }
        return "";
    }

    private String zhMode(String mode) {
        return switch (mode) {
            case "idle" -> "空闲";
            case "running" -> "作业中";
            case "charging" -> "充电中";
            case "waiting" -> "等待";
            case "paused" -> "暂停";
            case "error" -> "故障";
            case "unknown" -> "未知";
            default -> mode;
        };
    }

    /**
     * @param aggregatable true 时窗口内同类事件 count 聚合（电量流水防刷屏）
     */
    private void record(String robotCode, String eventType, String reason, String severity,
                        String oldValue, String newValue, String message, boolean aggregatable) {
        long now = System.currentTimeMillis();
        if (aggregatable) {
            Optional<RobotEventRecord> latest = repository.findLatest(robotCode, eventType, reason);
            if (latest.isPresent() && latest.get().getLastAtMillis() != null
                    && now - latest.get().getLastAtMillis() < batteryAggregationWindowMillis) {
                RobotEventRecord merged = latest.get();
                merged.setEventCount(merged.getEventCount() + 1);
                merged.setNewValue(newValue);
                merged.setMessage(message);
                merged.setLastAtMillis(now);
                repository.updateAggregated(merged);
                return;
            }
        }
        RobotEventRecord event = new RobotEventRecord();
        event.setRobotCode(robotCode);
        event.setEventType(eventType);
        event.setReason(reason);
        event.setSeverity(severity);
        event.setOldValue(oldValue);
        event.setNewValue(newValue);
        event.setMessage(message);
        event.setEventCount(1);
        event.setFirstAtMillis(now);
        event.setLastAtMillis(now);
        repository.insert(event);
    }

    /**
     * 图拓扑状态变化（离图/回图）：只在状态翻转时记录事件（前端可感知，不只进后端日志）。
     *
     * @param detail 离图原因（楼层不匹配/位置吸附失败等，含坐标）
     */
    public void onGraphState(String robotCode, boolean onGraph, String detail) {
        try {
            Boolean prev = onGraphByRobot.get(robotCode);
            if (prev != null && prev == onGraph) {
                return;
            }
            onGraphByRobot.put(robotCode, onGraph);
            if (!onGraph) {
                record(robotCode, "GRAPH", "OFF_GRAPH", "WARN", "在图", "离图",
                        "机器人离图，临时不可派发（" + detail + "），回到图上自动恢复", false);
            } else if (prev != null) {
                // 首帧视为初始态不记录，仅真实的 离图→回图 翻转记录恢复
                record(robotCode, "GRAPH", "ON_GRAPH", "INFO", "离图", "在图",
                        "机器人回到地图拓扑，恢复可派发", false);
            }
        } catch (Exception e) {
            log.warn("机器人图状态事件记录失败: robot={} error={}", robotCode, e.getMessage());
        }
    }

    /**
     * 地图疑似不一致（行为校验：到达回执后实际上报位置与目标点偏差超阈值）。
     * 典型场景：机器人 SLAM 加载了同名不同版本的地图，点位坐标错位 → 乱走乱撞。
     */
    public void onMapSuspect(String robotCode, String detail) {
        try {
            record(robotCode, "MAP", "MAP_SUSPECT", "ERROR", null, null,
                    "地图疑似不一致: " + detail, false);
        } catch (Exception e) {
            log.warn("地图疑似不一致事件记录失败: robot={} error={}", robotCode, e.getMessage());
        }
    }

    /**
     * 地图一致性变化（机器人 SLAM 地图标识 vs 调度激活地图）：不一致 ERROR + 禁止派发，恢复 INFO。
     * 标识未上报（旧协议）不判定。变化去重：只在状态翻转时记录。
     */
    public void onMapIdentity(String robotCode, String slamMapCode, String activeMapName) {
        try {
            if (robotCode == null || robotCode.isBlank()
                    || slamMapCode == null || slamMapCode.isBlank()
                    || activeMapName == null || activeMapName.isBlank()) {
                return;
            }
            boolean match = slamMapCode.equals(activeMapName);
            Boolean prev = mapMatchByRobot.get(robotCode);
            if (prev != null && prev == match) {
                return;
            }
            mapMatchByRobot.put(robotCode, match);
            if (!match) {
                record(robotCode, "MAP", "MAP_MISMATCH", "ERROR", slamMapCode, activeMapName,
                        "机器人地图与调度不一致（机器人: " + slamMapCode + " vs 调度: " + activeMapName
                                + "），已禁止派发；请检查机器人 SLAM 加载地图", false);
            } else if (prev != null) {
                record(robotCode, "MAP", "MAP_MATCHED", "INFO", null, activeMapName,
                        "机器人地图与调度一致（" + activeMapName + "），恢复可派发", false);
            }
        } catch (Exception e) {
            log.warn("地图一致性事件记录失败: robot={} error={}", robotCode, e.getMessage());
        }
    }

    /** 事件保留期 GC（1h 周期，保留 7 天）。 */
    @Scheduled(fixedDelay = 3_600_000)
    public void gc() {
        try {
            long deleted = repository.deleteOlderThan(System.currentTimeMillis() - RETENTION_MILLIS);
            if (deleted > 0) {
                log.info("机器人事件 GC: 删除 {} 条过期记录", deleted);
            }
        } catch (Exception e) {
            log.warn("机器人事件 GC 失败: {}", e.getMessage());
        }
    }
}
