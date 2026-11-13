# 调度 API 测试手册（新版架构）

> 调度服务 http://127.0.0.1:8091 ｜ RMS http://127.0.0.1:18083 ｜ 推荐 PowerShell（curl 有引号问题）

## 一、核心概念（新版架构）

- **调度只管移动**：`goto-place` 是唯一推荐移动入口；`dispatch_task` 已废弃（保留兼容）
- **业务动作归上层**：质检/充电由上层直连 RMS `POST /api/v1/robot-management/rmf/robots/{code}/start-activity`
- **业务流闭环** = `assign-task 抢占 → goto-place 移动 → 直连 RMS 动作 → observability/activities 读结果 → release-robot 释放`
- **ID 契约**：taskflow_id = 雪花（assign-task 传整数）；task_id = `taskflow_id + yyyyMMddHHmmssSSS`

## 二、移动任务（goto-place）

```powershell
# 生成 ID（示例用时间戳，正式上层用雪花）
$tf = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$taskId = "$tf$(Get-Date -Format yyyyMMddHHmmssfff)"

# 1. 抢占机器人
$b1 = '{"taskflow_id":' + $tf + ',"task_template_type_list":["quality_inspection"],"robot_code":"RB_20261112_00032"}'
Invoke-RestMethod -Uri "http://127.0.0.1:8091/api/v1/scheduler/assign-task" -Method Post -ContentType "application/json; charset=utf-8" -Body $b1

# 2. 移动到点
$b2 = '{"taskflow_id":"' + $tf + '","task_id":"' + $taskId + '","robot_code":"RB_20261112_00032","nav_target_point":"W1"}'
Invoke-RestMethod -Uri "http://127.0.0.1:8091/api/v1/scheduler/goto-place" -Method Post -ContentType "application/json; charset=utf-8" -Body $b2

# 3. 查状态（task_status 字段：queued/underway/completed/failed/canceled）
Invoke-RestMethod -Uri "http://127.0.0.1:8091/api/v1/scheduler/tasks/$taskId/status"

# 4. 释放
$b4 = '{"taskflow_id":"' + $tf + '","robot_code":"RB_20261112_00032"}'
Invoke-RestMethod -Uri "http://127.0.0.1:8091/api/v1/scheduler/release-robot" -Method Post -ContentType "application/json; charset=utf-8" -Body $b4
```

幂等：`"parameters":{"idempotent":true}` 时相同 task_id + 相同载荷重试返回原 scheduler_request_id，改载荷 409。

## 三、业务动作（质检/充电，直连 RMS）

```powershell
# 质检（机器人须已在作业点；模板需本体已预置，如 LAD-28-A-not-lift）
$desc = @{
  rmf_task_id = "qcsim.$taskId"; task_template_type = "quality_inspection"
  task_template_code = "LAD-28-A-not-lift"; end_point = "W1"
  destination = @{ x = 20.5; y = 3.0; yaw = 0 }
  task_id = $taskId; taskflow_id = "$tf"; nav_target_point = "W1"
}
$payload = @{ activity = "quality_inspection"; description = $desc; label = "$tf|LAD-28-A-not-lift"; cmd_id = 1 } | ConvertTo-Json -Depth 5
Invoke-RestMethod -Uri "http://127.0.0.1:18083/api/v1/robot-management/rmf/robots/RB_20261112_00032/start-activity" -Method Post -ContentType "application/json; charset=utf-8" -Body $payload

# 充电：activity 直接放模板 code（机器人按模板 code 查）
#   activity = "back-charge"; description.task_template_type = "charge"

# 读结果（调度观测镜像，勿轮询 RMS 信箱——会被调度兜底 ack 清掉）
Invoke-RestMethod -Uri "http://127.0.0.1:8091/api/v1/observability/activities?task_id=$taskId"
# action_status: 3=成功（result_report 为业务结果）；非 3 看 failure_msg
```

## 四、观测接口

```powershell
Invoke-RestMethod -Uri "http://127.0.0.1:8091/api/v1/runtime/fleets/snapshot"           # 机器人实时
Invoke-RestMethod -Uri "http://127.0.0.1:8091/api/v1/observability/tasks?page_size=10"  # 任务历史（含请求报文/失败原因）
Invoke-RestMethod -Uri "http://127.0.0.1:8091/api/v1/observability/traffic/occupation"  # 交通占用
Invoke-RestMethod -Uri "http://127.0.0.1:8091/api/v1/observability/robot-events?event_type=USAGE&page_size=20"  # 占用/释放时间线
Invoke-RestMethod -Uri "http://127.0.0.1:8091/api/v1/observability/chargers"            # 充电桩
Invoke-RestMethod -Uri "http://127.0.0.1:8091/api/v1/maps/current"                      # 地图
```

## 五、取消任务

```powershell
Invoke-RestMethod -Uri "http://127.0.0.1:8091/api/v1/scheduler/stop-task" -Method Post -ContentType "application/json" -Body (@{task_id=$taskId;reason="manual"} | ConvertTo-Json)
# 排队→canceled；执行中→机器人停车 + completed + cancellation 标记
```

## 六、前端业务模拟器（联调利器）

- `http://localhost:5174/qc-sim` 质检模拟 / `/charge-sim` 充电模拟：一键全流程（抢占→移动→start-activity→镜像读结果→释放）、仅移动、仅动作、中止（动作阶段自动 RMS stop）
- 地图页右键：移动到任意点（后台执行，可并行给多台下发）

## 七、仿真机器人接入

- **本机仿真**：IDEA 上位机（testDispatch 项目）+ WSL 仿真底盘，zenoh 连 `tcp/10.6.34.53:7447`（namespace `test-dispatch`）
- **远程机器**：对方 zenoh client 配置 endpoints 指向本机 `tcp/10.6.34.53:7447` + namespace 一致即可入网
- 新机器人注册后设分组：`.\scripts\assign-group.ps1 -Codes RB_xxx`
- mock 批量：`scripts/deploy-mocks-v2.ps1` 等（压测用，注意与真机区分）

## 八、压测

```powershell
# 双车道随机压测（移动40%/质检40%/充电20%，全业务流闭环，JSONL 结果+汇总）
powershell -ExecutionPolicy Bypass -File .\scripts\sim-stress.ps1
# 停止
New-Item .\logs\sim-stress.stop
# 汇总输出成功率/失败原因分布，明细 logs\sim-stress-results.jsonl
```

## 九、常见失败 message

| message | 含义 |
|---|---|
| `机器人不可派发（停止确认中，交通占用保留），等待超时` | 上次异常后 stop 确认未完成（幂等修复后应 10s 内自愈） |
| `导航分段未完成 [A->B]: 任务模板不存在...` | navigate 描述带了模板（已修复；上层直发动作才会遇到） |
| `机器人地图与调度不一致（机器人: X vs 调度: Y）` | 机器人 SLAM 图 ≠ 调度激活图，换图后两侧对齐 |
| `交通资源占用失败（等待 300s 超时）` | 目标/路径被长期占用 |
| `自动分配超时：无可用机器人在 60s 内` | 无 robot_code 且全员不可派 |
| `task status record not found`（stop-task 404） | 任务已终态/不存在，无可停对象 |
