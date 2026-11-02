# 调度系统 HTTP 接口测试手册（已验证）

> 前提：服务已启动（IDEA `idea` profile 或 jar），地址 `http://127.0.0.1:8091`，地图 map1022Pro 已加载。
> **推荐 PowerShell**（CMD 的 curl 有引号转义问题，JSON 会传坏）。

## 可用测试点位（map1022Pro）

| 点位 | 类型 | 坐标 | 用途 |
|---|---|---|---|
| H1 | parking | (14.1, -6.4) | 停靠位（远端，长路径测试） |
| W1 | workstation | (20.5, 3.0) | 工作站 |
| F1 | pickup | (9.4, 14.6) | 质检取货点 |
| D1 | dropoff | (16.7, 16.2) | 卸货点 |
| K1 | dock | (12.7, 8.8) | 对接位 |
| C1 | charge | (0.3, 13.4) | 充电位 |
| T1 | storage | (-9.8, 1.5) | 存储点 |
| E1 | elevator | (-7.6, -6.8) | 电梯口 |

---

## PowerShell 命令（已验证可用）

### 场景 1：仅移动到点（自动分配机器人）

```powershell
$body = @{
  taskflow_id = "tf-move-001"
  task_id = "task-move-001"
  task_template_type = "go_to_place"
  nav_end_point = "H1"
} | ConvertTo-Json
Invoke-RestMethod -Uri "http://127.0.0.1:8091/api/v1/scheduler/dispatch_task" -Method Post -ContentType "application/json" -Body $body
```

### 场景 2：移动到点（指定机器人）

```powershell
$body = @{
  taskflow_id = "tf-move-002"
  task_id = "task-move-002"
  robot_code = "RB_20261023_00001"
  task_template_type = "go_to_place"
  nav_end_point = "W1"
} | ConvertTo-Json
Invoke-RestMethod -Uri "http://127.0.0.1:8091/api/v1/scheduler/dispatch_task" -Method Post -ContentType "application/json" -Body $body
```

### 场景 3：质检任务（自动分配：导航到点 + 质检动作）

```powershell
$body = @{
  taskflow_id = "tf-qc-001"
  task_id = "task-qc-001"
  task_template_type = "quality_inspection"
  nav_end_point = "H1"
  parameters = @{ task_template_code = "QC_TEMPLATE_A"; work_order_id = "wo-test-001" }
} | ConvertTo-Json
Invoke-RestMethod -Uri "http://127.0.0.1:8091/api/v1/scheduler/dispatch_task" -Method Post -ContentType "application/json" -Body $body
```

### 场景 4：质检任务（指定机器人）

```powershell
$body = @{
  taskflow_id = "tf-qc-002"
  task_id = "task-qc-002"
  robot_code = "RB_20261023_00001"
  task_template_type = "quality_inspection"
  nav_end_point = "K1"
  parameters = @{ task_template_code = "QC_TEMPLATE_B" }
} | ConvertTo-Json
Invoke-RestMethod -Uri "http://127.0.0.1:8091/api/v1/scheduler/dispatch_task" -Method Post -ContentType "application/json" -Body $body
```

### 场景 5：查询任务

```powershell
# 任务列表
Invoke-RestMethod -Uri "http://127.0.0.1:8091/api/v1/scheduler/rmf/tasks?limit=5"

# 按调度请求 ID 查单个（替换 scheduler_request_id）
Invoke-RestMethod -Uri "http://127.0.0.1:8091/api/v1/scheduler/tasks/status?scheduler_request_id=替换ID"

# 任务历史（含失败原因/质检结果/导航进度，重启不丢）
Invoke-RestMethod -Uri "http://127.0.0.1:8091/api/v1/observability/tasks?page_size=10"
```

### 场景 6：取消任务（导航中取消，机器人停车）

```powershell
Invoke-RestMethod -Uri "http://127.0.0.1:8091/api/v1/scheduler/stop-task" -Method Post -ContentType "application/json" -Body (@{robot_code="RB_20261023_00001";reason="manual-test"} | ConvertTo-Json)
```

### 观测命令

```powershell
Invoke-RestMethod -Uri "http://127.0.0.1:8091/api/v1/runtime/fleets/snapshot"          # 机器人实时
Invoke-RestMethod -Uri "http://127.0.0.1:8091/api/v1/observability/traffic/occupation"  # 交通占用
Invoke-RestMethod -Uri "http://127.0.0.1:8091/api/v1/observability/chargers"            # 充电桩
Invoke-RestMethod -Uri "http://127.0.0.1:8091/api/v1/maps/current"                      # 地图状态
```

---

## 多机仿真机器人接入（qcrobotmock_new）

仿真器 jar 已打包：`D:\workspace\qc-robot\qcrobotmock_new\target\qcrobotmock-0.0.1-SNAPSHOT.jar`
本机 zenoh router：`127.0.0.1:7447`｜RMS：`http://127.0.0.1:18083`｜分组：`RG_20260407_00001`

**启动一台新仿真机（参数覆盖即可，每台改编号）**：

```powershell
java -jar D:\workspace\qc-robot\qcrobotmock_new\target\qcrobotmock-0.0.1-SNAPSHOT.jar `
  --config=D:\workspace\qc-robot\qcrobotmock_new\config\qc-robot-mock.yml `
  --robot.robot-code=RB_SIM_002 `
  --robot.serial-no=SN_RB_SIM_002 `
  --robot.robot-name=RB_SIM_002 `
  --robot-management.base-url=http://127.0.0.1:18083 `
  --zenoh.endpoints=127.0.0.1:7447
```

批量起 5 台（PowerShell 循环）：

```powershell
2..6 | ForEach-Object {
  $code = "RB_SIM_{0:D3}" -f $_
  Start-Process java -ArgumentList "-jar","D:\workspace\qc-robot\qcrobotmock_new\target\qcrobotmock-0.0.1-SNAPSHOT.jar",
    "--config=D:\workspace\qc-robot\qcrobotmock_new\config\qc-robot-mock.yml",
    "--robot.robot-code=$code","--robot.serial-no=SN_$code","--robot.robot-name=$code",
    "--robot-management.base-url=http://127.0.0.1:18083",
    "--zenoh.endpoints=127.0.0.1:7447" -WindowStyle Minimized
}
```

启动后验证链路：
1. RMS 机器人列表出现新机器人：`Invoke-RestMethod -Uri "http://127.0.0.1:18083/api/v1/robot-management/robots?page_size=20"`
2. 分组（mock 配置里 group_no=RG_20260407_00001 会自动入组；若没入组用 PUT 接口分配）
3. 调度观测台 `http://127.0.0.1:8091/` 自动出现新机器人
4. 发多个任务（场景 1 自动分配）验证多机调度

---

## 预期结果速查

| 操作 | 响应/后续 |
|---|---|
| 发任务 | `msg: "request published; execution pending"` + `scheduler_request_id` |
| 自动分配 | 在线可派 → 1s 内分配；不可派 → queued → 60s 超时 failed（message 写明原因） |
| 任务执行 | 观测台任务历史：`queued → 导航进度 1/N → completed`（质检任务带质检结果） |
| 导航中 | 交通占用段数先增后逐段递减 |
| 取消 | `cancel_requested` → 终态 completed + "已取消"徽章，机器人停车 |
| 机器人离开充电位 | 充电桩 2m 外自动释放（观测台桩状态变可用） |
| 上位机直发任务 | 位置移动 → 调度显示 running 不可派（防冲突）；停稳 3 帧恢复 idle |

## 常见失败 message

| message | 含义 |
|---|---|
| `自动分配超时：无可用机器人在 60s 内` | 全部机器人离线/忙/不可派 |
| `机器人不可派发（离线/离图），等待超时` | 指定机器人不在图上或离线 |
| `导航分段未完成 [A->B]: ...` | 机器人拒绝导航（真实原因在后缀） |
| `目标点位不存在: XX` | 点位 ID 写错 |
| `交通资源占用失败（路径冲突）` | 路径被他机占用 |
