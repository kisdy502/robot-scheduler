# 启动 20 台 map1027S 仿真机器人（RB_SIM_002~021，随机出生在通行/停靠点位，不占充电位/作业点/空白区）
# 端口 8095-8114；-Xmx256m 限内存（20 JVM 并存）；电量混布（含 3 台低电触发充电链路）
$jar = "D:\workspace\qc-robot\qcrobotmock_new\target\qcrobotmock-0.0.1-SNAPSHOT.jar"
$config = "D:\robotscheduler\robot-scheduler\scripts\sim-robot-config.yml"
$logDir = "D:\robotscheduler\robot-scheduler\logs"
$base = "http://127.0.0.1:8091"

# 凡人修仙传群像（20 位在编 + 备用池：扩编超 20 台后自动启用）
$names = @("韩立","墨大夫","合欢老魔","玄骨魔祖","极阴祖师","天星双圣","历飞雨",
           "南宫婉","银月","紫灵仙子","曲魂","元瑶","董萱儿","陈巧倩",
           "墨彩环","张铁","雷万鹤","李化元","钟卫娘","文思月",
           # ---- 备用池（下次扩编用）----
           "王婵","青易居士","天机真人","火龙童子","金光上人","南陇候","天澜圣女","向之礼","魏无涯")
$mockCount = 20

# 从调度拉当前地图点位，出生点池 = 通行点 + 停靠点（避开充电位/作业点/电梯）
$map = Invoke-RestMethod -Uri "$base/api/v1/maps/current/detail" -TimeoutSec 10
$waypoints = @()
foreach ($floor in $map.data.floors) { $waypoints += $floor.waypoints }
$spawnPool = @($waypoints | Where-Object { $_.type -in @("path", "parking") })
"spawn pool: $($spawnPool.Count) 点（通行+停靠）"
if ($spawnPool.Count -lt 20) { Write-Error "出生点池不足 20"; exit 1 }
# 洗牌取前 20（不重复出生点）
$shuffled = $spawnPool | Get-Random -Count $spawnPool.Count

# 电量：17 台 40~95 随机 + 3 台低电（15~19，立即触发充电链路）
$batteries = @()
for ($i = 0; $i -lt 17; $i++) { $batteries += (Get-Random -Minimum 40 -Maximum 95) }
$batteries += 15..19 | Get-Random -Count 3

# 互斥锁：防止并发双执行（重复进程对同 code 冲突互杀）
$lockPath = "D:\robotscheduler\robot-scheduler\logs\mock-start.lock"
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

# 每台独立配置（中文名写 UTF-8 文件，规避命令行中文编码坑）
$configDir = "D:\robotscheduler\robot-scheduler\scripts\sim-configs"
New-Item -ItemType Directory -Path $configDir -Force | Out-Null
$baseContent = Get-Content $config -Encoding UTF8 -Raw

$running = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match "qcrobotmock" }
$port = 8095
for ($i = 0; $i -lt 20; $i++) {
  $code = "RB_SIM_{0:D3}" -f ($i + 2)
  $name = $names[$i]
  $spawn = $shuffled[$i]
  $battery = $batteries[$i]
  if ($running | Where-Object { $_.CommandLine -match "robot-code=$code" }) {
    "skip $code ($name) (already running)"
    $port++
    continue
  }
  $content = $baseContent -replace 'robot_name: RB_SIM_TEMPLATE', "robot_name: $name"
  $outFile = "$configDir\sim-$code.yml"
  [System.IO.File]::WriteAllText($outFile, $content, (New-Object System.Text.UTF8Encoding($false)))
  $p = Start-Process java -ArgumentList @(
    "-Xmx256m", "-Xms64m",
    "-jar", $jar,
    "--config=$outFile",
    "--server.port=$port",
    "--robot.robot-code=$code",
    "--robot.serial-no=SN_$code",
    "--simulation.initial-battery-percent=$battery",
    "--motion.initial_x=$($spawn.x)",
    "--motion.initial_y=$($spawn.y)",
    "--motion.initial_yaw=0.0",
    "--motion.home_x=$($spawn.x)",
    "--motion.home_y=$($spawn.y)",
    "--motion.home_yaw=0.0"
  ) -RedirectStandardOutput "$logDir\mock-$code.log" -RedirectStandardError "$logDir\mock-$code-err.log" -PassThru
  "started $code ($name) port=$port PID=$($p.Id) spawn=$($spawn.id) ($($spawn.x),$($spawn.y)) battery=$battery%"
  $port++
  Start-Sleep -Milliseconds 400
}

} finally {
  Start-Sleep -Seconds 3
  $lockStream.Close()
  Remove-Item $lockPath -Force -ErrorAction SilentlyContinue
}
