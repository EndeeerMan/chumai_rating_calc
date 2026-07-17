[CmdletBinding()]
param(
    [ValidateNotNullOrEmpty()]
    [string]$BackendUrl = "http://127.0.0.1:8080",

    [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-ConflictingInterceptProxyProcesses {
    $currentSessionId = (Get-Process -Id $PID -ErrorAction Stop).SessionId
    $knownInterceptProxyProcessNames = @(
        "Fiddler",
        "Fiddler Everywhere",
        "FiddlerEverywhere",
        "Fiddler.WebUi",
        "Charles",
        "Charles4",
        "Charles64",
        "CharlesProxy"
    )

    return @(
        Get-Process -ErrorAction SilentlyContinue |
            Where-Object {
                $_.SessionId -eq $currentSessionId `
                    -and $_.ProcessName -in $knownInterceptProxyProcessNames
            } |
            Sort-Object -Property ProcessName, Id
    )
}

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$helperRoot = Join-Path $projectRoot "wechat-helper"
$venvPython = Join-Path $helperRoot ".venv\Scripts\python.exe"

if (-not (Test-Path -LiteralPath $venvPython -PathType Leaf)) {
    throw "找不到 wechat-helper/.venv。请先运行 .\setup-wechat-helper.ps1。"
}

try {
    $backendUri = [Uri]$BackendUrl
} catch {
    throw "BackendUrl 不是有效 URL：$BackendUrl"
}
if (-not $backendUri.IsAbsoluteUri -or $backendUri.Scheme -notin @("http", "https")) {
    throw "BackendUrl 必须是绝对 HTTP/HTTPS URL。"
}
if ($backendUri.UserInfo `
        -or $backendUri.AbsolutePath -notin @("", "/") `
        -or $backendUri.Query `
        -or $backendUri.Fragment) {
    throw "BackendUrl 只能包含协议、主机和可选端口，不能包含用户信息、路径、查询或片段。"
}
$normalizedBackendUrl = $BackendUrl.TrimEnd("/")

$conflictingProxyProcesses = @(Get-ConflictingInterceptProxyProcesses)
if ($conflictingProxyProcesses.Count -gt 0) {
    $conflictingProxyNames = @(
        $conflictingProxyProcesses |
            ForEach-Object { "$($_.ProcessName) (PID $($_.Id))" }
    ) -join "、"
    $proxyConflictMessage = @(
        "检测到当前用户会话中正在运行会拦截系统代理的程序：$conflictingProxyNames。",
        "它会抢走舞萌 DX / 中二节奏的微信公众号 OAuth 回调，使请求无法进入 Helper，并最终出现 504 Gateway Timeout。",
        "请彻底退出这些程序（包括任务栏托盘中的后台进程），然后重新运行 .\run-wechat-helper.ps1。",
        "Clash 是本项目支持的代理，不需要退出。"
    ) -join " "

    if ($DryRun) {
        Write-Warning "$proxyConflictMessage 当前使用 -DryRun，因此只警告、不阻止检查。"
    } else {
        throw $proxyConflictMessage
    }
}

$oldBackendUrl = $env:MAIMAI_WECHAT_BACKEND_URL
$oldListenHost = $env:MAIMAI_WECHAT_LISTEN_HOST
$oldListenPort = $env:MAIMAI_WECHAT_LISTEN_PORT
$oldSessionTtl = $env:MAIMAI_WECHAT_SESSION_TTL_SECONDS
$helperExitCode = 0
$helperMode = if ($DryRun) { "同步 dry-run" } else { "同步运行" }
$modeArguments = @("-m", "wechat_helper")
try {
    $env:MAIMAI_WECHAT_BACKEND_URL = $normalizedBackendUrl
    $env:MAIMAI_WECHAT_LISTEN_HOST = "0.0.0.0"
    $env:MAIMAI_WECHAT_LISTEN_PORT = "8081"
    $env:MAIMAI_WECHAT_SESSION_TTL_SECONDS = "900"

    Push-Location $helperRoot
    try {
        if ($DryRun) {
            & $venvPython @modeArguments --dry-run
            $helperExitCode = $LASTEXITCODE
        } else {
            & $venvPython @modeArguments --dry-run
            if ($LASTEXITCODE -ne 0) {
                throw "辅助程序 dry-run 检查失败（退出码 $LASTEXITCODE）。"
            }

            $healthUrl = "$normalizedBackendUrl/api/health"
            try {
                $healthResponse = Invoke-WebRequest `
                    -UseBasicParsing `
                    -Uri $healthUrl `
                    -Method Get `
                    -TimeoutSec 5
                $healthPayload = $healthResponse.Content | ConvertFrom-Json
                if ($healthResponse.StatusCode -ne 200 -or $healthPayload.status -ne "ok") {
                    throw "后端返回了非健康状态"
                }
            } catch {
                throw "无法连接 Java 后端健康检查 $healthUrl。请先运行 .\run-web.ps1，或用 -BackendUrl 指定实际地址。详情：$($_.Exception.Message)"
            }
            Write-Host "Java 后端健康检查通过：$healthUrl"
            Write-Host "Helper 正在监听 0.0.0.0:8081；短时会话有效期固定为 15 分钟。"

            & $venvPython @modeArguments
            $helperExitCode = $LASTEXITCODE
        }
    } finally {
        Pop-Location
    }
} finally {
    $env:MAIMAI_WECHAT_BACKEND_URL = $oldBackendUrl
    $env:MAIMAI_WECHAT_LISTEN_HOST = $oldListenHost
    $env:MAIMAI_WECHAT_LISTEN_PORT = $oldListenPort
    $env:MAIMAI_WECHAT_SESSION_TTL_SECONDS = $oldSessionTtl
}

if ($helperExitCode -ne 0) {
    throw "微信同步辅助程序 $helperMode 失败（退出码 $helperExitCode）。"
}
