# 混沌压测：满图随机深点 + 同点竞争追逐 + 随机取消 + 自动分配混合 + 低电返充
# 目标：触发死锁/对向冲突/交通管制等待/取消链路异常，暴露深水区 bug
# 停止：New-Item D:\robotscheduler\robot-scheduler\logs\chaos.stop
$ErrorActionPreference = "Continue"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$base = "http://127.0.0.1:8091"
$logDir = "D:\robotscheduler\robot-scheduler\logs"
$stopFile = Join-Path $logDir "chaos.stop"
if (Test-Path $stopFile) { Remove-Item $stopFile -Force }

$lowBattery = 20
$cancelEverySeconds = 100   # 随机取消节奏
$script:lastCancelAt = Get-Date
$script:recentTargets = @() # 最近目标（追逐用：多车汇聚同点制造冲突）
$script:stats = @{ qi=0; mv=0; ch=0; auto=0; cancel=0; fail=0 }

function Log($msg) {
  $line = "{0} {1}" -f (Get-Date -Format "HH:mm:ss"), $msg
  $line | Tee-Object -FilePath "$logDir\chaos.log" -Append
}
function Pick($arr) { $arr | Get-Random }
function Dispatch($body, $label) {
  try {
    $json = $body | ConvertTo-Json -Depth 5
    $res = Invoke-RestMethod -Uri "$base/api/v1/scheduler/dispatch_task" -Method Post `
      -Body ([System.Text.Encoding]::UTF8.GetBytes($json)) -ContentType "application/json" -TimeoutSec 10
    if ($res.status -eq 0) { Log "  -> OK $label"; return $true }
    Log "  -> FAIL status=$($res.status) msg=$($res.msg)"; $script:stats.fail++; return $false
  } catch {
    $d = if ($_.ErrorDetails) { $_.ErrorDetails.Message } else { $_.Exception.Message }
    Log "  -> ERROR $d"; $script:stats.fail++; return $false
  }
}

$map = Invoke-RestMethod -Uri "$base/api/v1/maps/current/detail" -TimeoutSec 10
$waypoints = @()
foreach ($floor in $map.data.floors) { $waypoints += $floor.waypoints }
$workstations = @($waypoints | Where-Object { $_.type -eq "workstation" } | ForEach-Object { $_.id })
$parkings     = @($waypoints | Where-Object { $_.type -eq "parking" } | ForEach-Object { $_.id })
$chargers     = @($waypoints | Where-Object { $_.type -eq "charge" } | ForEach-Object { $_.id })
$allMoves     = @($waypoints | Where-Object { $_.type -ne "charge" } | ForEach-Object { $_.id })
Log "==== 混沌压测启动 map=$($map.data.map_name)（W=$($workstations.Count) H=$($parkings.Count) C=$($chargers.Count) 全图移动点=$($allMoves.Count)；停止：New-Item $stopFile）===="

$cycle = 0
while ($true) {
  if (Test-Path $stopFile) { Log "==== 收到停止信号，退出。累计: $(($script:stats | Format-List | Out-String).Replace("`n",' ')) ===="; break }
  $cycle++
  try { $robots = (Invoke-RestMethod -Uri "$base/api/v1/runtime/fleets/snapshot" -TimeoutSec 8).data.robots } catch { $robots = @() }

  # ---- 随机取消（每 cancelEverySeconds 一单，打取消链路：stop + completed+cancellation + 资源释放）----
  if (((Get-Date) - $script:lastCancelAt).TotalSeconds -ge $cancelEverySeconds) {
    $running = @($robots | Where-Object { $_.mode -eq "running" })
    if ($running.Count -gt 0) {
      $victim = Pick $running
      $script:lastCancelAt = Get-Date
      $script:stats.cancel++
      try {
        $body = @{ robot_code = $victim.robot_code; reason = "chaos-kill" } | ConvertTo-Json
        $r = Invoke-RestMethod -Uri "$base/api/v1/scheduler/stop-task" -Method Post `
          -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) -ContentType "application/json" -TimeoutSec 10
        Log "[混沌] 取消 $($victim.robot_code)（running 随机击杀）-> status=$($r.status) msg=$($r.msg)"
      } catch { Log "[混沌] 取消失败: $($_.Exception.Message)" }
    }
  }

  # ---- 派发：空闲车零空闲 + 30% 走自动分配（测 ETA 评分/充电中断策略）----
  $ts = Get-Date -Format "HHmmss"
  foreach ($r in $robots) {
    $code = $r.robot_code
    if ($r.mode -ne "idle" -or -not $r.dispatchable -or $r.online_status -ne "online") { continue }
    $roll = Get-Random -Minimum 0 -Maximum 100
    $useAuto = ($roll -ge 70)   # 30% 自动分配
    $targetCode = if ($useAuto) { $null } else { $code }

    if ($null -ne $r.battery -and $r.battery -le $lowBattery) {
      $c = Pick $chargers
      $script:stats.ch++
      $ok = Dispatch @{ taskflow_id="tf-chaos-$ts"; task_id="chaos-ch-$cycle-$code-$ts";
        task_template_code="charge-liteflow"; task_template_type="charge"; nav_end_point=$c;
        robot_code=$targetCode; parameters=@{ action_timeout_seconds=1800 } } "充电->$c [$code]"
    }
    elseif ($roll -lt 20 -and $script:recentTargets.Count -gt 0) {
      # 20% 追逐：冲向最近别的车的目标 → 汇聚冲突/对向占用
      $t = Pick $script:recentTargets
      $script:stats.mv++
      $ok = Dispatch @{ taskflow_id="tf-chaos-$ts"; task_id="chaos-mv-$cycle-$code-$ts";
        task_template_type="go_to_place"; nav_end_point=$t; robot_code=$targetCode } "追逐->$t [$code]"
    }
    elseif ($roll -lt 55) {
      $w = Pick $workstations
      $standby = Pick ($parkings | Where-Object { $_ -ne $w })
      $script:stats.qi++
      $ok = Dispatch @{ taskflow_id="tf-chaos-$ts"; task_id="chaos-qi-$cycle-$code-$ts";
        task_template_type="quality_inspection"; nav_end_point=$w; robot_code=$targetCode;
        parameters=@{ task_template_code="QC_CONTEXT"; end_point=$standby } } "质检->$w(停靠$standby) [$code]"
      if ($ok) { $script:recentTargets = @($script:recentTargets + $w | Select-Object -Last 6) }
    }
    else {
      # 满图随机（含深处远点）
      $p = Pick $allMoves
      $script:stats.mv++
      $ok = Dispatch @{ taskflow_id="tf-chaos-$ts"; task_id="chaos-mv-$cycle-$code-$ts";
        task_template_type="go_to_place"; nav_end_point=$p; robot_code=$targetCode } "移动->$p [$code]"
      if ($ok) { $script:recentTargets = @($script:recentTargets + $p | Select-Object -Last 6) }
    }
    if ($useAuto) { $script:stats.auto++ }
  }

  if ($cycle % 20 -eq 0) {
    $modes = ($robots | Group-Object mode | ForEach-Object { "$($_.Name)=$($_.Count)" }) -join " "
    Log "[统计] cycle=$cycle 质检=$($script:stats.qi) 移动=$($script:stats.mv) 充电=$($script:stats.ch) 自动分配=$($script:stats.auto) 取消=$($script:stats.cancel) 失败=$($script:stats.fail) | $modes"
  }
  Start-Sleep -Seconds 4
}
