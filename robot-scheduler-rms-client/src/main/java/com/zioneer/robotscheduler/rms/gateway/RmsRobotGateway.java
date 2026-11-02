package com.zioneer.robotscheduler.rms.gateway;

import com.zioneer.robotscheduler.core.fleet.RobotCmdResult;
import com.zioneer.robotscheduler.core.fleet.RobotGateway;
import com.zioneer.robotscheduler.rms.client.RobotManagementClient;
import com.zioneer.robotscheduler.rms.client.model.RmsCommandResult;
import lombok.RequiredArgsConstructor;

import java.util.Map;

/**
 * RobotGateway 端口的 RMS 适配：core 的命令三态 ↔ RMS 命令三态的直映射。
 *
 * <p>命令重试/串行/generation 取消策略在 core 命令执行器实现，本类保持单次调用的轻量转发。</p>
 */
@RequiredArgsConstructor
public class RmsRobotGateway implements RobotGateway {

    private final RobotManagementClient client;

    @Override
    public RobotCmdResult navigate(String robotCode, int cmdId, String mapName,
                                   double x, double y, double yaw, Double speedLimit,
                                   Map<String, Object> description) {
        return map(client.navigate(robotCode, cmdId, mapName, x, y, yaw, speedLimit, description));
    }

    @Override
    public RobotCmdResult startActivity(String robotCode, int cmdId, String activity, String label,
                                        Map<String, Object> description) {
        return map(client.startActivity(robotCode, cmdId, activity, label, description));
    }

    @Override
    public RobotCmdResult stop(String robotCode, int runningCmdId, int stopCmdId, String bodyTaskId) {
        return map(client.stop(robotCode, runningCmdId, stopCmdId, bodyTaskId));
    }

    @Override
    public RobotCmdResult pauseTask(String robotCode, int cmdId, Map<String, Object> params) {
        return map(client.pauseTask(robotCode, cmdId, params));
    }

    @Override
    public RobotCmdResult resumeTask(String robotCode, int cmdId, Map<String, Object> params) {
        return map(client.resumeTask(robotCode, cmdId, params));
    }

    @Override
    public RobotCmdResult ackCompletion(String robotCode, String completionId) {
        return map(client.ackCompletion(robotCode, completionId));
    }

    @Override
    public Map<String, Object> lastActivityResult(String robotCode) {
        // TODO(P2): 缓存最近一次 start-activity 的 path 覆写数据
        return Map.of();
    }

    private RobotCmdResult map(RmsCommandResult result) {
        if (result.isAccepted()) {
            return RobotCmdResult.SUCCESS;
        }
        return result.isRetryable() ? RobotCmdResult.RETRY : RobotCmdResult.IMPOSSIBLE;
    }
}
