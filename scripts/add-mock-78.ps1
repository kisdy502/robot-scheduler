# 追加 2 台 mock（RB_SIM_007/008）到非作业点（P5/P3）
$jar = "D:\workspace\qc-robot\qcrobotmock_new\target\qcrobotmock-0.0.1-SNAPSHOT.jar"
$configDir = "D:\robotscheduler\robot-scheduler\scripts\sim-configs"
$logDir = "D:\robotscheduler\robot-scheduler\logs"
$baseConfig = "D:\robotscheduler\robot-scheduler\scripts\sim-robot-config.yml"

$newRobots = @(
  @{ code = "RB_SIM_007"; pos = "P5"; x = 7.8;  y = -12.6 },
  @{ code = "RB_SIM_008"; pos = "P3"; x = 10.4; y = -0.7 }
)

foreach ($r in $newRobots) {
  $content = Get-Content $baseConfig -Encoding UTF8 -Raw
  $content = $content -replace 'initial_x: [-\d.]+', "initial_x: $($r.x)"
  $content = $content -replace 'initial_y: [-\d.]+', "initial_y: $($r.y)"
  $content = $content -replace 'home_x: [-\d.]+', "home_x: $($r.x)"
  $content = $content -replace 'home_y: [-\d.]+', "home_y: $($r.y)"
  $content = $content -replace '(?s)(initial:\s*\n\s*x: )[-\d.]+', "`${1}$($r.x)"
  $content = $content -replace '(?s)(initial:\s*\n\s*x: [-\d.]+\s*\n\s*y: )[-\d.]+', "`${1}$($r.y)"
  $content = $content -replace '(?s)(home:\s*\n\s*x: )[-\d.]+', "`${1}$($r.x)"
  $content = $content -replace '(?s)(home:\s*\n\s*x: [-\d.]+\s*\n\s*y: )[-\d.]+', "`${1}$($r.y)"
  $outFile = "$configDir\sim-$($r.code).yml"
  [System.IO.File]::WriteAllText($outFile, $content, (New-Object System.Text.UTF8Encoding($false)))
  "config: sim-$($r.code).yml initial=$($r.pos)"
}

$port = 8100
foreach ($r in $newRobots) {
  $code = $r.code
  $running = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match "robot-code=$code" }
  if ($running) { "skip $code (already running)"; $port++; continue }
  $p = Start-Process java -ArgumentList @(
    "-jar", $jar,
    "--config=$configDir\sim-$code.yml",
    "--server.port=$port",
    "--robot.robot-code=$code",
    "--robot.serial-no=SN_$code",
    "--robot.robot-name=$code"
  ) -RedirectStandardOutput "$logDir\mock-$code.log" -RedirectStandardError "$logDir\mock-$code-err.log" -PassThru
  "started $code at $($r.pos) port=$port PID=$($p.Id)"
  $port++
}
