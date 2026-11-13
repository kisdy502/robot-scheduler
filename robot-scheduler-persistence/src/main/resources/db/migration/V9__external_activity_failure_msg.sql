-- 外部动作镜像补失败原因列（action_status 非 3 时 raw_result.data.msg，上层从镜像读结果可见失败细节）
ALTER TABLE t_external_activity ADD COLUMN IF NOT EXISTS failure_msg VARCHAR(512);
