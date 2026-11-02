param(
  [string]$RobotCode = "RB_20261027_00015",
  [string]$GroupNo = "RG_20260407_00001",
  [string]$BaseUrl = "http://127.0.0.1:18083"
)

# PS 5.1 的 Invoke-RestMethod 会把无 charset 的 UTF-8 响应误解码为 Latin-1（中文名变乱码），
# 统一走原始字节流按 UTF-8 解码
function Get-RmsJson([string]$Uri) {
  $resp = Invoke-WebRequest -Uri $Uri -UseBasicParsing
  [Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray()) | ConvertFrom-Json
}

# 1. keyword 检索定位机器人 id
$list = Get-RmsJson "$BaseUrl/api/v1/robot-management/robots?page_num=1&page_size=50&keyword=$RobotCode"
$robot = $list.data.list | Where-Object { $_.robot_code -eq $RobotCode } | Select-Object -First 1
if (-not $robot) { Write-Error "robot not found: $RobotCode"; exit 1 }

# 2. 拉详情（RMS PUT 为全量更新：robot_name/status 必填，其余字段原值回填避免被清空）
$detail = (Get-RmsJson "$BaseUrl/api/v1/robot-management/robots/$($robot.id)").data

# 3. PUT 更新（snake_case 契约，OpenAPI 标的 camelCase 不生效），仅覆写 group_no
# 注意：PS 5.1 Invoke-RestMethod 的 PUT byte[] body 会被 RMS 拒绝（字段全 blank），必须走 curl.exe
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
$tmp = Join-Path $env:TEMP "set-robot-group-$PID.json"
[IO.File]::WriteAllText($tmp, $json, (New-Object Text.UTF8Encoding($false)))
$raw = & "$env:SystemRoot\System32\curl.exe" -s -X PUT `
  "$BaseUrl/api/v1/robot-management/robots/$($robot.id)" `
  -H "Content-Type: application/json" --data-binary "@$tmp"
Remove-Item $tmp -ErrorAction SilentlyContinue
$resp = $raw | ConvertFrom-Json
if ($resp.status -ne 0) { Write-Error "PUT failed: status=$($resp.status) msg=$($resp.msg)"; exit 1 }

# 4. 回读验证
$check = (Get-RmsJson "$BaseUrl/api/v1/robot-management/robots/$($robot.id)").data
"robot_code=$($check.robot_code)  group_no=$($check.group_no)  online_status=$($check.online_status)"
