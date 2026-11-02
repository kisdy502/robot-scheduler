Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match "qcrobotmock" } | ForEach-Object { taskkill /PID $_.ProcessId /F | Out-Null }
Start-Sleep -Seconds 2
$jar = "D:\workspace\qc-robot\qcrobotmock_new\target\qcrobotmock-0.0.1-SNAPSHOT.jar"
$config = "D:\workspace\qc-robot\qcrobotmock_new\config\qc-robot-mock.yml"
$p = Start-Process java -ArgumentList @(
  "-jar", $jar,
  "--config=$config",
  "--server.port=8095",
  "--robot.robot-code=RB_SIM_002",
  "--robot.serial-no=SN_RB_SIM_002",
  "--robot.robot-name=RB_SIM_002",
  "--robot-management.base-url=http://127.0.0.1:18083",
  "--zenoh.endpoints=tcp/127.0.0.1:7447",
  "--zenoh.namespace=test-dispatch"
) -RedirectStandardOutput "D:\robotscheduler\robot-scheduler\logs\mock-002.log" -RedirectStandardError "D:\robotscheduler\robot-scheduler\logs\mock-002-err.log" -PassThru
"PID=$($p.Id)"
