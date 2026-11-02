# 启动 5 台 map1025L 仿真机器人（端口 8095-8099，初始分布新图各停靠点，不占充电位/作业点）
$jar = "D:\workspace\qc-robot\qcrobotmock_new\target\qcrobotmock-0.0.1-SNAPSHOT.jar"
$config = "D:\robotscheduler\robot-scheduler\scripts\sim-robot-config.yml"

# 初始位 = 归巢位（parking 点，分散四角+东侧）；RB_SIM_006 低电量保留返充测试
$robots = @(
  @{ code = "RB_SIM_002"; x = 1.50;   y = 9.27;   yaw = 0.0;  battery = 92; home = "H1" },
  @{ code = "RB_SIM_003"; x = -24.42; y = 11.02;  yaw = 0.0;  battery = 92; home = "H2" },
  @{ code = "RB_SIM_004"; x = -39.00; y = 17.43;  yaw = 0.0;  battery = 92; home = "H5" },
  @{ code = "RB_SIM_005"; x = 4.18;   y = -14.61; yaw = 0.0;  battery = 92; home = "H4" },
  @{ code = "RB_SIM_006"; x = -19.11; y = -16.00; yaw = 0.0;  battery = 10; home = "H3" }
)

$logDir = "D:\robotscheduler\robot-scheduler\logs"

# 互斥锁：防止并发双执行（重复进程对同 code 冲突互杀）
# 内核原子：CreateNew 独占打开，第二个实例 Open 即失败退出
$lockPath = Join-Path $env:TEMP "mock-start.lock"
$staleLock = Test-Path $lockPath -OlderThan (Get-Date).AddMinutes(-2)
if ($staleLock) { Remove-Item $lockPath -Force -ErrorAction SilentlyContinue }
try {
  $lockStream = [System.IO.File]::Open($lockPath, [System.IO.FileMode]::CreateNew,
      [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
} catch {
  "another start in progress, exit"
  exit 0
}
try {

$port = 8095
$running = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match "qcrobotmock" }
foreach ($r in $robots) {
  $code = $r.code
  if ($running | Where-Object { $_.CommandLine -match "robot-code=$code" }) {
    "skip $code (already running)"
    $port++
    continue
  }
  $p = Start-Process java -ArgumentList @(
    "-jar", $jar,
    "--config=$config",
    "--server.port=$port",
    "--robot.robot-code=$code",
    "--robot.serial-no=SN_$code",
    "--robot.robot-name=$code",
    "--simulation.initial-battery-percent=$($r.battery)",
    "--motion.initial_x=$($r.x)",
    "--motion.initial_y=$($r.y)",
    "--motion.initial_yaw=$($r.yaw)",
    "--motion.home_x=$($r.x)",
    "--motion.home_y=$($r.y)",
    "--motion.home_yaw=$($r.yaw)"
  ) -RedirectStandardOutput "$logDir\mock-$code.log" -RedirectStandardError "$logDir\mock-$code-err.log" -PassThru
  "started $code port=$port PID=$($p.Id) at $($r.home) ($($r.x), $($r.y)) battery=$($r.battery)%"
  $port++
  Start-Sleep -Milliseconds 500
}

} finally {
  Start-Sleep -Seconds 3
  $lockStream.Close()
  Remove-Item $lockPath -Force -ErrorAction SilentlyContinue
}
