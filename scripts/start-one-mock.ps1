$jar = "D:\workspace\qc-robot\qcrobotmock_new\target\qcrobotmock-0.0.1-SNAPSHOT.jar"
$config = "D:\workspace\qc-robot\qcrobotmock_new\config\qc-robot-mock.yml"
Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match "qcrobotmock" } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 2
$p = Start-Process java -ArgumentList @(
  "-jar", $jar,
  "--config=$config",
  "--robot.robot-code=RB_SIM_002",
  "--robot.serial-no=SN_RB_SIM_002",
  "--robot.robot-name=RB_SIM_002",
  "--robot-management.base-url=http://127.0.0.1:18083",
  "--zenoh.endpoints=127.0.0.1:7447",
  "--motion.initial-x=-8.71",
  "--motion.initial-y=11.42",
  "--motion.initial-yaw=0",
  "--simulation.initial-battery-percent=92"
) -RedirectStandardOutput "D:\robotscheduler\robot-scheduler\logs\mock-002.log" -RedirectStandardError "D:\robotscheduler\robot-scheduler\logs\mock-002-err.log" -PassThru
"PID=$($p.Id)"
