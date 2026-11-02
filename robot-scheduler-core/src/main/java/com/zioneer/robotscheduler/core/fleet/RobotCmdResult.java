package com.zioneer.robotscheduler.core.fleet;

/**
 * 机器人命令执行结果三态（对齐原 fleet adapter 的 RobotAPIResult 语义）。
 */
public enum RobotCmdResult {

    /** 成功送达。 */
    SUCCESS,

    /** 暂时失败，可重试（HTTP/网络错误、RMS 忙）。 */
    RETRY,

    /** 永久失败（RMS 拒绝、机器人不支持），触发任务失败或取消。 */
    IMPOSSIBLE
}
