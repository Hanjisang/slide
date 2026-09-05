[CmdletBinding()]
param(
    [string]$Registry = $(if ($env:COMPANY_REGISTRY) { $env:COMPANY_REGISTRY } else { '10.25.13.206:5000' }),
    [string]$Namespace = $(if ($env:COMPANY_REGISTRY_NAMESPACE) { $env:COMPANY_REGISTRY_NAMESPACE } else { 'custom-develop' }),
    [string]$SourceTag = 'latest',
    [string]$Tag = 'latest',
    [switch]$Login,
    [switch]$SkipVendor,
    [switch]$PushLatest
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Write-Step([string]$Message) {
    Write-Host "`n==> $Message" -ForegroundColor Cyan
}

function Invoke-Docker([string[]]$Arguments) {
    & docker @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Docker operation failed (exit code $LASTEXITCODE): docker $($Arguments -join ' ')"
    }
}

function Get-RegistryEndpoint([string]$Value) {
    $text = $Value.Trim().TrimEnd('/')
    if ($text -match '^https?://') {
        $uri = [Uri]$text
        return @{ Host = $uri.Host; Port = if ($uri.Port -gt 0) { $uri.Port } elseif ($uri.Scheme -eq 'https') { 443 } else { 80 } }
    }
    $parts = $text.Split(':', 2)
    return @{ Host = $parts[0]; Port = if ($parts.Count -eq 2) { [int]$parts[1] } else { 443 } }
}

function Test-LocalImage([string]$Image) {
    & docker image inspect $Image *> $null
    return $LASTEXITCODE -eq 0
}

$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot
try {
    Write-Host 'Medical Report Platform - local image publisher' -ForegroundColor Green
    Write-Host "Target registry: $Registry/$Namespace"
    Write-Host "Source tag: $SourceTag; target tag: $Tag"

    Write-Step 'Checking Docker service'
    & docker version *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop is not running or the current user cannot access Docker.' }

    Write-Step 'Checking registry connectivity (connect company VPN first)'
    $endpoint = Get-RegistryEndpoint $Registry
    $connection = Test-NetConnection -ComputerName $endpoint.Host -Port $endpoint.Port -WarningAction SilentlyContinue
    if (-not $connection.TcpTestSucceeded) {
        throw "Cannot connect to $Registry. Check company VPN and registry address, then run again."
    }

    if ($Login) {
        Write-Step 'Logging in to registry'
        Write-Host 'Docker will read the username and password interactively; credentials are not stored in this script.'
        Invoke-Docker @('login', $Registry)
    }

    $images = @(
        @{ Name = 'medical-report-mvp-backend'; Required = $true },
        @{ Name = 'medical-report-mvp-frontend'; Required = $true },
        @{ Name = 'medical-report-mvp-go-parser'; Required = $true },
        @{ Name = 'medical-report-mvp-slide-worker'; Required = $true },
        @{ Name = 'medical-report-mvp-go-parser-vendor'; Required = -not $SkipVendor }
    )

    $plans = @()
    foreach ($image in $images) {
        $source = "$($image.Name):$SourceTag"
        if (-not (Test-LocalImage $source)) {
            if ($image.Required) { throw "Local image does not exist: $source. Build it first." }
            Write-Host "Skipping optional image: $source" -ForegroundColor Yellow
            continue
        }
        $target = "$Registry/$Namespace/$($image.Name):$Tag"
        $plans += @{ Source = $source; Target = $target }
    }

    if ($plans.Count -eq 0) { throw 'No local images are available to push.' }

    Write-Step 'Images to push'
    $plans | ForEach-Object { Write-Host "  $($_.Source)  ->  $($_.Target)" }
    $answer = Read-Host 'Type YES to start pushing'
    if ($answer -cne 'YES') {
        Write-Host 'Cancelled. No local tags were changed and nothing was pushed.' -ForegroundColor Yellow
        exit 0
    }

    Write-Step 'Tagging and pushing'
    foreach ($plan in $plans) {
        Invoke-Docker @('tag', $plan.Source, $plan.Target)
        Invoke-Docker @('push', $plan.Target)
        Write-Host "Completed: $($plan.Target)" -ForegroundColor Green

        if ($PushLatest -and $Tag -ne 'latest') {
            $latestTarget = "$Registry/$Namespace/$($plan.Target.Split('/')[-1].Split(':')[0]):latest"
            Invoke-Docker @('tag', $plan.Source, $latestTarget)
            Invoke-Docker @('push', $latestTarget)
            Write-Host "Updated latest: $latestTarget" -ForegroundColor Green
        }
    }

    Write-Host "`nAll images pushed successfully. Target: $Registry/$Namespace; tag: $Tag" -ForegroundColor Green
}
finally {
    Pop-Location
}
