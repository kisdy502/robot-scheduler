package com.zioneer.robotscheduler.core.fleet;

import java.util.Map;

/**
 * 机器人命令网关端口（对齐原 fleet adapter 的 RobotAPI：8 端点语义收敛为 5 个命令 + 1 个回执确认）。
 *
 * <p>实现由 rms-client 模块提供（HTTP）或 Mock 提供（联调/契约测试）。
 * 命令重试、每机器人串行、generation 取消等执行策略在 core 的命令执行器内统一处理，
 * 实现类只保证单次调用的超时与三态映射。</p>
 */
public interface RobotGateway {

    /**
     * 导航命令：目标点坐标原样透传（拓扑坐标，由 RMS/机器人完成实际导航）。
     *
     * @param cmdId      命令序号（每机器人递增）
     * @param mapName    目标地图
     * @param x          目标 x（拓扑坐标）
     * @param y          目标 y
     * @param yaw        目标朝向（弧度）
     * @param speedLimit 限速（m/s，可为空）
     * @param description 附加描述（业务上下文，透传 RMS）
     */
    RobotCmdResult navigate(String robotCode, int cmdId, String mapName,
                            double x, double y, double yaw, Double speedLimit, Map<String, Object> description);

    /**
     * 业务动作：dock/quality_inspection/charge/swap_battery 等。
     *
     * @return 结果；RMS 返回 path 覆写信息通过 {@link #lastActivityResult} 获取
     */
    RobotCmdResult startActivity(String robotCode, int cmdId, String activity, String label,
                                 Map<String, Object> description);

    /**
     * 停止命令（取消停机）。
     *
     * @param bodyTaskId 业务任务 ID（RMS 契约：stop 需要定位机器人当前活动任务）
     */
    RobotCmdResult stop(String robotCode, int runningCmdId, int stopCmdId, String bodyTaskId);

    /**
     * 暂停/恢复业务任务（pause-task / resume-task）。
     */
    RobotCmdResult pauseTask(String robotCode, int cmdId, Map<String, Object> params);

    RobotCmdResult resumeTask(String robotCode, int cmdId, Map<String, Object> params);

    /**
     * 确认完成回执（completions/{id}/ack），防止 RMS 重复推送。
     */
    RobotCmdResult ackCompletion(String robotCode, String completionId);

    /**
     * 最近一次 start-activity 的附加结果（如 RMS 返回的 path 覆写），无则返回空 map。
     */
    default Map<String, Object> lastActivityResult(String robotCode) {
        return Map.of();
    }
}
