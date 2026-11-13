# rename-jytree: 两台 JYTREE003 重名 → 分配未使用的修仙角色名
$BaseUrl = "http://127.0.0.1:18083"

function Get-RmsJson([string]$Uri) {
  $resp = Invoke-WebRequest -Uri $Uri -UseBasicParsing
  [Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray()) | ConvertFrom-Json
}

# 凡人修仙传角色（不在旧 20 名池内的新鲜名字）
$pool = @("石穿空", "青易居士", "蟹道人", "玄骨", "血河东", "妖凤", "灵鹫", "掌天瓶")

$list = Get-RmsJson "$BaseUrl/api/v1/robot-management/robots?page_num=1&page_size=100"
$used = @($list.data.list | ForEach-Object { $_.robot_name })
$targets = @($list.data.list | Where-Object { $_.robot_name -eq "JYTREE003" })
"targets: $($targets.Count)"

$idx = 0
foreach ($robot in $targets) {
  $code = $robot.robot_code
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
  $tmp = Join-Path $env:TEMP "rename-jy-$code.json"
  [IO.File]::WriteAllText($tmp, $json, (New-Object Text.UTF8Encoding($false)))
  $raw = & "$env:SystemRoot\System32\curl.exe" -s -X PUT `
    "$BaseUrl/api/v1/robot-management/robots/$($robot.id)" `
    -H "Content-Type: application/json" --data-binary "@$tmp"
  Remove-Item $tmp -ErrorAction SilentlyContinue
  $resp = $raw | ConvertFrom-Json
  if ($resp.status -ne 0) { "FAIL $code -> $name : $($resp.msg)" }
  else { "OK   $code -> $name" }
}
