# 循环质检压测：不停发质检任务（4 个作业点随机），做完自动去停靠点等待
# 用法：powershell -File loop-inspection-test.ps1 -Rounds 10 -IntervalSec 15
param(
  [int]$Rounds = 10,
  [int]$IntervalSec = 15
)
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$base = "http://127.0.0.1:8091"

# 4 个作业点（v3.8.2 workstation）
$workPoints = @("W1", "W2", "W3", "W4")
# 停靠等待点（非作业点）
$standbyPoints = @("H1", "H2", "T1", "P8", "P9", "P3", "P5")

$round = 0
while ($round -lt $Rounds) {
  $round++
  $work = $workPoints | Get-Random
  $standby = $standbyPoints | Get-Random
  $taskId = "loop-qc-{0:d3}" -f $round
  $body = @{
    taskflow_id = "tf-loop-$round"
    task_id = $taskId
    task_template_type = "quality_inspection"
    nav_end_point = $work
    parameters = @{
      task_template_code = "QC_LOOP"
      end_point = $standby
    }
  } | ConvertTo-Json
  try {
    $r = Invoke-RestMethod -Uri "$base/api/v1/scheduler/dispatch_task" -Method Post -ContentType "application/json" -Body $body -TimeoutSec 10
    $ts = Get-Date -Format "HH:mm:ss"
    Write-Host "[$ts] #$round -> $work (停靠 $standby) : $($r.data.dispatch_status)"
  } catch {
    Write-Host "[$(Get-Date -Format 'HH:mm:ss')] #$round -> $work : FAILED $($_.Exception.Message)"
  }
  Start-Sleep -Seconds $IntervalSec
}

# 等待最后的任务完成
Write-Host "`n等待收尾..."; Start-Sleep -Seconds 90
Write-Host "`n===== 压测结果汇总 ====="
$h = Invoke-RestMethod -Uri "$base/api/v1/observability/tasks?page_size=$($Rounds + 5)" -TimeoutSec 8
$loopTasks = $h.data.list | Where-Object { $_.business_task_id -like "loop-qc-*" }
$completed = ($loopTasks | Where-Object { $_.status -eq "completed" }).Count
$failed = ($loopTasks | Where-Object { $_.status -eq "failed" }).Count
$other = $loopTasks.Count - $completed - $failed
Write-Host "总: $($loopTasks.Count) | 完成: $completed | 失败: $failed | 其他: $other"
$loopTasks | Sort-Object business_task_id | ForEach-Object {
  $msg = if ($_.message) { $_.message.Substring(0, [Math]::Min(65, $_.message.Length)) } else { "" }
  $insp = if ($_.inspection_result) { " [质检✓]" } else { "" }
  Write-Host "  $($_.business_task_id) $($_.robot_code.Substring(12)) $($_.status)$insp $msg"
}
