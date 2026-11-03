-- 单实例运行正本：业务使用权、外部作业、交通占用与命令序号；旧表和旧接口不变。
CREATE TABLE t_robot_runtime (
    robot_code VARCHAR(128) PRIMARY KEY,
    taskflow_id VARCHAR(128) UNIQUE,
    operation_id VARCHAR(192),
    operation_started_at TIMESTAMPTZ,
    completed_taskflow_id VARCHAR(128),
    completed_operation_id VARCHAR(192),
    completed_started_at TIMESTAMPTZ,
    completed_finished_at TIMESTAMPTZ,
    segment_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    waypoint_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    dwell_waypoint_id VARCHAR(192),
    dwell_segment_id VARCHAR(256),
    awaiting_stop BOOLEAN NOT NULL DEFAULT FALSE,
    stop_accepted BOOLEAN NOT NULL DEFAULT FALSE,
    acquired_at TIMESTAMPTZ,
    map_name VARCHAR(192),
    map_version VARCHAR(128),
    last_command_id INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_robot_runtime_operation CHECK (operation_id IS NULL OR
        (taskflow_id IS NOT NULL AND operation_started_at IS NOT NULL)),
    CONSTRAINT ck_robot_runtime_completed CHECK (completed_operation_id IS NULL OR
        (completed_taskflow_id IS NOT NULL AND completed_started_at IS NOT NULL AND completed_finished_at IS NOT NULL)),
    CONSTRAINT ck_robot_runtime_cmd CHECK (last_command_id >= 0)
);
