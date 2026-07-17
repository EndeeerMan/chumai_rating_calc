[CmdletBinding()]
param(
    [ValidateNotNullOrEmpty()]
    [string]$BackendUrl = "http://127.0.0.1:8080",

    [string]$PythonPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$helperRoot = Join-Path $projectRoot "wechat-helper"
$venvRoot = Join-Path $helperRoot ".venv"
$venvPython = Join-Path $venvRoot "Scripts\python.exe"
$requirements = Join-Path $helperRoot "requirements.txt"

function Resolve-Python312Candidate {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Command,

        [string[]]$PrefixArguments = @()
    )

    $probeArguments = @($PrefixArguments) + @(
        "-c",
        "import sys; print(str(sys.version_info.major)+chr(46)+str(sys.version_info.minor)+chr(124)+sys._base_executable)"
    )
    try {
        $probeOutput = @(& $Command @probeArguments 2>$null)
        if ($LASTEXITCODE -ne 0 -or $probeOutput.Count -eq 0) {
            return $null
        }
    } catch {
        return $null
    }

    $parts = [string]$probeOutput[-1] -split "\|", 2
    if ($parts.Count -ne 2 -or $parts[0] -ne "3.12") {
        return $null
    }
    $baseExecutable = $parts[1].Trim()
    if (-not (Test-Path -LiteralPath $baseExecutable -PathType Leaf)) {
        return $null
    }
    return (Resolve-Path -LiteralPath $baseExecutable).Path
}

function Find-Python312 {
    param([string]$ExplicitPath)

    $candidates = [System.Collections.Generic.List[object]]::new()
    if ($ExplicitPath) {
        $candidates.Add([pscustomobject]@{ Command = $ExplicitPath; Prefix = @() })
    }

    $launcher = Get-Command "py" -ErrorAction SilentlyContinue
    if ($launcher -and $launcher.Source -notmatch "\\Microsoft\\WindowsApps\\") {
        $candidates.Add([pscustomobject]@{ Command = $launcher.Source; Prefix = @("-3.12") })
    }
    foreach ($name in @("python3.12", "python", "python3")) {
        foreach ($command in @(Get-Command $name -All -ErrorAction SilentlyContinue)) {
            if ($command.Source -and $command.Source -notmatch "\\Microsoft\\WindowsApps\\") {
                $candidates.Add([pscustomobject]@{ Command = $command.Source; Prefix = @() })
            }
        }
    }

    foreach ($path in @(
        (Join-Path $env:LOCALAPPDATA "Programs\Python\Python312\python.exe"),
        (Join-Path $env:ProgramFiles "Python312\python.exe"),
        (Join-Path $env:ProgramFiles "Python 3.12\python.exe")
    )) {
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            $candidates.Add([pscustomobject]@{ Command = $path; Prefix = @() })
        }
    }

    $bundledRuntimePattern = Join-Path `
        $env:USERPROFILE `
        ".cache\codex-runtimes\*\dependencies\python\python.exe"
    foreach ($runtimePython in @(
        Get-ChildItem -Path $bundledRuntimePattern -File -ErrorAction SilentlyContinue |
            Sort-Object FullName
    )) {
        $candidates.Add([pscustomobject]@{ Command = $runtimePython.FullName; Prefix = @() })
    }

    foreach ($candidate in $candidates) {
        $resolved = Resolve-Python312Candidate `
            -Command ([string]$candidate.Command) `
            -PrefixArguments ([string[]]$candidate.Prefix)
        if ($resolved) {
            return $resolved
        }
    }
    return $null
}

function Invoke-Checked {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,

        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,

        [Parameter(Mandatory = $true)]
        [string]$FailureMessage
    )

    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$FailureMessage（退出码 $LASTEXITCODE）"
    }
}

if (-not (Test-Path -LiteralPath $helperRoot -PathType Container)) {
    throw "找不到辅助程序目录：$helperRoot"
}
if (-not (Test-Path -LiteralPath $requirements -PathType Leaf)) {
    throw "找不到依赖清单：$requirements"
}

$createdVenv = $false
if (Test-Path -LiteralPath $venvPython -PathType Leaf) {
    $existingPython = Resolve-Python312Candidate -Command $venvPython
    if (-not $existingPython) {
        throw "现有 wechat-helper/.venv 不是 Python 3.12。请先备份并手动移走该目录，再重新运行本脚本。"
    }
    Write-Host "复用现有 Python 3.12 虚拟环境：$venvRoot"
} elseif (Test-Path -LiteralPath $venvRoot) {
    throw "wechat-helper/.venv 已存在但不完整。请先备份并手动移走该目录，再重新运行本脚本。"
} else {
    $basePython = Find-Python312 -ExplicitPath $PythonPath
    if (-not $basePython) {
        throw @"
未找到 Python 3.12，本脚本不会静默安装系统软件。
请先安装后重试，例如：
  winget install --exact --id Python.Python.3.12
也可以通过 -PythonPath 指定 python.exe 的完整路径。
"@
    }
    Write-Host "使用 Python：$basePython"
    Invoke-Checked -FilePath $basePython `
        -Arguments @("-m", "venv", $venvRoot) `
        -FailureMessage "创建 wechat-helper/.venv 失败"
    $createdVenv = $true
}

if ($createdVenv) {
    Invoke-Checked -FilePath $venvPython `
        -Arguments @("-m", "pip", "install", "--disable-pip-version-check", "--upgrade", "pip") `
        -FailureMessage "升级虚拟环境 pip 失败"
}
Invoke-Checked -FilePath $venvPython `
    -Arguments @("-m", "pip", "install", "--disable-pip-version-check", "--requirement", $requirements) `
    -FailureMessage "安装微信同步辅助程序依赖失败"

$oldBackendUrl = $env:MAIMAI_WECHAT_BACKEND_URL
$oldListenPort = $env:MAIMAI_WECHAT_LISTEN_PORT
$oldSessionTtl = $env:MAIMAI_WECHAT_SESSION_TTL_SECONDS
try {
    $env:MAIMAI_WECHAT_BACKEND_URL = $BackendUrl
    $env:MAIMAI_WECHAT_LISTEN_PORT = "8081"
    $env:MAIMAI_WECHAT_SESSION_TTL_SECONDS = "900"
    Push-Location $helperRoot
    try {
        Invoke-Checked -FilePath $venvPython `
            -Arguments @("-m", "wechat_helper", "--dry-run") `
            -FailureMessage "辅助程序 dry-run 检查失败"
    } finally {
        Pop-Location
    }
} finally {
    $env:MAIMAI_WECHAT_BACKEND_URL = $oldBackendUrl
    $env:MAIMAI_WECHAT_LISTEN_PORT = $oldListenPort
    $env:MAIMAI_WECHAT_SESSION_TTL_SECONDS = $oldSessionTtl
}

Write-Host "微信同步辅助程序安装与 dry-run 检查完成。"
Write-Host "启动命令：.\run-wechat-helper.ps1"
Write-Host "局域网手机同步的最小权限防火墙规则：.\setup-lan-access.ps1 -Helper"
