[CmdletBinding()]
param(
    [string]$ProxyHost,

    [ValidateRange(1, 65535)]
    [int]$ProxyPort = 7890,

    [ValidateRange(1, 10)]
    [int]$CoverAttempts = 4,

    [ValidateRange(1, 16)]
    [int]$CoverConcurrency = 6,

    [switch]$Json
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$buildDirectory = Join-Path $env:TEMP (
    "song-catalog-sync-" + [Guid]::NewGuid().ToString("N"))
$sources = @(
    (Join-Path $projectRoot "Json.java"),
    (Join-Path $projectRoot "SongCatalog.java"),
    (Join-Path $projectRoot "ChunithmCatalog.java"),
    (Join-Path $projectRoot "SongCatalogSync.java"),
    (Join-Path $projectRoot "LxnsCoverService.java"),
    (Join-Path $projectRoot "MaimaiCoverService.java"),
    (Join-Path $projectRoot "ChunithmCoverService.java"),
    (Join-Path $projectRoot "SongCoverSync.java")
)

function Format-SyncTime {
    param([object]$Value)

    if ($null -eq $Value -or [string]::IsNullOrWhiteSpace([string]$Value)) {
        return "未记录"
    }
    try {
        return [DateTimeOffset]::Parse([string]$Value).
            ToLocalTime().ToString("yyyy-MM-dd HH:mm:ss zzz")
    } catch {
        return [string]$Value
    }
}

function Get-ProviderName {
    param([string]$Provider)

    switch ($Provider) {
        "Diving-Fish" { return "水鱼（Diving-Fish）" }
        "LXNS" { return "落雪（LXNS）" }
        default { return $Provider }
    }
}

function Write-GameSyncResult {
    param(
        [string]$GameName,
        [object]$GameReport
    )

    $result = $GameReport.result
    $succeeded = [bool]$result.success
    $stateText = if ($succeeded) { "同步成功" } else { "同步失败" }
    $stateColor = if ($succeeded) { "Green" } else { "Red" }

    Write-Host ""
    Write-Host ("[{0}] {1}" -f $GameName, $stateText) -ForegroundColor $stateColor
    Write-Host ("  曲目数量：{0}" -f $result.status.songCount)
    Write-Host ("  本次开始：{0}" -f (Format-SyncTime $result.status.lastAttemptAt))
    Write-Host ("  最近成功：{0}" -f (Format-SyncTime $result.status.lastSuccessAt))
    if (-not $succeeded -and $result.status.error) {
        Write-Host ("  失败原因：{0}" -f $result.status.error) -ForegroundColor Red
        Write-Host "  已保留上一份可用曲库，没有发布不完整数据。" -ForegroundColor Yellow
    }

    Write-Host "  API 状态："
    foreach ($source in @($GameReport.publicApiSources)) {
        $published = $source.publication -eq "validated-and-published"
        $apiState = if ($published) { "已验证并发布" } else { "未发布" }
        $apiColor = if ($published) { "Green" } else { "Red" }
        Write-Host ("    [{0}] {1}" -f $apiState, (Get-ProviderName $source.provider)) `
            -ForegroundColor $apiColor
        Write-Host ("             {0}" -f $source.url) -ForegroundColor DarkGray
    }
}

function Write-CoverSyncResult {
    param(
        [string]$GameName,
        [object]$CoverReport
    )

    $succeeded = [bool]$CoverReport.success
    $stateText = if ($succeeded) { " cover 已补全" } else { " cover 未补全" }
    $stateColor = if ($succeeded) { "Green" } else { "Red" }

    Write-Host ""
    Write-Host ("[{0} cover ] {1}" -f $GameName, $stateText) -ForegroundColor $stateColor
    Write-Host ("  歌曲覆盖：{0} / {1}" -f `
        $CoverReport.coveredSongs, $CoverReport.songCount)
    Write-Host ("  所需 cover 文件：{0}" -f $CoverReport.expectedFiles)
    Write-Host ("  运行前已有：{0}" -f $CoverReport.availableBefore)
    Write-Host ("  本次新下载：{0}" -f $CoverReport.downloaded)
    Write-Host ("  仍缺 cover 文件：{0}" -f $CoverReport.remainingFiles)
    Write-Host ("  无法解析 cover  ID 的歌曲：{0}" -f $CoverReport.unresolvedSongs)
    if ($GameName -eq "舞萌 DX") {
        Write-Host "   cover 来源：落雪（LXNS）公共资源服务"
    } else {
        Write-Host "   cover 来源：落雪（LXNS）；已审计的历史曲目固定备用源"
    }
    if (-not $succeeded -and $CoverReport.error) {
        Write-Host ("  失败原因：{0}" -f $CoverReport.error) -ForegroundColor Red
    }
    foreach ($failure in @($CoverReport.failures)) {
        Write-Host ("    SongID {0}：{1}" -f $failure.songId, $failure.message) `
            -ForegroundColor Red
    }
    if (-not $succeeded) {
        Write-Host "  已下载成功的 cover 均已保留；再次运行只会继续补剩余项。" `
            -ForegroundColor Yellow
    }
}

function Write-SyncReport {
    param(
        [object]$Report,
        [object]$CoverReport
    )

    $separator = "=" * 72
    Write-Host ""
    Write-Host $separator -ForegroundColor DarkCyan
    Write-Host " 舞萌 DX / 中二节奏曲库与 cover 同步报告" -ForegroundColor Cyan
    Write-Host (" 完成时间：{0}" -f (Format-SyncTime $CoverReport.completedAt))
    Write-Host $separator -ForegroundColor DarkCyan

    Write-GameSyncResult "舞萌 DX" $Report.maimai
    Write-GameSyncResult "中二节奏" $Report.chunithm
    Write-CoverSyncResult "舞萌 DX" $CoverReport.maimai
    Write-CoverSyncResult "中二节奏" $CoverReport.chunithm

    Write-Host ""
    Write-Host $separator -ForegroundColor DarkCyan
    if ([bool]$Report.success -and [bool]$CoverReport.success) {
        Write-Host " 最终结果：两套曲库与全部可解析歌曲 cover 均已同步完成。" `
            -ForegroundColor Green
    } else {
        Write-Host " 最终结果：仍有曲库或 cover 未完成，请查看上方红色项目。" `
            -ForegroundColor Red
    }
    Write-Host $separator -ForegroundColor DarkCyan
    Write-Host ""
}

New-Item -ItemType Directory -Path $buildDirectory | Out-Null
$catalogOutput = @()
$coverOutput = @()
$catalogExitCode = 1
$coverExitCode = 1
try {
    & javac --release 26 -Xlint:all -encoding UTF-8 `
        -d $buildDirectory $sources
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }

    $javaOptions = @()
    if ($ProxyHost) {
        $javaOptions += "-Dhttps.proxyHost=$ProxyHost"
        $javaOptions += "-Dhttps.proxyPort=$ProxyPort"
    }
    $catalogArguments = $javaOptions + @(
        "-cp", $buildDirectory,
        "SongCatalogSync",
        (Join-Path $projectRoot "web\song-catalog"),
        (Join-Path $projectRoot "cache\maimai-catalog.json"),
        (Join-Path $projectRoot "web\chunithm-catalog")
    )
    $coverArguments = $javaOptions + @(
        "-cp", $buildDirectory,
        "SongCoverSync",
        (Join-Path $projectRoot "web\song-catalog"),
        (Join-Path $projectRoot "cache\maimai-catalog.json"),
        (Join-Path $projectRoot "cache\maimai-covers"),
        (Join-Path $projectRoot "web\chunithm-catalog"),
        (Join-Path $projectRoot "cache\chunithm-covers"),
        $CoverAttempts,
        $CoverConcurrency
    )

    Push-Location $projectRoot
    try {
        $catalogOutput = @(& java @catalogArguments)
        $catalogExitCode = $LASTEXITCODE
        $coverOutput = @(& java @coverArguments)
        $coverExitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
} finally {
    if (Test-Path -LiteralPath $buildDirectory) {
        Remove-Item -LiteralPath $buildDirectory -Recurse -Force
    }
}

$catalogJson = ($catalogOutput | ForEach-Object { [string]$_ }) `
    -join [Environment]::NewLine
$coverJson = ($coverOutput | ForEach-Object { [string]$_ }) `
    -join [Environment]::NewLine
if ([string]::IsNullOrWhiteSpace($catalogJson)) {
    Write-Host "曲库同步程序没有返回状态数据。" -ForegroundColor Red
    exit $(if ($catalogExitCode -ne 0) { $catalogExitCode } else { 2 })
}
if ([string]::IsNullOrWhiteSpace($coverJson)) {
    Write-Host " cover 同步程序没有返回状态数据。" -ForegroundColor Red
    exit $(if ($coverExitCode -ne 0) { $coverExitCode } else { 2 })
}

try {
    $report = ConvertFrom-Json -InputObject $catalogJson -ErrorAction Stop
    $coverReport = ConvertFrom-Json -InputObject $coverJson -ErrorAction Stop
} catch {
    Write-Host "无法解析同步程序返回的状态，原始输出如下：" -ForegroundColor Red
    Write-Output $catalogJson
    Write-Output $coverJson
    exit 2
}

$overallSuccess = [bool]$report.success -and [bool]$coverReport.success
if ($Json) {
    $combined = [ordered]@{
        success = $overallSuccess
        completedAt = $coverReport.completedAt
        maimai = $report.maimai
        chunithm = $report.chunithm
        covers = $coverReport
    }
    Write-Output ($combined | ConvertTo-Json -Depth 20 -Compress)
} else {
    Write-SyncReport $report $coverReport
}

$finalExitCode = 0
if ($catalogExitCode -ne 0) {
    $finalExitCode = $catalogExitCode
} elseif ($coverExitCode -ne 0) {
    $finalExitCode = $coverExitCode
} elseif (-not $overallSuccess) {
    $finalExitCode = 1
}
exit $finalExitCode
