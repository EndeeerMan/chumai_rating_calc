[CmdletBinding(DefaultParameterSetName = "Enable")]
param(
    [ValidateRange(1, 65535)]
    [ValidateScript({
        if ($_ -eq 8081) {
            throw "Port 8081 is reserved for the WeChat sync helper. Use -Helper instead of -Port 8081."
        }
        return $true
    })]
    [int]$Port = 8080,

    [switch]$Helper,

    [Parameter(ParameterSetName = "Disable")]
    [switch]$Disable,

    [Parameter(ParameterSetName = "Remove")]
    [switch]$Remove
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($Helper -and $PSBoundParameters.ContainsKey("Port")) {
    throw "-Helper always manages the fixed TCP 8081 Helper rule. Do not combine -Helper with -Port."
}

if ($Helper) {
    $effectivePort = 8081
    $ruleName = "MaimaiRatingCalc-WeChatHelper-PrivateLAN"
    $ruleDisplayName = "maimai rating calc - WeChat helper (Private LAN)"
    $ruleDescription = "Allow TCP 8081 from the local subnet to the maimai rating calc WeChat sync helper."
} else {
    $effectivePort = $Port
    $ruleName = "MaimaiRatingCalc-Web-PrivateLAN"
    $ruleDisplayName = "maimai rating calc - Web server (Private LAN)"
    $ruleDescription = "Allow TCP $Port from the local subnet to the maimai rating calc web server."
}

function Test-IsAdministrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Invoke-ElevatedCopy {
    $hostExecutable = (Get-Process -Id $PID).Path
    $arguments = @(
        "-NoProfile"
        "-ExecutionPolicy"
        "Bypass"
        "-File"
        $PSCommandPath
    )

    if ($Helper) {
        $arguments += "-Helper"
    } else {
        $arguments += @("-Port", [string]$Port)
    }

    if ($Disable) {
        $arguments += "-Disable"
    } elseif ($Remove) {
        $arguments += "-Remove"
    }

    Write-Host "Administrator permission is required. Opening the Windows UAC prompt..."
    try {
        $process = Start-Process -FilePath $hostExecutable -ArgumentList $arguments -Verb RunAs -Wait -PassThru
    } catch {
        throw "Administrator elevation was cancelled or failed. $($_.Exception.Message)"
    }

    exit $process.ExitCode
}

if (-not (Test-IsAdministrator)) {
    Invoke-ElevatedCopy
}

Import-Module NetSecurity -ErrorAction Stop
$existingRule = Get-NetFirewallRule -Name $ruleName -PolicyStore PersistentStore -ErrorAction SilentlyContinue

if ($Remove) {
    if ($null -eq $existingRule) {
        Write-Host "Firewall rule is already absent: $ruleDisplayName"
    } else {
        Remove-NetFirewallRule -Name $ruleName -PolicyStore PersistentStore -Confirm:$false
        Write-Host "Removed firewall rule: $ruleDisplayName"
    }
    exit 0
}

if ($Disable) {
    if ($null -eq $existingRule) {
        Write-Host "Firewall rule does not exist, so there is nothing to disable: $ruleDisplayName"
    } else {
        Set-NetFirewallRule `
            -Name $ruleName `
            -PolicyStore PersistentStore `
            -Enabled False | Out-Null
        Write-Host "Disabled firewall rule: $ruleDisplayName"
    }
    exit 0
}

$ruleParameters = @{
    Name                = $ruleName
    PolicyStore         = "PersistentStore"
    Description         = $ruleDescription
    Enabled             = "True"
    Profile             = "Private"
    Direction           = "Inbound"
    Action              = "Allow"
    EdgeTraversalPolicy = "Block"
    LocalAddress        = "Any"
    RemoteAddress       = "LocalSubnet"
    Protocol            = "TCP"
    LocalPort           = [string]$effectivePort
    RemotePort          = "Any"
    Program             = "Any"
    Service             = "Any"
    InterfaceType       = "Any"
}

if ($null -ne $existingRule) {
    # Recreate the one deterministic rule so every port/address filter is
    # normalized consistently across supported Windows versions.
    Remove-NetFirewallRule -Name $ruleName -PolicyStore PersistentStore -Confirm:$false
}

New-NetFirewallRule `
    @ruleParameters `
    -DisplayName $ruleDisplayName | Out-Null

if ($null -eq $existingRule) {
    Write-Host "Created firewall rule: $ruleDisplayName"
} else {
    Write-Host "Updated firewall rule: $ruleDisplayName"
}

Write-Host "  Profile:       Private"
Write-Host "  Direction:     Inbound"
Write-Host "  Protocol/port: TCP $effectivePort"
Write-Host "  Remote address: LocalSubnet"
Write-Host "  Edge traversal: Block"
Write-Host "  Public network: not allowed by this rule"
Write-Host ""
if ($Helper) {
    Write-Host "Start the Helper with: .\run-wechat-helper.ps1"
    Write-Host "On the phone, use <computer-private-ip>:8081 as the HTTP proxy for WeChat sync."
} else {
    Write-Host "Start the website with: .\run-web.ps1 -Port $Port"
    Write-Host "On a phone in the same private LAN, open: http://<computer-private-ip>:$Port/"
}
