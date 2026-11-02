# 21 车零空闲压测（20 mock + 真机）：发现空闲机器人立即派任务，直到无车可用
# 任务：质检 50%（作业点+停靠让位）/ 移动 50%（任意非充电点）；指定机器人直派
# 充电：电量 ≤ 20% 且空闲 → 指派充电（随机充电位）；充电保护窗（最短充 5 分钟）内不派活
# 死锁观测：本压测目的即冲突/死锁概率，调度侧有等待意图+死锁检测，任务历史可复盘
# 停止：New-Item D:\robotscheduler\robot-scheduler\logs\stress-10.stop
$ErrorActionPreference = "Continue"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$base = "http://127.0.0.1:8091"
$logDir = "D:\robotscheduler\robot-scheduler\logs"
$stopFile = Join-Path $logDir "stress-10.stop"
if (Test-Path $stopFile) { Remove-Item $stopFile -Force }

# ===== 策略参数 =====
$lowBatteryThreshold = 20      # 低于此电量触发充电任务
$chargeProtectSeconds = 300    # 充电保护窗口（最短充电时长）

$map = Invoke-RestMethod -Uri "$base/api/v1/maps/current/detail" -TimeoutSec 10
$waypoints = @()
foreach ($floor in $map.data.floors) { $waypoints += $floor.waypoints }
$workstations = @($waypoints | Where-Object { $_.type -eq "workstation" } | ForEach-Object { $_.id })
$parkings     = @($waypoints | Where-Object { $_.type -eq "parking" } | ForEach-Object { $_.id })
$chargers     = @($waypoints | Where-Object { $_.type -eq "charge" } | ForEach-Object { $_.id })
$moves        = @($waypoints | Where-Object { $_.type -in @("workstation", "parking", "path") } | ForEach-Object { $_.id })
"points: W=$($workstations.Count) H=$($parkings.Count) C=$($chargers.Count) move=$($moves.Count)"
if ($workstations.Count -eq 0 -or $moves.Count -eq 0) { Write-Error "地图点位为空"; exit 1 }

$script:chargeProtectUntil = @{}
$script:chargePending = @{}
$script:stats = @{ qi = 0; mv = 0; ch = 0; skip = 0 }

function Log($msg) {
  $line = "{0} {1}" -f (Get-Date -Format "HH:mm:ss"), $msg
  $line | Tee-Object -FilePath "$logDir\stress-10.log" -Append
}
function Pick($arr) { $arr | Get-Random }
function Dispatch($body) {
  try {
    $json = $body | ConvertTo-Json -Depth 5
    $res = Invoke-RestMethod -Uri "$base/api/v1/scheduler/dispatch_task" `
      -Method Post -Body ([System.Text.Encoding]::UTF8.GetBytes($json)) `
      -ContentType "application/json" -TimeoutSec 10
    if ($res.status -eq 0) { $true } else {
      Log ("    -> FAIL status={0} msg={1}" -f $res.status, $res.msg); $false
    }
  } catch {
    $detail = ""
    if ($_.ErrorDetails) { $detail = $_.ErrorDetails.Message }
    Log ("    -> ERROR {0} {1}" -f $_.Exception.Message, $detail); $false
  }
}
function Get-RobotStates() {
  try {
    $s = Invoke-RestMethod -Uri "$base/api/v1/runtime/fleets/snapshot" -TimeoutSec 8
    return $s.data.robots
  } catch { return @() }
}
function Is-ChargeProtected([string]$code) {
  $until = $script:chargeProtectUntil[$code]
  return ($until -and [DateTime]::UtcNow -lt $until)
}
function Clean-ChargePending() {
  foreach ($code in @($script:chargePending.Keys)) {
    $taskId = $script:chargePending[$code]
    try {
      $s = Invoke-RestMethod -Uri "$base/api/v1/scheduler/tasks/$taskId/status" -TimeoutSec 8
      if ($s.data.execution_final) {
        Log ("[充电] $code 终态: $($s.data.task_status)")
        $script:chargePending.Remove($code)
      }
    } catch { }
  }
}

Log "==== 21 车零空闲压测启动（质检50/移动50/低电≤${lowBatteryThreshold}%充电；停止：New-Item $stopFile）===="
$seq = 0
while ($true) {
  if (Test-Path $stopFile) { Log "==== 收到停止信号，退出 ===="; break }
  $states = Get-RobotStates
  Clean-ChargePending

  $dispatched = 0
  foreach ($r in $states) {
    $code = $r.robot_code
    # 充电保护窗口内的车跳过
    if ($r.mode -eq "charging" -or (Is-ChargeProtected $code)) {
      if ($r.mode -eq "charging" -and -not (Is-ChargeProtected $code)) {
        $script:chargeProtectUntil[$code] = [DateTime]::UtcNow.AddSeconds($chargeProtectSeconds)
      }
      continue
    }
    # 只派"空闲且可派发"的车（零空闲策略）
    if (-not ($r.mode -eq "idle" -and $r.dispatchable -and $r.online_status -eq "online")) { continue }
    # 充电任务在途不重复派
    if ($script:chargePending.ContainsKey($code)) { continue }

    $seq++
    $ts = Get-Date -Format "HHmmss"
    $battery = $r.battery
    $ok = $false

    if ($null -ne $battery -and $battery -le $lowBatteryThreshold) {
      # 低电充电
      $c = Pick $chargers
      $taskId = "st-ch-$seq-$ts"
      $body = @{
        taskflow_id = "tf-st-$ts"; task_id = $taskId
        task_template_code = "charge-liteflow"
        task_template_type = "charge"; nav_end_point = $c
        robot_code = $code
        parameters = @{ action_timeout_seconds = 1800 }
      }
      Log ("[$seq] 充电 -> $c [指定 $code] 电量 $battery%")
      $ok = Dispatch $body
      if ($ok) {
        $script:stats.ch++
        $script:chargeProtectUntil[$code] = [DateTime]::UtcNow.AddSeconds($chargeProtectSeconds)
        $script:chargePending[$code] = $taskId
      }
    } else {
      $roll = Get-Random -Minimum 0 -Maximum 100
      if ($roll -lt 50) {
        $w = Pick $workstations
        $standby = Pick ($parkings | Where-Object { $_ -ne $w })
        $body = @{
          taskflow_id = "tf-st-$ts"; task_id = "st-qi-$seq-$ts"
          task_template_type = "quality_inspection"; nav_end_point = $w
          robot_code = $code
          parameters = @{ task_template_code = "QC_CONTEXT"; end_point = $standby }
        }
        Log ("[$seq] 质检 -> $w（停靠 $standby）[指定 $code]")
        $ok = Dispatch $body
        if ($ok) { $script:stats.qi++ }
      } else {
        $p = Pick $moves
        $body = @{
          taskflow_id = "tf-st-$ts"; task_id = "st-mv-$seq-$ts"
          task_template_type = "go_to_place"; nav_end_point = $p
          robot_code = $code
        }
        Log ("[$seq] 移动 -> $p [指定 $code]")
        $ok = Dispatch $body
        if ($ok) { $script:stats.mv++ }
      }
    }
    if ($ok) { $dispatched++ }
  }

  if ($dispatched -eq 0) { $script:stats.skip++ }
  Start-Sleep -Seconds 5
}
