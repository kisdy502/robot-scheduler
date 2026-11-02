package com.zioneer.robotscheduler.common.scheduler;

/**
 * 调度服务常量（逐条对齐旧 robot-scheduler-rest-api constants.py，字段值不可随意变更——
 * 上层 taskflow-management-service 依赖这些语义做轮询与超时判断）。
 */
public final class SchedulerConstants {

    private SchedulerConstants() {
    }

    /** 默认请求方标识（旧 DEFAULT_REQUESTER）。 */
    public static final String DEFAULT_REQUESTER = "taskflow-management-service";

    /** 调度类请求发布成功后的固定 msg 文案（上层依赖该文案判断"已受理"。 */
    public static final String REQUEST_PUBLISHED_MESSAGE = "request published; execution pending";

    /** 默认车队名（环境变量 RMF_DEFAULT_FLEET 缺省值）。 */
    public static final String DEFAULT_FLEET_NAME = "default";

    /** 调度内核响应超时（秒），超时任务按 failed 处理。 */
    public static final double DEFAULT_RESPONSE_TIMEOUT_SECONDS = 5.0;

    /** 机器人在线判定 TTL（毫秒）：超过未收到状态更新视为 offline。 */
    public static final long LIVE_ROBOT_ONLINE_TTL_MILLIS = 15_000L;

    /** 任务状态镜像 TTL（毫秒）：超过未更新标记为 stale。 */
    public static final long RMF_TASK_STATE_TTL_MILLIS = 10_000L;

    /** 非终态任务放弃阈值（秒）：超过该时长无内核存活信号则归档为 abandoned。 */
    public static final double DEFAULT_TASK_ABANDON_SECONDS = 1800.0;

    /** 放弃阈值下限（秒），防止配置过小导致误判。 */
    public static final double TASK_ABANDON_MIN_SECONDS = 300.0;

    /** 终态任务记录保留时长（秒），到期归档。 */
    public static final long RECORD_RETENTION_SECONDS = 7200L;

    /** 记录 GC 周期（秒）。 */
    public static final long RECORD_GC_INTERVAL_SECONDS = 60L;

    /** 切换地图预检明细条数上限（robots/tasks 明细各自封顶，truncated=true）。 */
    public static final int PRE_SWITCH_CHECK_MAX_DETAIL_ITEMS = 50;

    /** 快照接口默认任务列表上限。 */
    public static final int DEFAULT_RUNTIME_TASK_LIST_LIMIT = 200;

    /** 快照接口任务列表上限最大值。 */
    public static final int MAX_RUNTIME_TASK_LIST_LIMIT = 1000;

    /** 任务列表查询默认 limit。 */
    public static final int DEFAULT_RMF_TASK_LIST_LIMIT = 100;

    /** 任务列表查询 limit 最大值。 */
    public static final int MAX_RMF_TASK_LIST_LIMIT = 1000;

    /** 机器人 code 合法字符（超出部分清洗为下划线，对齐旧 sanitize 规则）。 */
    public static final String ROBOT_CODE_ALLOWED_PATTERN = "[^A-Za-z0-9_]";

    /** 机器人 code 清洗后的兜底名。 */
    public static final String ROBOT_CODE_FALLBACK = "robot";

    /** 机器人 code 数字开头时的前缀。 */
    public static final String ROBOT_CODE_NUMERIC_PREFIX = "robot_";

    /** 位置吸附校验：允许吸附到 waypoint 的最大距离（米）。机型定位误差 ~1.9m，旧栈从 0.001 放宽到 3.0。 */
    public static final double MAX_MERGE_WAYPOINT_DISTANCE = 3.0;

    /** 位置吸附校验：允许吸附到 lane 的最大垂距（米）。 */
    public static final double MAX_MERGE_LANE_DISTANCE = 1.0;

    /**
     * 驻留点位阈值（米）：机器人距 waypoint 小于此值才判定"停在该点上"（阻断该点）。
     * 区别于规划吸附（3.0m）：驻留是物理阻断，需精确；规划吸附是逻辑起点，可宽松。
     * 0.5m 意味着机器人基本停在点位圆心才算"占点"；0.5~3m 之间在段上则占段。
     */
    /** 驻留占点判定阈值（米）：机器人停在点位该半径内算"占点"。
     *  0.8m：车身尺寸（典型 AGV 0.4~0.6m 对角）下，停在 0.5~0.8m 内虽偏离圆心仍物理覆盖点位，
     *  只登记路段不登记点会导致其他机器人按"点空闲"穿越该点撞车身（压测实测）。 */
    public static final double DWELL_WAYPOINT_THRESHOLD = 0.8;

    /** 驻留路段阈值（米）：机器人距路径段垂距小于此值判定"停在路段上"（阻断该段）。 */
    public static final double DWELL_SEGMENT_THRESHOLD = 1.0;

    /** 单条机器人命令最大重试次数。 */
    public static final int COMMAND_RETRY_MAX_ATTEMPTS = 3;

    /** 命令重试退避间隔（秒）。 */
    public static final double COMMAND_RETRY_BACKOFF_SECONDS = 1.0;

    /** 活动任务取消时是否向机器人下发 stop（对齐 RMF_QC_STOP_ON_TASK_CANCEL 默认值）。 */
    public static final boolean STOP_ON_TASK_CANCEL = true;

    /** RMS HTTP 连接超时（秒）。 */
    public static final double RMS_CONNECT_TIMEOUT_SECONDS = 3.0;

    /** RMS HTTP 读超时（秒）。 */
    public static final double RMS_READ_TIMEOUT_SECONDS = 15.0;

    /** RMS 状态轮询周期（毫秒）默认 1Hz（旧栈部署默认，yaml 原始默认 10Hz）。 */
    public static final long ROBOT_STATE_UPDATE_INTERVAL_MILLIS = 1000L;

    /** RMS 清单轮询周期（毫秒）默认 0.1Hz。 */
    public static final long ROBOT_INVENTORY_UPDATE_INTERVAL_MILLIS = 10_000L;

    /** 机器人状态过期阈值（秒），过期整帧丢弃并触发临时下线。 */
    public static final double ROBOT_STATE_STALE_AFTER_SECONDS = 15.0;
}
