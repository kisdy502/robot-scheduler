# bind-mocks: start qcrobotmock for OFFLINE fleet robots using their REAL serial_no
# (RMS register binds by SN -> revives the existing named entry instead of creating a new one)
$BaseUrl = "http://127.0.0.1:18083"
$jar = "D:\workspace\qc-robot\qcrobotmock_new\target\qcrobotmock-0.0.1-SNAPSHOT.jar"
$config = "D:\robotscheduler\robot-scheduler\scripts\sim-robot-config.yml"
$base = "http://127.0.0.1:8091"
$logDir = "D:\robotscheduler\robot-scheduler\logs"

function Get-RmsJson([string]$Uri) {
  $resp = Invoke-WebRequest -Uri $Uri -UseBasicParsing
  [Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray()) | ConvertFrom-Json
}

# current fleet waypoints for initial positions
$map = Invoke-RestMethod -Uri "$base/api/v1/maps/current/detail" -TimeoutSec 10
$pts = @($map.data.floors[0].waypoints | Where-Object { $_.type -ne "charge" })

$list = Get-RmsJson "$BaseUrl/api/v1/robot-management/robots?page_num=1&page_size=50"
$targets = @($list.data.list | Where-Object { $_.online_status -eq "offline" -and $_.group_no -eq "RG_20260407_00001" })
"offline grouped robots: $($targets.Count)"

$port = 8130
foreach ($robot in $targets) {
  $code = $robot.robot_code
  $detail = (Get-RmsJson "$BaseUrl/api/v1/robot-management/robots/$($robot.id)").data
  $sn = $detail.robot_sn
  if (-not $sn) { "SKIP $code (no sn)"; continue }
  $wp = $pts[(Get-Random -Minimum 0 -Maximum $pts.Count)]
  $p = Start-Process java -ArgumentList @(
    "-jar", $jar, "--config=$config", "--server.port=$port",
    "--robot.robot-code=$code", "--robot.serial-no=$sn", "--robot.robot-name=$($detail.robot_name)",
    "--motion.initial-x=$($wp.x)", "--motion.initial-y=$($wp.y)",
    "--simulation.initial-battery-percent=$(Get-Random -Minimum 80 -Maximum 96)"
  ) -RedirectStandardOutput "$logDir\mock-$code.log" -RedirectStandardError "$logDir\mock-$code-err.log" -PassThru
  "started $code sn=$sn at $($wp.id) port=$port pid=$($p.Id)"
  Start-Sleep -Seconds 8
  $port++
}
