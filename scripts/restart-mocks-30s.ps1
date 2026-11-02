# 重启全部 8 台 mock，质检动作耗时 30 秒（activity_complete_delay_ms=30000）
$jar = "D:\workspace\qc-robot\qcrobotmock_new\target\qcrobotmock-0.0.1-SNAPSHOT.jar"
$configDir = "D:\robotscheduler\robot-scheduler\scripts\sim-configs"
$logDir = "D:\robotscheduler\robot-scheduler\logs"

$robots = @(
  @{ code = "RB_SIM_002"; pos = "H1"; x = 14.13;  y = -6.39 },
  @{ code = "RB_SIM_003"; pos = "H2"; x = 4.00;   y = 10.10 },
  @{ code = "RB_SIM_004"; pos = "T1"; x = -9.79;  y = 1.53 },
  @{ code = "RB_SIM_005"; pos = "P8"; x = -0.10;  y = 10.10 },
  @{ code = "RB_SIM_006"; pos = "P9"; x = 3.90;   y = -0.60 },
  @{ code = "RB_SIM_007"; pos = "P5"; x = 7.80;   y = -12.60 },
  @{ code = "RB_SIM_008"; pos = "P3"; x = 10.40;  y = -0.70 }
)

$baseConfig = "D:\robotscheduler\robot-scheduler\scripts\sim-robot-config.yml"
foreach ($r in $robots) {
  $content = Get-Content $baseConfig -Encoding UTF8 -Raw
  $content = $content -replace 'initial_x: [-\d.]+', "initial_x: $($r.x)"
  $content = $content -replace 'initial_y: [-\d.]+', "initial_y: $($r.y)"
  $content = $content -replace 'home_x: [-\d.]+', "home_x: $($r.x)"
  $content = $content -replace 'home_y: [-\d.]+', "home_y: $($r.y)"
  $content = $content -replace '(?s)(initial:\s*\n\s*x: )[-\d.]+', "`${1}$($r.x)"
  $content = $content -replace '(?s)(initial:\s*\n\s*x: [-\d.]+\s*\n\s*y: )[-\d.]+', "`${1}$($r.y)"
  $content = $content -replace '(?s)(home:\s*\n\s*x: )[-\d.]+', "`${1}$($r.x)"
  $content = $content -replace '(?s)(home:\s*\n\s*x: [-\d.]+\s*\n\s*y: )[-\d.]+', "`${1}$($r.y)"
  [System.IO.File]::WriteAllText("$configDir\sim-$($r.code).yml", $content, (New-Object System.Text.UTF8Encoding($false)))
  "config: sim-$($r.code).yml at $($r.pos)"
}

$port = 8095
foreach ($r in $robots) {
  $code = $r.code
  $p = Start-Process java -ArgumentList @(
    "-jar", $jar,
    "--config=$configDir\sim-$code.yml",
    "--server.port=$port",
    "--robot.robot-code=$code",
    "--robot.serial-no=SN_$code",
    "--robot.robot-name=$code",
    "--simulation.activity-complete-delay-ms=30000"
  ) -RedirectStandardOutput "$logDir\mock-$code.log" -RedirectStandardError "$logDir\mock-$code-err.log" -PassThru
  "started $code at $($r.pos) port=$port PID=$($p.Id)"
  $port++
}
