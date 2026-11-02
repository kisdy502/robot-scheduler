# rename-new-mocks: give code-named robots (RB_*) proper cultivation-novel display names
$BaseUrl = "http://127.0.0.1:18083"

function Get-RmsJson([string]$Uri) {
  $resp = Invoke-WebRequest -Uri $Uri -UseBasicParsing
  [Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray()) | ConvertFrom-Json
}

# fresh name pool (avoid ones already in use, checked below)
$pool = @(
  "南宫婉", "紫灵仙子", "董萱儿", "陈巧倩", "墨彩环", "元瑶", "银月", "曲魂",
  "张铁", "大衍神君", "青元子", "雷万鹤", "文思月", "冰凤", "金童", "木灵子",
  "寒骊上人", "云翳老祖", "贝叶神尼", "古盟主"
)

$list = Get-RmsJson "$BaseUrl/api/v1/robot-management/robots?page_num=1&page_size=50"
$used = @($list.data.list | ForEach-Object { $_.robot_name })
$targets = @($list.data.list | Where-Object { $_.robot_name -match "^RB_" })
"code-named robots: $($targets.Count)"

$idx = 0
foreach ($robot in $targets) {
  $code = $robot.robot_code
  # find next unused name
  while ($idx -lt $pool.Count -and $used -contains $pool[$idx]) { $idx++ }
  if ($idx -ge $pool.Count) { "OUT OF NAMES at $code"; break }
  $name = $pool[$idx]; $idx++
  $detail = (Get-RmsJson "$BaseUrl/api/v1/robot-management/robots/$($robot.id)").data
  $json = @{
    robot_name       = $name
    robot_sn         = $detail.robot_sn
    ip               = $detail.ip
    model            = $detail.model
    firmware_version = $detail.firmware_version
    robot_type       = $detail.robot_type
    robot_type_no    = $detail.robot_type_no
    group_no         = $detail.group_no
    status           = $detail.status
  } | ConvertTo-Json
  $tmp = Join-Path $env:TEMP "rename2-$code.json"
  [IO.File]::WriteAllText($tmp, $json, (New-Object Text.UTF8Encoding($false)))
  $raw = & "$env:SystemRoot\System32\curl.exe" -s -X PUT `
    "$BaseUrl/api/v1/robot-management/robots/$($robot.id)" `
    -H "Content-Type: application/json" --data-binary "@$tmp"
  Remove-Item $tmp -ErrorAction SilentlyContinue
  $resp = $raw | ConvertFrom-Json
  if ($resp.status -ne 0) { "FAIL $code -> $name : $($resp.msg)" }
  else { "OK   $code -> $name" }
}
