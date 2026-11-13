# 两台仿真机器人随机压测：轮流派发 移动/质检/充电 全业务流（抢占→goto-place→[start-activity]→释放）
# 用法：
#   powershell -File sim-stress.ps1                 # 父模式：自动发现在线机器人逐台拉起车道 + 汇总
#   powershell -File sim-stress.ps1 -LaneRobot RB_x # 车道模式（内部用）
# 停止：新建空文件 logs\sim-stress.stop
param(
    [string]$LaneRobot = "",
    [int]$GapSeconds = 3,
    [int]$MaxRounds = 100000,
    [int]$LowBatteryPercent = 20,
    [int]$ResumeBatteryPercent = 50
)

$Scheduler = "http://127.0.0.1:8091"
$Rms = "http://127.0.0.1:18083"
$QcTemplate = "LAD-28-A-not-lift"
$ChargeTemplate = "back-charge"
$StopFile = Join-Path $PSScriptRoot "..\logs\sim-stress.stop"
$ResultFile = Join-Path $PSScriptRoot "..\logs\sim-stress-results.jsonl"
$MoveTimeoutSec = 600
$ActionTimeoutSec = 600

# ---------------- 公共工具 ----------------
$script:seq = Get-Random -Maximum 900
function New-TaskFlowId {
    $script:seq = ($script:seq + 1) % 1000
    return [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() * 1000 + $script:seq
}
function New-BizTs { return (Get-Date -Format "yyyyMMddHHmmssfff") }
function ShouldStop { return (Test-Path $StopFile) }

function DetailOf($body, $raw) {
    if ($body -and $body.detail) { return "$($body.detail)" }
    if ($body -and $body.msg) { return "$($body.msg)" }
    return "$raw"
}
function MsgOf($body, $raw) {
    if ($body -and $body.msg) { return "$($body.msg)" }
    return "$raw"
}
function ThetaOf($wp) {
    if ($wp.theta) { return $wp.theta }
    return 0
}

function Get-Json([string]$Url) {
    try {
        $r = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 10
        return ([Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray()) | ConvertFrom-Json)
    } catch { return $null }
}

function Post-Json([string]$Url, [string]$Json) {
    try {
        $r = Invoke-WebRequest -Uri $Url -Method Post -Body $Json `
            -ContentType "application/json; charset=utf-8" -UseBasicParsing -TimeoutSec 20
        $body = ([Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray()) | ConvertFrom-Json)
        return @{ ok = ($r.StatusCode -ge 200 -and $r.StatusCode -lt 300); status = $r.StatusCode; body = $body; raw = "" }
    } catch {
        $detail = $_.Exception.Message
        try {
            if ($_.Exception.Response) {
                $sr = New-Object IO.StreamReader($_.Exception.Response.GetResponseStream())
                $detail = $sr.ReadToEnd()
            }
        } catch { }
        return @{ ok = $false; status = 0; body = $null; raw = $detail }
    }
}

function Append-Result([string]$Robot, [string]$Type, [string]$TaskId, [string]$Result,
                       [string]$Stage, [string]$Reason, [long]$DurationMs) {
    $o = [ordered]@{
        ts = (Get-Date -Format "HH:mm:ss.fff"); robot = $Robot; type = $Type; task_id = $TaskId
        result = $Result; stage = $Stage; reason = $Reason; duration_ms = $DurationMs
    }
    Add-Content -Path $ResultFile -Value ($o | ConvertTo-Json -Compress) -Encoding UTF8
}

function Wait-MoveDone([string]$TaskId) {
    $deadline = (Get-Date).AddSeconds($MoveTimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (ShouldStop) { return @{ ok = $false; reason = "stopped" } }
        $j = Get-Json "$Scheduler/api/v1/scheduler/tasks/$TaskId/status"
        if ($j -and $j.data) {
            $st = $j.data.task_status
            if ($st -eq "completed") { return @{ ok = $true; reason = "" } }
            if ($st -eq "failed" -or $st -eq "canceled") {
                return @{ ok = $false; reason = ("move {0}: {1}" -f $st, $j.data.message) }
            }
        }
        Start-Sleep -Seconds 2
    }
    return @{ ok = $false; reason = "move timeout ${MoveTimeoutSec}s" }
}

# 客户端超时后强停任务并等终态（引擎可能仍在正常执行——超时≠任务死，直接释放会被 409 拒且占用成孤儿）
function Stop-And-WaitTerminal([string]$TaskId, [int]$GraceSec) {
    $null = Post-Json "$Scheduler/api/v1/scheduler/stop-task" ('{"task_id":"' + $TaskId + '","reason":"stress move timeout"}')
    $deadline = (Get-Date).AddSeconds($GraceSec)
    while ((Get-Date) -lt $deadline) {
        if (ShouldStop) { return }
        $j = Get-Json "$Scheduler/api/v1/scheduler/tasks/$TaskId/status"
        $st = $j.data.task_status
        if ($st -eq "completed" -or $st -eq "failed" -or $st -eq "canceled") { return }
        Start-Sleep -Seconds 3
    }
}

# 释放带退避重试：任务收尾有窄窗口，409 时重试而非放弃（放弃=占用孤儿=机器人死锁）。
# 注意：不做 ShouldStop 短路——停止时收尾轮次的释放是清理动作必须执行，否则占用成孤儿锁死机器人。
function Release-WithRetry([string]$Tf, [string]$Robot) {
    $body = ('{"taskflow_id":"' + $Tf + '","robot_code":"' + $Robot + '"}')
    for ($i = 1; $i -le 5; $i++) {
        $r = Post-Json "$Scheduler/api/v1/scheduler/release-robot" $body
        if ($r.ok) { return $true }
        Start-Sleep -Seconds (10 * $i)
    }
    return $false
}

function Wait-ActionDone([string]$Robot, [string]$BodyTaskId) {
    $deadline = (Get-Date).AddSeconds($ActionTimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (ShouldStop) { return @{ ok = $false; reason = "stopped" } }
        $j = Get-Json "$Scheduler/api/v1/observability/activities?task_id=$BodyTaskId&page_size=10"
        if ($j -and $j.data -and $j.data.list) {
            $found = @($j.data.list | Where-Object { $_.body_task_id -eq $BodyTaskId -and $_.robot_code -eq $Robot })
            if ($found.Count -gt 0) {
                $a = $found[0]
                if ($a.action_status -eq 3) { return @{ ok = $true; reason = "" } }
                return @{ ok = $false; reason = ("action_status={0}: {1}" -f $a.action_status, $a.failure_msg) }
            }
        }
        Start-Sleep -Seconds 2
    }
    return @{ ok = $false; reason = "action timeout ${ActionTimeoutSec}s" }
}

function Get-Waypoints([string]$Type) {
    $q = ""
    if ($Type) { $q = "?type=$Type" }
    $j = Get-Json "$Scheduler/api/v1/maps/current/waypoints$q"
    return @($j.data)
}

# 等机器人可派（充电中/停止确认中安静等待，不烧失败轮次；最多 15 分钟）
function Wait-RobotReady([string]$Robot) {
    $deadline = (Get-Date).AddMinutes(15)
    while ((Get-Date) -lt $deadline) {
        if (ShouldStop) { return $false }
        $j = Get-Json "$Scheduler/api/v1/runtime/fleets/snapshot"
        $r = @($j.data.robots | Where-Object { $_.robot_code -eq $Robot })
        if ($r.Count -gt 0 -and $r[0].dispatchable -eq $true) { return $true }
        Start-Sleep -Seconds 10
    }
    return $false
}

# 机器人快照（电量/mode/位置）；离线返回 null
function Get-RobotState([string]$Robot) {
    $j = Get-Json "$Scheduler/api/v1/runtime/fleets/snapshot"
    $r = @($j.data.robots | Where-Object { $_.robot_code -eq $Robot })
    if ($r.Count -eq 0) { return $null }
    return $r[0]
}

# 最近充电位（按机器人当前位置欧氏距离）
function Select-NearestCharger([object]$RobotState, [array]$Chargers) {
    $best = $null
    $bestD = [double]::MaxValue
    foreach ($c in $Chargers) {
        $dx = [double]$c.x - [double]$RobotState.pose.x
        $dy = [double]$c.y - [double]$RobotState.pose.y
        $d = [Math]::Sqrt($dx * $dx + $dy * $dy)
        if ($d -lt $bestD) { $bestD = $d; $best = $c }
    }
    return $best
}

# 电量驱动回充：低电 → 移动到最近充电位 → 释放 → 等电量恢复 → RMS stop 取消充电 → 返回
function Ensure-Charged([string]$Robot, [array]$Chargers) {
    $state = Get-RobotState $Robot
    if ($null -eq $state -or $null -eq $state.battery) { return }
    if ([double]$state.battery -ge $LowBatteryPercent) { return }

    $bat = [double]$state.battery
    Write-Output ("[lane {0}] 低电回充: {1}% < {2}%，移动到最近充电位" -f $Robot, $bat, $LowBatteryPercent)
    $charger = Select-NearestCharger $state $Chargers
    if ($null -eq $charger) { Write-Output "[lane $Robot] 无充电位可用"; return }

    # 抢占 + 移动到充电位（纯移动，本体到位自充）
    $tf = New-TaskFlowId
    $taskId = "$tf$(New-BizTs)"
    $ab = ('{"taskflow_id":' + $tf + ',"task_template_type_list":["charge"],"robot_code":"' + $Robot + '"}')
    $r = Post-Json "$Scheduler/api/v1/scheduler/assign-task" $ab
    if (-not $r.ok) { Write-Output ("[lane $Robot] 回充抢占失败: " + (DetailOf $r.body $r.raw)); return }
    $gb = ('{"taskflow_id":"' + $tf + '","task_id":"' + $taskId + '","robot_code":"' + $Robot + '","nav_target_point":"' + $charger.id + '"}')
    $r = Post-Json "$Scheduler/api/v1/scheduler/goto-place" $gb
    if ($r.ok) {
        $null = Wait-MoveDone $taskId
    }
    $null = Release-WithRetry $tf $Robot
    Append-Result $Robot "charge" $taskId "ok" "-" ("低电回充 {0}% -> {1}" -f $bat, $charger.id) 0

    # 等电量恢复到作业阈值
    Write-Output ("[lane {0}] 充电中，等待电量 >= {1}%" -f $Robot, $ResumeBatteryPercent)
    while (-not (ShouldStop)) {
        Start-Sleep -Seconds 30
        $state = Get-RobotState $Robot
        if ($null -eq $state -or $null -eq $state.battery) { continue }
        if ([double]$state.battery -ge $ResumeBatteryPercent) { break }
    }
    if (ShouldStop) { return }

    # 取消充电：充电中机器人不可派发，先 RMS stop 让本体退出充电态，再等可派
    $state = Get-RobotState $Robot
    if ($state.mode -eq "charging") {
        Write-Output ("[lane {0}] 电量 {1}% >= {2}%，RMS stop 取消充电" -f $Robot, $state.battery, $ResumeBatteryPercent)
        $sb = ('{"running_cmd_id":"0","stop_cmd_id":"' + (Get-Random -Maximum 100000) + '"}')
        $null = Post-Json "$Rms/api/v1/robot-management/rmf/robots/$Robot/stop" $sb
        $deadline = (Get-Date).AddSeconds(60)
        while ((Get-Date) -lt $deadline -and -not (ShouldStop)) {
            Start-Sleep -Seconds 3
            $state = Get-RobotState $Robot
            if ($null -ne $state -and $state.mode -ne "charging") { break }
        }
    }
    Write-Output ("[lane {0}] 电量恢复 {1}%，继续作业" -f $Robot, (Get-RobotState $Robot).battery)
}

# ---------------- 车道模式 ----------------
function Run-Lane([string]$Robot) {
    Write-Output ("[lane] robot={0} start" -f $Robot)
    $wpAll = Get-Waypoints $null
    $wpWork = Get-Waypoints "workstation"
    $wpCharge = Get-Waypoints "charge"
    Write-Output ("[lane] waypoints: all={0} work={1} charge={2}" -f $wpAll.Count, $wpWork.Count, $wpCharge.Count)

    for ($i = 1; $i -le $MaxRounds; $i++) {
        if (ShouldStop) { break }
        # 电量驱动：低电回充（到位自充 → ≥50% RMS stop 取消 → 恢复作业）
        Ensure-Charged $Robot $wpCharge
        if (ShouldStop) { break }
        if (-not (Wait-RobotReady $Robot)) { continue }
        # 作业类型：移动/质检 各 50%（充电由电量驱动不再随机派）
        $dice = Get-Random -Maximum 10
        $type = "move"
        if ($dice -gt 4) { $type = "qc" }
        $wp = $null
        if ($type -eq "move") { $wp = $wpAll | Get-Random }
        elseif ($type -eq "qc") { $wp = $wpWork | Get-Random }
        else { $wp = $wpCharge | Get-Random }
        if ($null -eq $wp) { continue }

        $tf = New-TaskFlowId
        $taskId = "$tf$(New-BizTs)"
        $sw = [Diagnostics.Stopwatch]::StartNew()
        $stage = "assign"
        $failReason = ""
        $assigned = $false

        $assignBody = ('{"taskflow_id":' + $tf + ',"task_template_type_list":["quality_inspection"],"robot_code":"' + $Robot + '"}')
        $r = Post-Json "$Scheduler/api/v1/scheduler/assign-task" $assignBody
        if (-not $r.ok) {
            $failReason = "assign HTTP " + $r.status + ": " + (DetailOf $r.body $r.raw)
        } else { $assigned = $true }

        if ($assigned -and $failReason -eq "") {
            $stage = "move"
            $gotoBody = ('{"taskflow_id":"' + $tf + '","task_id":"' + $taskId + '","robot_code":"' + $Robot + '","nav_target_point":"' + $wp.id + '"}')
            $r = Post-Json "$Scheduler/api/v1/scheduler/goto-place" $gotoBody
            if (-not $r.ok) {
                $failReason = "goto HTTP " + $r.status + ": " + (DetailOf $r.body $r.raw)
            } else {
                $m = Wait-MoveDone $taskId
                if (-not $m.ok) {
                    # 超时/失败都先等任务收尾：强停 + 等终态，保证释放能成功
                    Stop-And-WaitTerminal $taskId 90
                    $failReason = $m.reason
                }
            }
        }

        if ($assigned -and $failReason -eq "" -and $type -ne "move") {
            $stage = "action"
            $activity = "quality_inspection"
            $template = $QcTemplate
            if ($type -eq "charge") { $activity = $ChargeTemplate; $template = $ChargeTemplate }
            $desc = @{
                rmf_task_id = "stress.$taskId"; task_template_type = $type
                task_template_code = $template; end_point = "$($wp.id)"
                destination = @{ x = [double]$wp.x; y = [double]$wp.y; yaw = [double](ThetaOf $wp) }
                task_id = $taskId; taskflow_id = "$tf"; nav_target_point = "$($wp.id)"
            }
            $payload = @{ activity = $activity; description = $desc; label = "$tf|$template"; cmd_id = 1 }
            $r = Post-Json "$Rms/api/v1/robot-management/rmf/robots/$Robot/start-activity" ($payload | ConvertTo-Json -Depth 6)
            if (-not $r.ok -or ($r.body -and $r.body.status -ne 0)) {
                $failReason = "start-activity: " + (MsgOf $r.body $r.raw)
            } else {
                $a = Wait-ActionDone $Robot $taskId
                if (-not $a.ok) { $failReason = $a.reason }
            }
        }

        if ($assigned) {
            $stage = "release"
            $released = Release-WithRetry $tf $Robot
            if (-not $released -and $failReason -eq "") {
                $failReason = "release failed after retries"
            }
        }

        $sw.Stop()
        $ok = ($failReason -eq "")
        $resTxt = "fail"; $stageTxt = $stage; $reasonTxt = $failReason
        if ($ok) { $resTxt = "ok"; $stageTxt = "-"; $reasonTxt = "-" }
        Append-Result $Robot $type $taskId $resTxt $stageTxt $reasonTxt $sw.ElapsedMilliseconds
        Write-Output ("[lane {0}] #{1} {2} {3} -> {4} {5}ms {6}" -f $Robot, $i, $type, $wp.id,
            $resTxt.ToUpper(), $sw.ElapsedMilliseconds, $reasonTxt)
        Start-Sleep -Seconds $GapSeconds
    }
    Write-Output ("[lane] robot={0} exit" -f $Robot)
}

# ---------------- 父模式 ----------------
function Run-Parent {
    if (Test-Path $StopFile) { Remove-Item $StopFile -Force }
    New-Item -ItemType Directory -Path (Split-Path $ResultFile) -Force | Out-Null
    "" | Out-File $ResultFile -Encoding UTF8
    $j = Get-Json "$Scheduler/api/v1/runtime/fleets/snapshot"
    $online = @($j.data.robots | Where-Object { $_.online_status -eq "online" } | Select-Object -ExpandProperty robot_code)
    if ($online.Count -eq 0) { Write-Output "no online robot, exit"; return }
    Write-Output ("[stress] online robots: " + ($online -join ", "))
    $procs = @()
    foreach ($code in $online) {
        $procs += Start-Process powershell -PassThru -WindowStyle Minimized -ArgumentList @(
            "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $PSCommandPath, "-LaneRobot", $code)
    }
    Write-Output ("[stress] lanes started: {0}; stop file: {1}" -f $procs.Count, $StopFile)
    Write-Output "[stress] waiting... (create stop file to finish)"
    foreach ($p in $procs) { Wait-Process -Id $p.Id -ErrorAction SilentlyContinue }
    Start-Sleep -Seconds 2

    $rows = @()
    foreach ($line in (Get-Content $ResultFile -Encoding UTF8)) {
        if ($line.Trim()) {
            try { $rows += ($line | ConvertFrom-Json) } catch { }
        }
    }
    $okRows = @($rows | Where-Object { $_.result -eq "ok" })
    $failRows = @($rows | Where-Object { $_.result -eq "fail" })
    Write-Output "==== SIM STRESS SUMMARY ===="
    $rate = 0
    if ($rows.Count) { $rate = $okRows.Count / $rows.Count }
    Write-Output ("total: {0}  ok: {1}  fail: {2}  success rate: {3:P1}" -f $rows.Count, $okRows.Count, $failRows.Count, $rate)
    foreach ($g in ($rows | Group-Object type)) {
        $gok = @($g.Group | Where-Object { $_.result -eq "ok" }).Count
        $trate = 0
        if ($g.Count) { $trate = $gok / $g.Count }
        Write-Output ("  {0}: total={1} ok={2} rate={3:P0}" -f $g.Name, $g.Count, $gok, $trate)
    }
    if ($failRows.Count) {
        Write-Output "---- failure breakdown ----"
        $failRows | Group-Object stage, reason | Sort-Object Count -Descending | ForEach-Object {
            Write-Output ("  [{0}] x{1}" -f $_.Name, $_.Count)
        }
    }
    if ($okRows.Count) {
        $avg = ($okRows | Measure-Object duration_ms -Average).Average
        Write-Output ("avg ok duration: {0:N0} ms" -f $avg)
    }
    Write-Output ("detail log: {0}" -f $ResultFile)
}

if ($LaneRobot -ne "") { Run-Lane $LaneRobot } else { Run-Parent }
