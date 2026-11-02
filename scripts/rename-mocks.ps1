# mock 机器人改名（韩立/墨大夫/合欢老魔/玄骨魔祖/极阴祖师/天星双圣/历飞雨）
# RMS robot_name 更新：keyword 定位 → 拉详情 → snake_case 全量 PUT（仅覆写 robot_name）
$BaseUrl = "http://127.0.0.1:18083"

function Get-RmsJson([string]$Uri) {
  $resp = Invoke-WebRequest -Uri $Uri -UseBasicParsing
  [Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray()) | ConvertFrom-Json
}

$names = [ordered]@{
  "RB_20261024_00007" = "韩立"
  "RB_20261024_00011" = "墨大夫"
  "RB_20261024_00010" = "合欢老魔"
  "RB_20261024_00008" = "玄骨魔祖"
  "RB_20261024_00009" = "极阴祖师"
  "RB_20261025_00014" = "天星双圣"
  "RB_20261025_00013" = "历飞雨"
}

foreach ($code in $names.Keys) {
  $list = Get-RmsJson "$BaseUrl/api/v1/robot-management/robots?page_num=1&page_size=50&keyword=$code"
  $robot = $list.data.list | Where-Object { $_.robot_code -eq $code } | Select-Object -First 1
  if (-not $robot) { "SKIP $code (not found)"; continue }
  $detail = (Get-RmsJson "$BaseUrl/api/v1/robot-management/robots/$($robot.id)").data
  $json = @{
    robot_name       = $names[$code]
    robot_sn         = $detail.robot_sn
    ip               = $detail.ip
    model            = $detail.model
    firmware_version = $detail.firmware_version
    robot_type       = $detail.robot_type
    robot_type_no    = $detail.robot_type_no
    group_no         = $detail.group_no
    status           = $detail.status
  } | ConvertTo-Json
  $tmp = Join-Path $env:TEMP "rename-$code.json"
  if (-not $env:TEMP) { $tmp = "D:\robotscheduler\robot-scheduler\logs\rename-$code.json" }
  [IO.File]::WriteAllText($tmp, $json, (New-Object Text.UTF8Encoding($false)))
  $raw = & "$env:SystemRoot\System32\curl.exe" -s -X PUT `
    "$BaseUrl/api/v1/robot-management/robots/$($robot.id)" `
    -H "Content-Type: application/json" --data-binary "@$tmp"
  Remove-Item $tmp -ErrorAction SilentlyContinue
  $resp = $raw | ConvertFrom-Json
  if ($resp.status -ne 0) { "FAIL $code -> $($names[$code]): status=$($resp.status) msg=$($resp.msg)" }
  else { "OK   $code -> $($names[$code])" }
}
