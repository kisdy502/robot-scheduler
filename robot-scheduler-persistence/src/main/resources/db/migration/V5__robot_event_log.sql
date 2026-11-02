-- 机器人事件日志（K8s Events 风格：reason 稳定码 + 聚合列 + 保留期 GC）
-- 事件类型：MODE 状态变化 / BATTERY 电量变化 / ONLINE 上下线
-- 聚合：同 (robot_code, event_type, reason) 窗口内重复事件 count++（防电量流水刷屏）
CREATE TABLE IF NOT EXISTS t_robot_event (
  id          BIGINT PRIMARY KEY,
  robot_code  VARCHAR(64)  NOT NULL,
  event_type  VARCHAR(32)  NOT NULL,
  reason      VARCHAR(64)  NOT NULL,
  severity    VARCHAR(16)  NOT NULL,
  old_value   VARCHAR(64),
  new_value   VARCHAR(64),
  message     VARCHAR(512),
  event_count BIGINT       NOT NULL DEFAULT 1,
  first_at    TIMESTAMPTZ  NOT NULL,
  last_at     TIMESTAMPTZ  NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_robot_event_query ON t_robot_event (robot_code, event_type, last_at DESC);
CREATE INDEX IF NOT EXISTS idx_robot_event_last ON t_robot_event (last_at DESC);
