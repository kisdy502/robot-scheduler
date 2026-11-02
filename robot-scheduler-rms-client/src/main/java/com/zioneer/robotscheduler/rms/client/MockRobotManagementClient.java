package com.zioneer.robotscheduler.rms.client;

import com.zioneer.robotscheduler.rms.client.model.RmsCommandResult;
import com.zioneer.robotscheduler.rms.client.model.RmsInventoryRobot;
import com.zioneer.robotscheduler.rms.client.model.RmsRobotStatus;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RMS Mock 客户端：内存实现，用于本地开发与契约测试（对应旧栈 --mock-task-execution 模式）。
 *
 * <p>可通过 {@link #injectStatus} 注入脚本化状态帧；命令调用记录在 {@link #commandLog} 供测试断言。</p>
 */
@Slf4j
public class MockRobotManagementClient implements RobotManagementClient {

    private final Map<String, RmsInventoryRobot> inventoryByRobot = new ConcurrentHashMap<>();

    private final Map<String, List<RmsRobotStatus>> statusByRobot = new ConcurrentHashMap<>();

    /** 机器人 SLAM 地图标识（测试注入模拟地图不一致场景）。 */
    private final Map<String, String> mapCodeByRobot = new ConcurrentHashMap<>();

    /** 机器人本体充电标记（测试注入模拟本体自动充电接管）。 */
    private final Map<String, Boolean> chargingByRobot = new ConcurrentHashMap<>();

    private final List<String> commandLog = new CopyOnWriteArrayList<>();

    private final AtomicInteger rejectionCountdown = new AtomicInteger();
    private final java.util.Set<String> noActiveTaskOnStop = ConcurrentHashMap.newKeySet();

    /** 模拟 stop 的 NO_ACTIVE_TASK 幂等结果（客户端已归一为接受）。 */
    public void injectNoActiveTaskOnStop(String robotCode) {
        noActiveTaskOnStop.add(robotCode);
    }

    public void injectInventory(RmsInventoryRobot robot) {
        inventoryByRobot.put(robot.getRobotCode(), robot);
    }

    public void injectStatus(RmsRobotStatus status) {
        statusByRobot.computeIfAbsent(status.getRobotCode(), k -> new CopyOnWriteArrayList<>()).add(status);
    }

    /** 注入机器人 SLAM 地图标识（模拟地图一致/不一致场景）。 */
    public void injectMapCode(String robotCode, String mapCode) {
        mapCodeByRobot.put(robotCode, mapCode);
    }

    /** 注入本体充电标记（模拟真机自动充电接管场景）。 */
    public void injectCharging(String robotCode, boolean charging) {
        chargingByRobot.put(robotCode, charging);
    }

    public List<String> commandLog() {
        return List.copyOf(commandLog);
    }

    /** 设置前 N 条命令返回不可接受（模拟 RMS 拒绝）。 */
    public void rejectNextCommands(int count) {
        rejectionCountdown.set(count);
    }

    @Override
    public List<RmsInventoryRobot> fetchInventory(String fleetName) {
        return List.copyOf(inventoryByRobot.values());
    }

    @Override
    public java.util.Map<String, String> fetchRobotNameMap() {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        inventoryByRobot.values().forEach(r -> map.put(r.getRobotCode(), r.getRobotName()));
        return map;
    }

    @Override
    public java.util.Map<String, String> fetchRobotMapCodeMap() {
        return Map.copyOf(mapCodeByRobot);
    }

    @Override
    public java.util.Map<String, RobotProfile> fetchRobotProfiles() {
        java.util.Map<String, RobotProfile> map = new java.util.HashMap<>();
        mapCodeByRobot.forEach((code, mapCode) ->
                map.put(code, new RobotProfile(mapCode, chargingByRobot.get(code))));
        return map;
    }

    @Override
    public List<RmsRobotStatus> fetchStatus(String fleetName, String robotName) {
        if (robotName != null && !robotName.isBlank()) {
            return statusByRobot.getOrDefault(robotName, List.of());
        }
        return statusByRobot.values().stream().flatMap(List::stream).toList();
    }

    @Override
    public RmsCommandResult navigate(String robotCode, int cmdId, String mapName,
                                     double x, double y, double yaw,
                                     Double speedLimit, Map<String, Object> description) {
        commandLog.add("navigate:" + robotCode + ":" + cmdId);
        return acceptOrReject();
    }

    @Override
    public RmsCommandResult startActivity(String robotCode, int cmdId, String activity, String label,
                                          Map<String, Object> description) {
        commandLog.add("start-activity:" + robotCode + ":" + cmdId + ":" + activity);
        return acceptOrReject();
    }

    @Override
    public RmsCommandResult stop(String robotCode, int runningCmdId, int stopCmdId, String bodyTaskId) {
        commandLog.add("stop:" + robotCode + ":" + runningCmdId + "->" + stopCmdId
                + (bodyTaskId == null ? "" : ":" + bodyTaskId));
        if (noActiveTaskOnStop.remove(robotCode)) {
            return RmsCommandResult.accepted(Map.of("no_active_task", true));
        }
        return acceptOrReject();
    }

    @Override
    public RmsCommandResult pauseTask(String robotCode, int cmdId, Map<String, Object> params) {
        commandLog.add("pause-task:" + robotCode + ":" + cmdId);
        return acceptOrReject();
    }

    @Override
    public RmsCommandResult resumeTask(String robotCode, int cmdId, Map<String, Object> params) {
        commandLog.add("resume-task:" + robotCode + ":" + cmdId);
        return acceptOrReject();
    }

    @Override
    public RmsCommandResult ackCompletion(String robotCode, String completionId) {
        commandLog.add("ack:" + robotCode + ":" + completionId);
        return RmsCommandResult.accepted(Map.of());
    }

    private RmsCommandResult acceptOrReject() {
        if (rejectionCountdown.getAndUpdate(v -> v > 0 ? v - 1 : 0) > 0) {
            return RmsCommandResult.impossible("mock rejection");
        }
        return RmsCommandResult.accepted(Map.of());
    }
}
