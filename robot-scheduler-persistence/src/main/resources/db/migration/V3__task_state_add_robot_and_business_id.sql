-- t_task_state 补观测字段：robot_code/business_task_id 便于任务历史按机器人/业务任务查询
ALTER TABLE t_task_state ADD COLUMN IF NOT EXISTS robot_code VARCHAR(64);
ALTER TABLE t_task_state ADD COLUMN IF NOT EXISTS business_task_id VARCHAR(128);
-- V1 中 t_task_state 的 rmf_task_id 唯一索引基于多列组合场景设计，现按单列唯一收敛（upsert 依赖）
DROP INDEX IF EXISTS uk_task_state_rmf_task_id;
CREATE UNIQUE INDEX IF NOT EXISTS uk_task_state_rmf_task_id ON t_task_state (rmf_task_id);
