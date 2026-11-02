# 多机交通冲突与错误提示测试（新地图 v3.5.6 网状拓扑）
# 前提：调度服务已重启（新代码）、5 台 mock 分布 P1/H1/W1/E1/T1
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$base = "http://127.0.0.1:8091"

function Send-Task($taskflowId, $taskId, $type, $target, $robotCode = $null) {
  $body = @{ taskflow_id = $taskflowId; task_id = $taskId; task_template_type = $type; nav_end_point = $target }
  if ($robotCode) { $body.robot_code = $robotCode }
  $j = $body | ConvertTo-Json
  Invoke-RestMethod -Uri "$base/api/v1/scheduler/dispatch_task" -Method Post -ContentType "application/json" -Body $j -TimeoutSec 10
}

function Wait-Final($reqId, $maxSec = 180) {
  $sw = [Diagnostics.Stopwatch]::StartNew()
  while ($sw.Elapsed.TotalSeconds -lt $maxSec) {
    Start-Sleep -Seconds 5
    $s = Invoke-RestMethod -Uri "$base/api/v1/scheduler/tasks/status?scheduler_request_id=$reqId" -TimeoutSec 8
    if ($s.data.execution_final) { return $s.data }
  }
  return $null
}

function Show-Robots($title) {
  $s = Invoke-RestMethod -Uri "$base/api/v1/runtime/fleets/snapshot" -TimeoutSec 8
  Write-Host "`n===== $title ====="
  $s.data.robots | Sort-Object robot_code | ForEach-Object {
    "  $($_.robot_code) mode=$($_.mode) disp=$($_.dispatchable) pos=($([math]::Round($_.pose.x,1)),$([math]::Round($_.pose.y,1)))"
  }
}

function Show-Occupation($title) {
  $o = Invoke-RestMethod -Uri "$base/api/v1/observability/traffic/occupation" -TimeoutSec 8
  Write-Host "----- $title -----"
  if ($o.data.by_robot) {
    $o.data.by_robot.PSObject.Properties | ForEach-Object { "  $($_.Name): $($_.Value.segmentIds.Count)段 $($_.Value.waypointIds.Count)点" }
  } else { "  （无占用）" }
}

# ========== Step 0: 上传新地图（热切换） ==========
Write-Host "`n########## Step 0: 热切换新地图 v3.5.6 ##########"
Add-Type -AssemblyName System.Net.Http
$handler = New-Object System.Net.Http.HttpClientHandler
$client = New-Object System.Net.Http.HttpClient($handler)
$content = New-Object System.Net.Http.MultipartFormDataContent
$json = [System.IO.File]::ReadAllBytes("D:\robotscheduler\robot-scheduler\local-map\map_info.json")
$meta = New-Object System.Net.Http.ByteArrayContent(,$json)
$content.Add($meta, "metadata", "map_info.json")
$result = $client.PostAsync("$base/api/v1/maps/upload", $content).Result
$resp = $result.Content.ReadAsStringAsync().Result
"上传: HTTP $([int]$result.StatusCode) $resp"
$m = Invoke-RestMethod -Uri "$base/api/v1/maps/current" -TimeoutSec 8
"当前地图: $($m.data.map_name) v$($m.data.version) 点位=$(($m.data.floors | ForEach-Object { $_.waypoints } | Measure-Object).Count)"
Start-Sleep -Seconds 5
Show-Robots "地图切换后机器人状态"

# ========== 用例 A: 同目标点冲突（两台同时发 H1）==========
Write-Host "`n########## 用例 A: 同目标点 H1 冲突（00007 从 P1 / 00009 从 W1）##########"
Show-Occupation "发任务前占用"
$r1 = Send-Task "tf-ca-1" "task-ca-1" "go_to_place" "H1" "RB_20261024_00007"
$r2 = Send-Task "tf-ca-2" "task-ca-2" "go_to_place" "H1" "RB_20261024_00009"
"任务1(00007->H1): $($r1.data.dispatch_status) req=$($r1.data.scheduler_request_id.Substring(0,8))"
"任务2(00009->H1): $($r2.data.dispatch_status) req=$($r2.data.scheduler_request_id.Substring(0,8))"
Start-Sleep -Seconds 20
Show-Occupation "执行中占用（观察 H1 归属）"
$f1 = Wait-Final $r1.data.scheduler_request_id 240
$f2 = Wait-Final $r2.data.scheduler_request_id 240
"任务1 终态: $($f1.task_status) | $($f1.message)"
"任务2 终态: $($f2.task_status) | $($f2.message)"

# ========== 用例 B: 路径交叉冲突 ==========
Write-Host "`n########## 用例 B: 路径交叉（00011 从 T1 经长路去 W1 / 00008 从 H1 去 P1 交叉）##########"
Show-Occupation "发任务前占用"
$r3 = Send-Task "tf-cb-1" "task-cb-1" "go_to_place" "W1" "RB_20261024_00011"
$r4 = Send-Task "tf-cb-2" "task-cb-2" "go_to_place" "P1" "RB_20261024_00008"
"任务3(00011 T1->W1): $($r3.data.dispatch_status)"
"任务4(00008 H1->P1): $($r4.data.dispatch_status)"
Start-Sleep -Seconds 20
Show-Occupation "执行中占用"
$f3 = Wait-Final $r3.data.scheduler_request_id 300
$f4 = Wait-Final $r4.data.scheduler_request_id 300
"任务3 终态: $($f3.task_status) | $($f3.message)"
"任务4 终态: $($f4.task_status) | $($f4.message)"

# ========== 用例 C: 错误提示精准性 ==========
Write-Host "`n########## 用例 C: 错误提示 ##########"
Write-Host "--- C1: 不存在的点位 ---"
try {
  $body = @{ taskflow_id = "tf-ec1"; task_id = "task-ec1"; robot_code = "RB_20261024_00007"; task_template_type = "go_to_place"; nav_end_point = "XX999" } | ConvertTo-Json
  $e = Invoke-RestMethod -Uri "$base/api/v1/scheduler/dispatch_task" -Method Post -ContentType "application/json" -Body $body -TimeoutSec 10
  "HTTP 200: $($e.data.task_status) msg=$($e.data.message)"
  $ef = Wait-Final $e.data.scheduler_request_id 60
  "终态: $($ef.task_status) | $($ef.message)"
} catch {
  $errBody = $_.ErrorDetails.Message | ConvertFrom-Json
  "HTTP $($_.Exception.Response.StatusCode.value__): $($errBody.detail)"
}

Write-Host "--- C2: 指定不存在机器人 ---"
try {
  $body = @{ taskflow_id = "tf-ec2"; task_id = "task-ec2"; robot_code = "RB_GHOST_999"; task_template_type = "go_to_place"; nav_end_point = "H1" } | ConvertTo-Json
  $e2 = Invoke-RestMethod -Uri "$base/api/v1/scheduler/dispatch_task" -Method Post -ContentType "application/json" -Body $body -TimeoutSec 10
  "HTTP 200: $($e2.data.task_status) req=$($e2.data.scheduler_request_id.Substring(0,8))"
  $ef2 = Wait-Final $e2.data.scheduler_request_id 120
  "终态: $($ef2.task_status) | $($ef2.message)"
} catch {
  $errBody = $_.ErrorDetails.Message | ConvertFrom-Json
  "HTTP $($_.Exception.Response.StatusCode.value__): $($errBody.detail)"
}

Write-Host "--- C3: 无人可派（全部占用时自动分配）---"
$r5 = Send-Task "tf-ec3" "task-ec3" "go_to_place" "F1"
"自动分配: $($r5.data.dispatch_status)"
Start-Sleep -Seconds 10
Show-Robots "自动分配后"
$f5 = Wait-Final $r5.data.scheduler_request_id 300
"终态: $($f5.task_status) | $($f5.message)"

# ========== 汇总 ==========
Write-Host "`n########## 任务历史汇总 ##########"
$h = Invoke-RestMethod -Uri "$base/api/v1/observability/tasks?page_size=20" -TimeoutSec 8
$h.data.list | ForEach-Object {
  $msg = if ($_.message) { $_.message.Substring(0, [Math]::Min(60, $_.message.Length)) } else { "" }
  "  $($_.business_task_id.PadRight(16)) $($_.robot_code)  $($_.status.PadRight(9)) $msg"
}
Show-Robots "测试结束机器人状态"
Show-Occupation "测试结束占用"
