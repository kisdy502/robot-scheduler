package com.zioneer.robotscheduler.rms.client;

import com.zioneer.robotscheduler.rms.client.model.RmsCommandResult;
import com.zioneer.robotscheduler.rms.client.model.RmsInventoryRobot;
import com.zioneer.robotscheduler.rms.client.model.RmsRobotStatus;

import java.util.List;
import java.util.Map;

/**
 * RMS (robot-management-service) 客户端端口：8 端点契约（复刻原 fleet adapter 的 RobotAPI 调用语义）。
 */
public interface RobotManagementClient {

    List<RmsInventoryRobot> fetchInventory(String fleetName);

    /**
     * 拉取机器人管理列表，构建 robotCode → 真实名称映射。
     * fleet status/inventory 接口返回的 robot_name 等于编码，真实名称只在管理列表接口。
     */
    Map<String, String> fetchRobotNameMap();

    /**
     * 拉取机器人 SLAM 地图标识映射（robotCode → current_map_code，管理列表接口）。
     * 调度侧与激活地图比对，不一致禁止派发（三方地图一致性的调度层校验）。
     */
    Map<String, String> fetchRobotMapCodeMap();

    /**
     * 拉取机器人档案（管理列表接口）：SLAM 地图标识 + 本体充电中标记。
     * bodyCharging 用于充电任务接管判定（真机本体自动开始充电时调度适配，不重复下发动作）。
     */
    Map<String, RobotProfile> fetchRobotProfiles();

    /** 机器人档案（管理接口可得字段）。 */
    record RobotProfile(String mapCode, Boolean bodyCharging) {
    }

    /**
     * 状态轮询；robotName 为空时返回全车队。
     */
    List<RmsRobotStatus> fetchStatus(String fleetName, String robotName);

    RmsCommandResult navigate(String robotCode, int cmdId, String mapName,
                              double x, double y, double yaw,
                              Double speedLimit, Map<String, Object> description);

    RmsCommandResult startActivity(String robotCode, int cmdId, String activity, String label,
                                   Map<String, Object> description);

    RmsCommandResult stop(String robotCode, int runningCmdId, int stopCmdId, String bodyTaskId);

    RmsCommandResult pauseTask(String robotCode, int cmdId, Map<String, Object> params);

    RmsCommandResult resumeTask(String robotCode, int cmdId, Map<String, Object> params);

    RmsCommandResult ackCompletion(String robotCode, String completionId);
}
