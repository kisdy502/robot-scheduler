-- 已到点但仍在执行动作的内核任务也要停止对账；排队任务和外部作业不设置此标记。
ALTER TABLE t_robot_runtime ADD COLUMN scheduler_task_id VARCHAR(128);
ALTER TABLE t_robot_runtime ADD CONSTRAINT ck_runtime_execution_owner
    CHECK (scheduler_task_id IS NULL OR operation_id IS NULL);
