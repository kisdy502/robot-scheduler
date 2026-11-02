-- 调度服务核心表初始化（对齐重构方案 §8.1）
-- 约定：snake_case；主键 BIGINT（雪花，应用侧生成）；时间 TIMESTAMPTZ；JSON 用 jsonb；逻辑删除 is_deleted

-- 调度请求记录（对齐旧栈 task_records.json + .archive.jsonl）
CREATE TABLE IF NOT EXISTS t_task_record (
    id                    BIGINT PRIMARY KEY,
    scheduler_request_id  VARCHAR(64)  NOT NULL,
    business_task_id      VARCHAR(128),
    rmf_task_id           VARCHAR(128),
    category              VARCHAR(64),
    robot_code            VARCHAR(64),
    dispatch_status       VARCHAR(32)  NOT NULL DEFAULT 'published',
    payload               JSONB,
    response              JSONB,
    cancel_request        JSONB,
    message               TEXT,
    scene_code            VARCHAR(64),
    is_abandoned          BOOLEAN      NOT NULL DEFAULT FALSE,
    archived_at           TIMESTAMPTZ,
    created_at            TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ,
    created_by            VARCHAR(64),
    updated_by            VARCHAR(64),
    is_deleted            BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_task_record_request_id ON t_task_record (scheduler_request_id);
CREATE INDEX IF NOT EXISTS idx_task_record_business_task_id ON t_task_record (business_task_id);
CREATE INDEX IF NOT EXISTS idx_task_record_rmf_task_id ON t_task_record (rmf_task_id);
CREATE INDEX IF NOT EXISTS idx_task_record_robot_code ON t_task_record (robot_code);
CREATE INDEX IF NOT EXISTS idx_task_record_dispatch_status ON t_task_record (dispatch_status);

-- 任务状态镜像（对齐旧栈 _task_state_records，含 fork 取消标记 cancellation）
CREATE TABLE IF NOT EXISTS t_task_state (
    id                 BIGINT PRIMARY KEY,
    rmf_task_id        VARCHAR(128) NOT NULL,
    status             VARCHAR(32)  NOT NULL,
    category           VARCHAR(64),
    detail             JSONB,
    cancellation       JSONB,
    message            TEXT,
    inspection_result  JSONB,
    updated_at         TIMESTAMPTZ,
    created_at         TIMESTAMPTZ,
    updated_by         VARCHAR(64),
    created_by         VARCHAR(64),
    is_deleted         BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_task_state_rmf_task_id ON t_task_state (rmf_task_id);

-- 充电桩（对齐 chargers.json + charger_pool 内存态）
CREATE TABLE IF NOT EXISTS t_charger (
    id                 BIGINT PRIMARY KEY,
    charger_id         VARCHAR(64)  NOT NULL,
    charger_name       VARCHAR(128),
    rmf_waypoint       VARCHAR(64),
    point_code         VARCHAR(64),
    rmf_level          VARCHAR(64),
    map_code           VARCHAR(64),
    scene_code         VARCHAR(64),
    resource_type      VARCHAR(32)  NOT NULL DEFAULT 'charger',
    connector_type     VARCHAR(64),
    compatible_fleets  TEXT[],
    fleet_name         VARCHAR(64),
    status             SMALLINT     NOT NULL DEFAULT 0,
    occupied_by        VARCHAR(64),
    reserved_by        VARCHAR(64),
    enabled            BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at         TIMESTAMPTZ,
    created_at         TIMESTAMPTZ,
    updated_by         VARCHAR(64),
    created_by         VARCHAR(64),
    is_deleted         BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_charger_charger_id ON t_charger (charger_id);
CREATE INDEX IF NOT EXISTS idx_charger_scene ON t_charger (scene_code);

-- 场景资源 bundle（对齐 <scene>/current/*.json 目录 + bundle_version）
CREATE TABLE IF NOT EXISTS t_scene_resource_bundle (
    id                BIGINT PRIMARY KEY,
    scene_code        VARCHAR(64)  NOT NULL,
    bundle_version    VARCHAR(64),
    resource_type     VARCHAR(32)  NOT NULL,
    checksum          VARCHAR(128),
    storage_object_key VARCHAR(512),
    status            VARCHAR(32)  NOT NULL DEFAULT 'active',
    loaded_at         TIMESTAMPTZ,
    created_at        TIMESTAMPTZ,
    updated_at        TIMESTAMPTZ,
    created_by        VARCHAR(64),
    updated_by        VARCHAR(64),
    is_deleted        BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS idx_scene_bundle_scene_type ON t_scene_resource_bundle (scene_code, resource_type);

-- 机器人家点（对齐 homes.json + robot home yaml）
CREATE TABLE IF NOT EXISTS t_robot_home (
    id            BIGINT PRIMARY KEY,
    robot_code    VARCHAR(64),
    robot_group   VARCHAR(64),
    fleet_name    VARCHAR(64),
    home_waypoint VARCHAR(64) NOT NULL,
    enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ,
    updated_at    TIMESTAMPTZ,
    created_by    VARCHAR(64),
    updated_by    VARCHAR(64),
    is_deleted    BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS idx_robot_home_robot ON t_robot_home (robot_code);

-- 场景点位（对齐 points.json）
CREATE TABLE IF NOT EXISTS t_point (
    id           BIGINT PRIMARY KEY,
    scene_code   VARCHAR(64) NOT NULL,
    point_code   VARCHAR(64) NOT NULL,
    point_name   VARCHAR(128),
    rmf_waypoint VARCHAR(64),
    created_at   TIMESTAMPTZ,
    updated_at   TIMESTAMPTZ,
    created_by   VARCHAR(64),
    updated_by   VARCHAR(64),
    is_deleted   BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_point_scene_code ON t_point (scene_code, point_code);

-- 地图存档（单 json 元数据正本 + SLAM 地图附件路径；替代旧栈 scene bundle 多配置体系）
CREATE TABLE IF NOT EXISTS t_map_metadata (
    id             BIGINT PRIMARY KEY,
    map_name       VARCHAR(128) NOT NULL,
    version        VARCHAR(32)  NOT NULL,
    status         VARCHAR(32)  NOT NULL DEFAULT 'draft',
    metadata_json  TEXT         NOT NULL,
    pgm_path       VARCHAR(512),
    yaml_path      VARCHAR(512),
    activated_at   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ,
    updated_at     TIMESTAMPTZ,
    created_by     VARCHAR(64),
    updated_by     VARCHAR(64),
    is_deleted     BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_map_name_version ON t_map_metadata (map_name, version);
CREATE INDEX IF NOT EXISTS idx_map_activated ON t_map_metadata (activated_at);

-- 交通事件审计（新增：资源占用/让路/死锁处理可观测）
CREATE TABLE IF NOT EXISTS t_traffic_event (
    id          BIGINT PRIMARY KEY,
    scene_code  VARCHAR(64),
    robot_code  VARCHAR(64) NOT NULL,
    resource_id VARCHAR(128),
    action      VARCHAR(32) NOT NULL,
    detail      JSONB,
    created_at  TIMESTAMPTZ,
    updated_at  TIMESTAMPTZ,
    created_by  VARCHAR(64),
    updated_by  VARCHAR(64),
    is_deleted  BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS idx_traffic_event_robot ON t_traffic_event (robot_code, created_at);
