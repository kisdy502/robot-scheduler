# deploy-mocks-v2: start 19 qcrobotmock processes (auto-register in RMS, group assigned later)
# ASCII only (PS 5.1 + no-BOM safe). Robots use code RB_SIM_NNN as NAME; RMS generates real code RB_20261102_XXXX.
$jar = "D:\workspace\qc-robot\qcrobotmock_new\target\qcrobotmock-0.0.1-SNAPSHOT.jar"
$config = "D:\robotscheduler\robot-scheduler\scripts\sim-robot-config.yml"
$base = "http://127.0.0.1:8091"
$logDir = "D:\robotscheduler\robot-scheduler\logs"

$map = Invoke-RestMethod -Uri "$base/api/v1/maps/current/detail" -TimeoutSec 10
$waypoints = @()
foreach ($floor in $map.data.floors) { $waypoints += $floor.waypoints }
$pts = @($waypoints | Where-Object { $_.type -ne "charge" -and $_.type -ne "elevator" })
"candidate points: $($pts.Count)"
if ($pts.Count -lt 19) { Write-Error "not enough points"; exit 1 }
$step = $pts.Count / 19.0
$selected = for ($i = 0; $i -lt 19; $i++) { $pts[[int][Math]::Floor($i * $step)] }

$port = 8096
$idx = 1
foreach ($wp in $selected) {
  $n = "{0:d3}" -f $idx
  $battery = if ($idx -eq 15 -or $idx -eq 16) { 18 } else { Get-Random -Minimum 80 -Maximum 96 }
  $p = Start-Process java -ArgumentList @(
    "-jar", $jar, "--config=$config", "--server.port=$port",
    "--robot.robot-code=RB_SIM_$n", "--robot.serial-no=SN_RB_SIM_$n", "--robot.robot-name=RB_SIM_$n",
    "--motion.initial-x=$($wp.x)", "--motion.initial-y=$($wp.y)",
    "--simulation.initial-battery-percent=$battery"
  ) -RedirectStandardOutput "$logDir\mock-sim$n.log" -RedirectStandardError "$logDir\mock-sim$n-err.log" -PassThru
  "started RB_SIM_$n at $($wp.id) battery=$battery port=$port pid=$($p.Id)"
  $idx++; $port++
}
"waiting 25s for auto-register..."
Start-Sleep -Seconds 25

# discover auto-registered codes (name RB_SIM_*, group empty)
$resp = Invoke-WebRequest -Uri "http://127.0.0.1:18083/api/v1/robot-management/robots?page_num=1&page_size=50&keyword=RB_SIM_" -UseBasicParsing
$json = [Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray()) | ConvertFrom-Json
$newCodes = @($json.data.list | Where-Object { -not $_.group_no } | ForEach-Object { $_.robot_code })
"discovered new codes: $($newCodes -join ', ')"
if ($newCodes.Count -gt 0) {
  & powershell -ExecutionPolicy Bypass -File "D:\robotscheduler\robot-scheduler\scripts\assign-group.ps1" $newCodes
} else {
  "WARN: no ungrouped RB_SIM_ entries found; check keyword search"
}
