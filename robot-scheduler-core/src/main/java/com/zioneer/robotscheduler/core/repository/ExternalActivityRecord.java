package com.zioneer.robotscheduler.core.repository;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * 外部动作回执镜像（观测档案）：上层应用直连 RMS 下发的业务动作（质检/夹爪…）完成回执。
 * 调度只存档不编排；body_task_id 与调度移动任务的 task_id 同值，前端据此聚合业务流。
 */
@Getter
@Setter
public class ExternalActivityRecord {

    /** 主键（雪花 ID）。 */
    private Long id;

    /** 机器人编码。 */
    private String robotCode;

    /** 上层业务任务 ID（回执 body_task_id，与移动任务 task_id 同值 = 聚合键）。 */
    private String bodyTaskId;

    /** RMS 回执信箱命令 ID（幂等去重键）。 */
    private String rmsCommandId;

    /** 动作类型（action / navigate）。 */
    private String actionType;

    /** 动作终态（3 成功 / 5 取消 / 6 失败）。 */
    private Integer actionStatus;

    /** 业务结果原文（质检 NG/OK、检测项明细）。 */
    private Map<String, Object> resultReport;

    /** 失败原因（action_status 非 3 时 raw_result.data.msg，如"任务模板不存在"）。 */
    private String failureMessage;

    /** 机器人上报时间戳（毫秒）。 */
    private Long robotReportedMillis;

    /** 落库时间（毫秒）。 */
    private Long createdAtMillis;
}
