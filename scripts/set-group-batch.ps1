# 批量设置机器人分组（RMS zenoh 注册不写 group_no，需补登记）
param(
  [string]$GroupNo = "RG_20260407_00001",
  [string]$BaseUrl = "http://127.0.0.1:18083",
  [string]$Keyword = "RB_20261028_"   # 本批新机器人编码前缀
)

function Get-RmsJson([string]$Uri) {
  $resp = Invoke-WebRequest -Uri $Uri -UseBasicParsing
  [Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray()) | ConvertFrom-Json
}

$list = Get-RmsJson "$BaseUrl/api/v1/robot-management/robots?page_num=1&page_size=100&keyword=$Keyword"
foreach ($robot in $list.data.list) {
  $code = $robot.robot_code
  if ($robot.group_no -eq $GroupNo) { "skip $code (already in group)"; continue }
  $detail = (Get-RmsJson "$BaseUrl/api/v1/robot-management/robots/$($robot.id)").data
  $json = @{
    robot_name       = $detail.robot_name
    robot_sn         = $detail.robot_sn
    ip               = $detail.ip
    model            = $detail.model
    firmware_version = $detail.firmware_version
    robot_type       = $detail.robot_type
    robot_type_no    = $detail.robot_type_no
    group_no         = $GroupNo
    status           = $detail.status
  } | ConvertTo-Json
  $tmp = "D:\robotscheduler\robot-scheduler\logs\set-group-$code.json"
  [IO.File]::WriteAllText($tmp, $json, (New-Object Text.UTF8Encoding($false)))
  $raw = & "$env:SystemRoot\System32\curl.exe" -s -X PUT `
    "$BaseUrl/api/v1/robot-management/robots/$($robot.id)" `
    -H "Content-Type: application/json" --data-binary "@$tmp"
  Remove-Item $tmp -ErrorAction SilentlyContinue
  $resp = $raw | ConvertFrom-Json
  if ($resp.status -ne 0) { "FAIL $code : $($resp.msg)" } else { "OK   $code -> $GroupNo" }
}
