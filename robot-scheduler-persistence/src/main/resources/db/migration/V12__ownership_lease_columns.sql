-- 使用权租约字段：取得时间 + 最近心跳（孤儿占用自动回收判据；重启后租约状态不丢）
ALTER TABLE t_robot_runtime ADD COLUMN IF NOT EXISTS owner_since_at TIMESTAMPTZ;
ALTER TABLE t_robot_runtime ADD COLUMN IF NOT EXISTS owner_heartbeat_at TIMESTAMPTZ;
