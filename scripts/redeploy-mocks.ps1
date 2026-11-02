# 重新部署 5 台 mock 到 v3.8.2 新地图的非作业点（H1/H2/T1/P8/P9）
$jar = "D:\workspace\qc-robot\qcrobotmock_new\target\qcrobotmock-0.0.1-SNAPSHOT.jar"
$config = "D:\robotscheduler\robot-scheduler\scripts\sim-robot-config.yml"

# v3.8.2 停靠点（非作业点）
$robots = @(
  @{ code = "RB_SIM_002"; pos = "H1"; x = 14.13;  y = -6.39 },
  @{ code = "RB_SIM_003"; pos = "H2"; x = 4.00;   y = 10.10 },
  @{ code = "RB_SIM_004"; pos = "T1"; x = -9.79;  y = 1.53 },
  @{ code = "RB_SIM_005"; pos = "P8"; x = -0.10;  y = 10.10 },
  @{ code = "RB_SIM_006"; pos = "P9"; x = 3.90;   y = -0.60 }
)

$configDir = "D:\robotscheduler\robot-scheduler\scripts\sim-configs"
New-Item -ItemType Directory -Path $configDir -Force | Out-Null

# 生成专属配置（每台不同初始位置）
foreach ($r in $robots) {
  $content = Get-Content $config -Encoding UTF8 -Raw
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

# 杀掉旧 mock
Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match "qcrobotmock" } | ForEach-Object { taskkill /PID $_.ProcessId /F | Out-Null }
Start-Sleep -Seconds 3
"mocks cleared"

# 重启（每台独立端口+日志）
$logDir = "D:\robotscheduler\robot-scheduler\logs"
$port = 8095
foreach ($r in $robots) {
  $code = $r.code
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
