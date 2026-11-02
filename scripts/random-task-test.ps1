# 随机任务压测：循环下发各类任务（质检/充电/归巢/移动），混合自动分配与随机指定
# 用法：后台运行，日志写 logs/random-task-test.log；停止：杀进程或删 stop 文件
$ErrorActionPreference = "Continue"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$logDir = "D:\robotscheduler\robot-scheduler\logs"
$stopFile = Join-Path $logDir "random-task-test.stop"
if (Test-Path $stopFile) { Remove-Item $stopFile -Force }

# map1025L 点位池
$workstations = @("W1", "W2", "W3", "W4", "W5", "W6", "W7")   # 作业点（质检目标）
$chargers     = @("C1", "C2", "C3", "C4")                       # 充电点
$parkings     = @("H1", "H2", "H3", "H4", "H5", "H6", "H7", "H8") # 停靠/归巢点
$allPoints    = $workstations + $chargers + $parkings + @(
  "P1","P2","P3","P4","P5","P6","P7","P8","P9","P10","P11","P12","P13","P14","P15","P16","P17","P18","P19","P20","P21","P22","P23","P24","P25","P26","P27")
$robots       = @("RB_20261024_00007", "RB_20261024_00008", "RB_20261024_00009",
                  "RB_20261024_00010", "RB_20261024_00011")     # 只压 mock，真机 00012 不参与

function Log($msg) {
  $line = "{0} {1}" -f (Get-Date -Format "HH:mm:ss"), $msg
  $line | Tee-Object -FilePath "$logDir\random-task-test.log" -Append
}

function Pick($arr) { $arr | Get-Random }

function Dispatch($body) {
  try {
    $json = $body | ConvertTo-Json -Depth 5
    $res = Invoke-RestMethod -Uri "http://localhost:8091/api/v1/scheduler/dispatch_task" `
      -Method Post -Body ([System.Text.Encoding]::UTF8.GetBytes($json)) `
      -ContentType "application/json" -TimeoutSec 10
    if ($res.status -eq 0) {
      Log ("  -> OK req={0}" -f $res.data.scheduler_request_id.Substring(0, 8))
    } else {
      Log ("  -> FAIL status={0} msg={1}" -f $res.status, $res.msg)
    }
  } catch {
    $detail = ""
    if ($_.ErrorDetails) { $detail = $_.ErrorDetails.Message }
    Log ("  -> ERROR {0} {1}" -f $_.Exception.Message, $detail)
  }
}

Log "==== 随机任务压测启动（停止：New-Item $stopFile）===="
$seq = 0
while ($true) {
  if (Test-Path $stopFile) { Log "==== 收到停止信号，退出 ===="; break }
  $seq++
  $ts = Get-Date -Format "HHmmss"
  $pickRobot = Pick $robots
  $use指定 = (Get-Random -Minimum 0 -Maximum 10) -lt 3   # 30% 指定机器人
  $robotTag = if ($use指定) { "指定 $pickRobot" } else { "自动分配" }

  # 随机选任务类型（权重：质检 40% / 移动 30% / 归巢 20% / 充电 10%）
  $roll = Get-Random -Minimum 0 -Maximum 100
  if ($roll -lt 40) {
    # 质检：作业点 + 随机停靠点（做完让位）
    $w = Pick $workstations
    $standby = Pick ($parkings | Where-Object { $_ -ne $w })
    $body = @{
      taskflow_id = "tf-rnd-$ts"; task_id = "rnd-qi-$seq-$ts"
      task_template_type = "quality_inspection"; nav_end_point = $w
      parameters = @{ task_template_code = "QC_CONTEXT"; end_point = $standby }
    }
    if ($use指定) { $body.robot_code = $pickRobot }
    Log ("[$seq] 质检 -> $w（停靠 $standby）[$robotTag]")
    Dispatch $body
  } elseif ($roll -lt 70) {
    # 移动：任意点
    $p = Pick $allPoints
    $body = @{
      taskflow_id = "tf-rnd-$ts"; task_id = "rnd-mv-$seq-$ts"
      task_template_type = "go_to_place"; nav_end_point = $p
    }
    if ($use指定) { $body.robot_code = $pickRobot }
    Log ("[$seq] 移动 -> $p [$robotTag]")
    Dispatch $body
  } elseif ($roll -lt 90) {
    # 归巢：随机停靠点（return_home 契约要求 robot_code）
    $h = Pick $parkings
    $body = @{
      taskflow_id = "tf-rnd-$ts"; task_id = "rnd-rh-$seq-$ts"
      task_template_type = "return_home"; nav_end_point = $h
      robot_code = $pickRobot
    }
    Log ("[$seq] 归巢 -> $h [指定 $pickRobot]")
    Dispatch $body
  } else {
    # 充电：随机充电点
    $c = Pick $chargers
    $body = @{
      taskflow_id = "tf-rnd-$ts"; task_id = "rnd-ch-$seq-$ts"
      task_template_type = "charge"; nav_end_point = $c
    }
    if ($use指定) { $body.robot_code = $pickRobot }
    Log ("[$seq] 充电 -> $c [$robotTag]")
    Dispatch $body
  }

  Start-Sleep -Seconds (Get-Random -Minimum 15 -Maximum 40)
}
