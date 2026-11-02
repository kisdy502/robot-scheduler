param([string[]]$Codes)
$BaseUrl = "http://127.0.0.1:18083"
$Group = "RG_20260407_00001"

function Get-RmsJson([string]$Uri) {
  $resp = Invoke-WebRequest -Uri $Uri -UseBasicParsing
  [Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray()) | ConvertFrom-Json
}

foreach ($code in $Codes) {
  if (-not $code) { continue }
  $list = Get-RmsJson "$BaseUrl/api/v1/robot-management/robots?page_num=1&page_size=50&keyword=$code"
  $robot = $list.data.list | Where-Object { $_.robot_code -eq $code } | Select-Object -First 1
  if (-not $robot) { "SKIP $code (not found)"; continue }
  if ($robot.group_no -eq $Group) { "OK   $code already in $Group"; continue }
  $detail = (Get-RmsJson "$BaseUrl/api/v1/robot-management/robots/$($robot.id)").data
  $json = @{
    robot_name       = $detail.robot_name
    robot_sn         = $detail.robot_sn
    ip               = $detail.ip
    model            = $detail.model
    firmware_version = $detail.firmware_version
    robot_type       = $detail.robot_type
    robot_type_no    = $detail.robot_type_no
    group_no         = $Group
    status           = $detail.status
  } | ConvertTo-Json
  $tmp = Join-Path $env:TEMP "group-$code.json"
  [IO.File]::WriteAllText($tmp, $json, (New-Object Text.UTF8Encoding($false)))
  $raw = & "$env:SystemRoot\System32\curl.exe" -s -X PUT `
    "$BaseUrl/api/v1/robot-management/robots/$($robot.id)" `
    -H "Content-Type: application/json" --data-binary "@$tmp"
  Remove-Item $tmp -ErrorAction SilentlyContinue
  $resp = $raw | ConvertFrom-Json
  if ($resp.status -ne 0) { "FAIL $code -> $Group : status=$($resp.status) msg=$($resp.msg)" }
  else { "OK   $code -> $Group (id=$($robot.id))" }
}
