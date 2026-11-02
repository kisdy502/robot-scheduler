# Zone-based stress: map split into 4 quadrants; each robot works ONLY in its zone
# - QC tasks: random workstation in robot's zone; standby = nearest non-workstation point in zone
# - Charge: nearest charger to robot's current position (chargers may be centralized)
# - Stop: New-Item logs\zone.stop
$ErrorActionPreference = "Continue"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$base = "http://127.0.0.1:8091"
$logDir = "D:\robotscheduler\robot-scheduler\logs"
$stopFile = Join-Path $logDir "zone.stop"
if (Test-Path $stopFile) { Remove-Item $stopFile -Force }

$lowBattery = 20
$chargeProtectSeconds = 300
$script:chargeProtectUntil = @{}
$script:stats = @{ qi = 0; ch = 0; fail = 0 }

function Log($msg) {
  $line = "{0} {1}" -f (Get-Date -Format "HH:mm:ss"), $msg
  $line | Tee-Object -FilePath "$logDir\zone-stress.log" -Append
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
    Log "  -> ERROR $($_.Exception.Message)"; $script:stats.fail++; return $false
  }
}
function RobotX($r) { if ($r.pose -and $null -ne $r.pose.x) { [double]$r.pose.x } elseif ($null -ne $r.x) { [double]$r.x } else { 0 } }
function RobotY($r) { if ($r.pose -and $null -ne $r.pose.y) { [double]$r.pose.y } elseif ($null -ne $r.y) { [double]$r.y } else { 0 } }

# ---- map + zone model ----
$map = Invoke-RestMethod -Uri "$base/api/v1/maps/current/detail" -TimeoutSec 10
$waypoints = @()
foreach ($floor in $map.data.floors) { $waypoints += $floor.waypoints }
$minX = ($waypoints | Measure-Object x -Minimum).Minimum
$maxX = ($waypoints | Measure-Object x -Maximum).Maximum
$minY = ($waypoints | Measure-Object y -Minimum).Minimum
$maxY = ($waypoints | Measure-Object y -Maximum).Maximum
# split: x-median first (west/east), then per-half y-median (south/north)
# guarantees every zone has workstations even when Ws cluster (map1027S: W=2/2/3/2)
$allW = @($waypoints | Where-Object { $_.type -eq "workstation" })
$xs = @($allW | ForEach-Object { $_.x } | Sort-Object)
$midX = $xs[[int][Math]::Floor(($xs.Count - 1) / 2)]
$westW = @($allW | Where-Object { $_.x -lt $midX })
$eastW = @($allW | Where-Object { $_.x -ge $midX })
$westY = @($westW | ForEach-Object { $_.y } | Sort-Object)
$eastY = @($eastW | ForEach-Object { $_.y } | Sort-Object)
$westMidY = $westY[[int][Math]::Floor(($westY.Count - 1) / 2)]
$eastMidY = $eastY[[int][Math]::Floor(($eastY.Count - 1) / 2)]

function ZoneOf($x, $y) {
  # 0:SW 1:SE 2:NW 3:NE (per-half y split lines)
  if ($x -lt $midX) { if ($y -lt $westMidY) { return 0 } else { return 2 } }
  if ($y -lt $eastMidY) { return 1 }
  return 3
}

$zones = @{}
for ($z = 0; $z -lt 4; $z++) {
  $zones[$z] = @{
    work = @(); park = @()
  }
}
foreach ($wp in $waypoints) {
  $z = ZoneOf $wp.x $wp.y
  if ($wp.type -eq "workstation") { $zones[$z].work += $wp }
  elseif ($wp.type -ne "charge" -and $wp.type -ne "elevator") { $zones[$z].park += $wp }
}
$chargers = @($waypoints | Where-Object { $_.type -eq "charge" })
Log "==== zone stress start map=$($map.data.map_name) bounds=($([Math]::Round($minX,1)),$([Math]::Round($minY,1)))-($([Math]::Round($maxX,1)),$([Math]::Round($maxY,1)))) ===="
for ($z = 0; $z -lt 4; $z++) {
  Log "zone $z : W=$($zones[$z].work.Count) park=$($zones[$z].park.Count)"
}
if (@($zones.Values | Where-Object { $_.work.Count -eq 0 }).Count -gt 0) {
  Write-Error "some zone has no workstation"; exit 1
}

# ---- robot -> zone assignment (weighted by zone workstation count: fleet size proportional
#      to workload; robots deploy to their zone on first task, then stay in-zone) ----
$robotZone = @{}
$zonePattern = @()
for ($z = 0; $z -lt 4; $z++) {
  for ($i = 0; $i -lt $zones[$z].work.Count; $i++) { $zonePattern += $z }
}
function Assign-Zones($robots) {
  $slot = 0
  foreach ($r in ($robots | Sort-Object robot_code)) {
    $code = $r.robot_code
    if ($robotZone.ContainsKey($code)) { continue }
    $z = $zonePattern[$slot % $zonePattern.Count]
    $slot++
    $robotZone[$code] = $z
    Log "[zone] $code -> zone $z (from $(RobotX $r),$(RobotY $r))"
  }
}

function NearestPoint($from, $candidates) {
  $best = $null; $bestD = [double]::MaxValue
  foreach ($p in $candidates) {
    $d = [Math]::hypot($p.x - $from.x, $p.y - $from.y)
    if ($d -lt $bestD) { $bestD = $d; $best = $p }
  }
  return $best
}

function StandbyNear($w, $parks) {
  # nearest 3 parks random pick: spread dwell points, avoid all robots parking on the same one
  $sorted = @($parks | Sort-Object { [Math]::hypot($_.x - $w.x, $_.y - $w.y) })
  $top = @($sorted | Select-Object -First 3)
  if ($top.Count -eq 0) { return $null }
  return (Pick $top)
}

# ---- main loop ----
$cycle = 0
while ($true) {
  if (Test-Path $stopFile) { Log "==== stop signal, exit ===="; break }
  $cycle++
  try { $robots = (Invoke-RestMethod -Uri "$base/api/v1/runtime/fleets/snapshot" -TimeoutSec 8).data.robots } catch { $robots = @() }
  Assign-Zones $robots
  $ts = Get-Date -Format "HHmmss"

  foreach ($r in $robots) {
    $code = $r.robot_code
    if ($r.mode -ne "idle" -or -not $r.dispatchable -or $r.online_status -ne "online") { continue }
    $z = $robotZone[$code]
    $protected = $script:chargeProtectUntil[$code] -and [DateTime]::UtcNow -lt $script:chargeProtectUntil[$code]
    if ($r.mode -eq "charging" -or $protected) { continue }

    if ($null -ne $r.battery -and $r.battery -le $lowBattery) {
      $pos = @{ x = RobotX $r; y = RobotY $r }
      $c = NearestPoint $pos $chargers
      $ok = Dispatch @{
        taskflow_id = "tf-zone-$ts"; task_id = "zone-ch-$cycle-$code-$ts"
        task_template_code = "charge-liteflow"; task_template_type = "charge"
        nav_end_point = $c.id; robot_code = $code
        parameters = @{ action_timeout_seconds = 1800 }
      } "charge->$($c.id) [zone $z] battery=$($r.battery)%"
      if ($ok) {
        $script:stats.ch++
        $script:chargeProtectUntil[$code] = [DateTime]::UtcNow.AddSeconds($chargeProtectSeconds)
      }
    } else {
      $w = Pick $zones[$z].work
      $standby = StandbyNear $w $zones[$z].park
      $standbyId = if ($standby) { $standby.id } else { $w.id }
      $ok = Dispatch @{
        taskflow_id = "tf-zone-$ts"; task_id = "zone-qi-$cycle-$code-$ts"
        task_template_type = "quality_inspection"; nav_end_point = $w.id
        robot_code = $code
        parameters = @{ task_template_code = "QC_CONTEXT"; end_point = $standbyId }
      } "QC->$($w.id) standby->$standbyId [zone $z]"
      if ($ok) { $script:stats.qi++ }
    }
  }

  if ($cycle % 24 -eq 0) {
    $modes = ($robots | Group-Object mode | ForEach-Object { "$($_.Name)=$($_.Count)" }) -join " "
    Log "[stats] cycle=$cycle QC=$($script:stats.qi) charge=$($script:stats.ch) fail=$($script:stats.fail) | $modes"
  }
  Start-Sleep -Seconds 5
}
