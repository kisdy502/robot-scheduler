<#
本次测试的可读结果入口：默认运行异常处理示范，也可用 -Test 指定其他测试类。
#>
param(
    [string]$Test = 'ApiExceptionHandlerTest',
    [string]$Module = 'robot-scheduler-api',
    [string]$Maven = 'mvn',
    [switch]$Offline,
    [string]$SummaryScript = 'D:\workspace\qc-robot\team-ai-rules\skills\testing\scripts\summarize-junit-reports.py'
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$runId = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 6)
$reportRoot = Join-Path $repoRoot "target/test-review/$runId"
New-Item -ItemType Directory -Path $reportRoot -Force | Out-Null
$started = [DateTime]::UtcNow
$mavenArgs = @('-pl', $Module, '-am', "-Dtest=$Test", '-Dsurefire.failIfNoSpecifiedTests=false', 'test')
if ($Offline) { $mavenArgs = @('-o') + $mavenArgs }

Push-Location $repoRoot
try {
    # 保留 Maven 的退出码；即便断言失败，也继续生成失败报告。
    & $Maven @mavenArgs *> (Join-Path $reportRoot 'maven.log')
    $testExit = $LASTEXITCODE
    $rows = @()
    foreach ($moduleDir in Get-ChildItem -LiteralPath $repoRoot -Directory -Filter 'robot-scheduler-*') {
        $reports = Join-Path $moduleDir.FullName 'target/surefire-reports'
        if (!(Test-Path -LiteralPath $reports)) { continue }
        foreach ($file in Get-ChildItem -LiteralPath $reports -Filter 'TEST-*.xml') {
            # 只收集本轮更新的 XML，防止把历史结果误报为这次已执行。
            if ($file.LastWriteTimeUtc -lt $started) { continue }
            $copyDir = Join-Path $reportRoot ($moduleDir.Name + '/target/surefire-reports')
            New-Item -ItemType Directory -Path $copyDir -Force | Out-Null
            Copy-Item -LiteralPath $file.FullName -Destination $copyDir
            [xml]$xml = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8
            foreach ($case in $xml.testsuite.testcase) {
                $name = [string]$case.name
                $source = Join-Path $moduleDir.FullName ('src/test/java/' + ([string]$case.classname -replace '\.', '/') + '.java')
                if (Test-Path -LiteralPath $source) {
                    $java = Get-Content -LiteralPath $source -Raw -Encoding UTF8
                    $pattern = '@DisplayName\("([^"\r\n]+)"\)\s*(?:@Test\s*)?void\s+' + [regex]::Escape($name) + '\s*\('
                    $match = [regex]::Match($java, $pattern)
                    if ($match.Success) { $name = $match.Groups[1].Value }
                }
                $state = '通过'; $reason = ''
                if ($case.SelectSingleNode('skipped')) { $state = '跳过'; $reason = [string]$case.skipped.message }
                foreach ($kind in @('failure', 'error')) {
                    $node = $case.SelectSingleNode($kind)
                    if ($node) { $state = '失败'; $reason = $node.GetAttribute('message') }
                }
                $rows += [pscustomobject]@{ '结果' = $state; '场景与预期' = $name; '测试类' = [string]$case.classname; '方法' = [string]$case.name; '耗时秒' = [string]$case.time; '失败或跳过原因' = $reason }
            }
        }
    }
    if ($rows.Count -eq 0) {
        Write-Host "本轮没有测试结果，不能判定通过。请查看 $reportRoot/maven.log"
        exit 1
    }
    $rows | Format-Table '结果', '场景与预期', '失败或跳过原因' -Wrap -AutoSize | Out-Host
    $rows | Export-Csv -LiteralPath (Join-Path $reportRoot 'cases.csv') -NoTypeInformation -Encoding UTF8
    $failed = @($rows | Where-Object { $_.结果 -eq '失败' }).Count
    $skipped = @($rows | Where-Object { $_.结果 -eq '跳过' }).Count
    $heading = "共 $($rows.Count) 项：通过 $($rows.Count - $failed - $skipped)，失败 $failed，跳过 $skipped；Maven 退出码 $testExit"
    $rows | ConvertTo-Html -Title '单元测试结果' -PreContent "<h1>单元测试结果</h1><p>$heading</p><p>通过表示该场景的断言已满足；不代表系统全部行为已验证。未配置中文名称的测试显示原方法名。</p>" |
        Set-Content -LiteralPath (Join-Path $reportRoot 'index.html') -Encoding UTF8
    Write-Host $heading
    Write-Host "明细报告：$reportRoot/index.html"
    # 复用团队工具生成模块汇总；不把汇总失败伪装成测试失败或成功。
    if (Test-Path -LiteralPath $SummaryScript) {
        & py $SummaryScript --root $reportRoot --out (Join-Path $reportRoot 'summary.csv') --json (Join-Path $reportRoot 'summary.json')
        if ($LASTEXITCODE -ne 0) { throw '测试汇总工具执行失败，测试明细和 Maven 日志已保留。' }
    } else {
        Write-Warning '未找到团队汇总工具；已生成本轮 HTML 和 CSV 明细，可通过 -SummaryScript 指定工具路径。'
    }
    if ($testExit -ne 0 -or $failed -gt 0) { exit 1 }
} finally {
    Pop-Location
}
