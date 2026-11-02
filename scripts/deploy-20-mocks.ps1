# 部署 20 台 map1027S mock 机器人（配合 stress-10-robots.ps1 零空闲压测）
# 点位动态取自调度当前激活地图（非充电/电梯点均匀分布，保证在图可吸附）
# 电量：2 台低电（18%，触发充电任务链路），其余 85-95%
# 停止：taskkill 全部 qcrobotmock 进程
$jar = "D:\workspace\qc-robot\qcrobotmock_new\target\qcrobotmock-0.0.1-SNAPSHOT.jar"
$config = "D:\robotscheduler\robot-scheduler\scripts\sim-robot-config.yml"
$base = "http://127.0.0.1:8091"
$logDir = "D:\robotscheduler\robot-scheduler\logs"

# 1. 当前地图点位
$map = Invoke-RestMethod -Uri "$base/api/v1/maps/current/detail" -TimeoutSec 10
$waypoints = @()
foreach ($floor in $map.data.floors) { $waypoints += $floor.waypoints }
$pts = @($waypoints | Where-Object { $_.type -ne "charge" -and $_.type -ne "elevator" })
"map=$($map.data.map_name) version=$($map.data.version) candidate-points=$($pts.Count)"
if ($pts.Count -lt 20) { Write-Error "可用初始点位不足 20"; exit 1 }

# 均匀取 20 个（相邻车拉开距离，避免初始就挤在一起）
$step = $pts.Count / 20.0
$selected = for ($i = 0; $i -lt 20; $i++) { $pts[[int][Math]::Floor($i * $step)] }

# 2. 清理旧 mock
Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match "qcrobotmock" } | ForEach-Object { taskkill /PID $_.ProcessId /F | Out-Null }
Start-Sleep -Seconds 3
"old mocks cleared"

# 3. 启动 RB_SIM_002..021（端口 8095 起；电量 17/18 号位低电触发返充链路）
$port = 8095
$idx = 0
foreach ($wp in $selected) {
  $idx++
  $code = "RB_SIM_{0:d3}" -f ($idx + 1)
  $battery = if ($idx -eq 17 -or $idx -eq 18) { 18 } else { Get-Random -Minimum 85 -Maximum 96 }
  $p = Start-Process java -ArgumentList @(
    "-jar", $jar,
    "--config=$config",
    "--server.port=$port",
    "--robot.robot-code=$code",
    "--robot.serial-no=SN_$code",
    "--robot.robot-name=$code",
    "--motion.initial-x=$($wp.x)",
    "--motion.initial-y=$($wp.y)",
    "--simulation.initial-battery-percent=$battery"
  ) -RedirectStandardOutput "$logDir\mock-$code.log" -RedirectStandardError "$logDir\mock-$code-err.log" -PassThru
  "started $code at $($wp.id) ($($wp.x),$($wp.y)) battery=$battery% port=$port PID=$($p.Id)"
  $port++
}
"all started; wait ~30s for zenoh register + RMS inventory sync"
