param(
    [ValidateRange(1, 65535)]
    [ValidateScript({
        if ($_ -eq 8081) {
            throw "8081 is reserved for the WeChat sync helper. Use 8080 (default) or another port such as 8090."
        }
        return $true
    })]
    [int]$Port = 8080
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$outputDirectory = Join-Path $projectRoot "out"
$sourceFiles = Get-ChildItem -LiteralPath $projectRoot -Filter "*.java" -File |
    Where-Object { $_.Name -notlike "*Test.java" } |
    ForEach-Object { $_.FullName }

if ($sourceFiles.Count -eq 0) {
    throw "No Java source files were found."
}

if (Test-Path -LiteralPath $outputDirectory) {
    Remove-Item -LiteralPath $outputDirectory -Recurse -Force
}
New-Item -ItemType Directory -Path $outputDirectory | Out-Null

& javac --release 26 -Xlint:all -encoding UTF-8 -d $outputDirectory $sourceFiles
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "Starting B50 web server on all IPv4 interfaces (0.0.0.0:$Port)..."
Write-Host "The localhost and LAN access URLs will be listed after startup."
Write-Host ""

Push-Location $projectRoot
try {
    & java -cp $outputDirectory WebServer $Port
} finally {
    Pop-Location
}
