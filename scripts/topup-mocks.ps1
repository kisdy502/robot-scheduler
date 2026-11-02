# top-up: restart failed mocks one-by-one (staggered registration to avoid zenoh query timeout)
$jar = "D:\workspace\qc-robot\qcrobotmock_new\target\qcrobotmock-0.0.1-SNAPSHOT.jar"
$config = "D:\robotscheduler\robot-scheduler\scripts\sim-robot-config.yml"
$base = "http://127.0.0.1:8091"
$logDir = "D:\robotscheduler\robot-scheduler\logs"

$map = Invoke-RestMethod -Uri "$base/api/v1/maps/current/detail" -TimeoutSec 10
$pts = @($map.data.floors[0].waypoints | Where-Object { $_.type -ne "charge" -and $_.type -ne "elevator" })

$failed = @("005","006","007","008","009","010","011","013")
$port = 8115
$idx = 0
foreach ($n in $failed) {
  $idx++
  $wp = $pts[(Get-Random -Minimum 0 -Maximum $pts.Count)]
  $battery = if ($idx -eq 6) { 15 } else { Get-Random -Minimum 80 -Maximum 96 }
  $p = Start-Process java -ArgumentList @(
    "-jar", $jar, "--config=$config", "--server.port=$port",
    "--robot.robot-code=RB_SIM_$n", "--robot.serial-no=SN_RB_SIM_$n", "--robot.robot-name=RB_SIM_$n",
    "--motion.initial-x=$($wp.x)", "--motion.initial-y=$($wp.y)",
    "--simulation.initial-battery-percent=$battery"
  ) -RedirectStandardOutput "$logDir\mock-sim$n.log" -RedirectStandardError "$logDir\mock-sim$n-err.log" -PassThru
  "started RB_SIM_$n at $($wp.id) battery=$battery port=$port pid=$($p.Id)"
  Start-Sleep -Seconds 8
  $port++
}
"waiting 20s then assign groups..."
Start-Sleep -Seconds 20
$resp = Invoke-WebRequest -Uri "http://127.0.0.1:18083/api/v1/robot-management/robots?page_num=1&page_size=50&keyword=RB_SIM_" -UseBasicParsing
$json = [Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray()) | ConvertFrom-Json
$newCodes = @($json.data.list | Where-Object { -not $_.group_no } | ForEach-Object { $_.robot_code })
"ungrouped: $($newCodes -join ', ')"
if ($newCodes.Count -gt 0) {
  & powershell -ExecutionPolicy Bypass -File "D:\robotscheduler\robot-scheduler\scripts\assign-group.ps1" $newCodes
}
