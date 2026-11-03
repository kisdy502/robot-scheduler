-- 外部动作回执镜像（观测档案，非任务表）：
-- 新版架构下调度只管移动；质检/夹爪等业务动作由上层应用直连 RMS 下发。
-- 调度轮询 RMS 状态帧时"看见"回执信箱（completed_commands），将非调度下发的回执存档，
-- 供前端按 body_task_id（= 移动任务 task_id）聚合展示同一业务流的 移动+质检 两条记录。
-- 幂等：rms_command_id 去重（上层应用负责 ack，未 ack 的重复投递由本键去重）。
CREATE TABLE IF NOT EXISTS t_external_activity (
    id                 BIGINT PRIMARY KEY,
    robot_code         VARCHAR(64)  NOT NULL,
    body_task_id       VARCHAR(128),
    rms_command_id     VARCHAR(64)  NOT NULL,
    action_type        VARCHAR(64),
    action_status      INT,
    result_report      JSONB,
    robot_reported_ms  BIGINT,
    finished_at        TIMESTAMPTZ  NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_external_activity_cmd ON t_external_activity (rms_command_id);
CREATE INDEX IF NOT EXISTS idx_external_activity_task ON t_external_activity (body_task_id);
CREATE INDEX IF NOT EXISTS idx_external_activity_robot ON t_external_activity (robot_code, finished_at DESC);
