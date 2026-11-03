# 机器人使用权与外部作业接入

调度负责移动和交通资源；拍照、夹爪取放等动作由上层业务通过 RMS 执行。调度不自动识别这些作业，业务系统通过下面的通知维持机器人使用状态。

## 推荐调用顺序

1. 调用现有 POST /api/v1/scheduler/assign-task，以 taskflow_id 和 robot_code 取得使用权。同一任务流重复取得同一台机器人幂等；换机器人前先释放原使用权。原接口 taskflow_id 仍为整数。
2. 使用相同的顶层 taskflow_id 提交 goto-place 或 dispatch_task，等待调度任务 completed。不要用 parameters.taskflow_id 代替顶层字段。
3. 调用 POST /api/v1/scheduler/external-operations/start，成功后才向 RMS 发拍照、夹爪等作业。若调度执行线程尚未退出，可能返回 409，稍后重试。不能在已经受理的导航任务之前插入外部作业。
4. 业务确认动作已结束、机器人允许再次移动后，调用 POST /api/v1/scheduler/external-operations/finish。业务执行失败也应先完成停止/恢复确认，不能仅因接口超时就通知结束。
5. 后续仍由同一任务流提交移动任务，或调用现有 release-robot 释放使用权。结束外部作业不会自动释放使用权。

开始和结束通知使用同一请求体：

~~~json
{
  "taskflow_id": "1001",
  "robot_code": "R001",
  "operation_id": "inspection-20261102-0001"
}
~~~

operation_id 是一次作业的唯一标识，不要在同一机器人上复用。通知重试保持原 ID。新接口的 taskflow_id 是字符串，沿用原整数 ID 时传对应的十进制字符串即可。

## 响应与查询

成功沿用 {status, msg, data, timestamp} 信封，msg=success。data 包括 robot_code、taskflow_id、operation_id、state（running/finished）、started_at、finished_at；时间是 Unix 毫秒，进行中的 finished_at 为 null。冲突返回 HTTP 409 + {"detail":"..."}，缺少必填字段返回 400。

GET /api/v1/scheduler/robot-use?robot_code=R001 返回使用权、当前 external_operation 和 available_for_new_taskflow。最后一个字段只表达使用权/已受理任务的准入情况，不代表在线、地图和交通条件全部满足。原 robot-resources 接口保持兼容。

## 调度行为

| 状态 | 持有者提交移动 | 其他任务流指定机器人 | 自动选车/自动返充 |
| --- | --- | --- | --- |
| 无使用权、无外部作业 | 保持原有行为 | 保持原有行为 | 正常参与 |
| 已取得使用权 | 允许 | 409 | 其他任务流和返充跳过；持有者可自动选中自己的机器人 |
| 外部作业中 | 指定机器人请求可受理排队，等待结束通知 | 409 | 跳过 |

指定机器人等待外部作业最多 300 秒，期间每 8 秒更新等待原因；超时只结束本次等待任务，不自动结束外部作业或释放使用权。自动选车沿用原自动分配超时配置。使用权任务仍受原有在线、地图、交通和任务执行检查约束。

已受理任务（包括排队任务）阻止释放使用权和开始外部作业。原任务流仍可取消自己的调度任务；遗留孤儿任务取消不能停止当前外部作业或另一项已登记的调度任务。释放使用权和结束外部作业都不会移除物理驻留点位；机器人离开后仍由原位置帧/交通登记处理。外部作业也阻止地图切换。

## 本批实现范围

新业务移动请求可通过 parameters.idempotent=true 显式启用防重复执行；相同 task_id 的相同请求返回原记录，
改变载荷返回 409，重做任务使用新 ID。不传该参数仍保留旧接口允许重复业务任务 ID 的行为。
30 台指定机器人稳定性检查与真实质检/报告联调步骤见 selected-robot-readiness-and-validation.md。

使用权、外部作业和交通占用已通过 V6 的 t_robot_runtime 持久化，不自动过期。正常重启会恢复原持有者和仍在执行的外部作业；首次从旧内存版本升级仍需停单、确认机器人停止，再重新登记。完整规则见 runtime-recovery.md。
约束范围仍是经过调度服务的命令；业务绕过调度直接向 RMS 发命令时，必须遵守上述调用顺序，RMS 不会因此自动实现多来源命令仲裁。

现有质检接口及导航加动作的执行能力暂时保留；本次不删除业务接口，也不增加让路、抢占或复杂租约机制。
