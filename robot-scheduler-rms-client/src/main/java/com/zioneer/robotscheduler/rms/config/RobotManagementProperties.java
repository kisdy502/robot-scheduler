package com.zioneer.robotscheduler.rms.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RMS 客户端配置（前缀 robot.scheduler.rms）。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "robot.scheduler.rms")
public class RobotManagementProperties {

    /** RMS 服务 base URL，如 https://sz-robot-admin.zioneer.com/robot-management-service。 */
    private String baseUrl = "http://127.0.0.1:18083";

    /** 车队名（RMS 侧 robot group，如 RG_20260407_00001）。 */
    private String fleetName = "default";

    /** 连接超时（秒）。 */
    private double connectTimeoutSeconds = 3.0;

    /** 读超时（秒）。 */
    private double readTimeoutSeconds = 15.0;

    /** 是否使用 Mock 客户端（本地开发/契约测试）。 */
    private boolean mockEnabled = false;

    /**
     * 充电动作的线上模板名：机器人侧下发的充电模板 code（如 back-charge 倒车充电），
     * 调度内部语义恒为 charge，仅 start-activity 下发时替换（activity 与 description.task_template_type）。
     */
    private String chargeActivityName = "back-charge";

    /**
     * 报文级追踪（调试期联调对拍）：出站命令（navigate/start-activity/stop 等）全量打印
     * 请求/响应 JSON；status 轮询只打带回执（completed_commands 非空）的帧，
     * 纯状态心跳帧与 inventory 不打。稳定后可整体关闭。
     */
    private boolean tracePayload = true;
}
