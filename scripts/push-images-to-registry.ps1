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

function Invoke-DockerPush([string]$Image) {
    $output = @(& docker push $Image 2>&1)
    $exitCode = $LASTEXITCODE
    $output | ForEach-Object { Write-Host $_ }
    if ($exitCode -ne 0) {
        throw "Docker push failed (exit code $exitCode): docker push $Image"
    }

    $digest = $null
    foreach ($line in $output) {
        if ([string]$line -match 'digest:\s*(sha256:[0-9a-f]{64})\b') {
            $digest = $Matches[1]
        }
    }
    if ([string]::IsNullOrWhiteSpace($digest)) {
        throw "Docker push did not return a registry digest for $Image. Refusing to report success."
    }
    return $digest
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

function Get-ImageId([string]$Image) {
    $value = & docker image inspect $Image --format '{{.Id}}'
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($value)) {
        throw "Cannot inspect local image: $Image"
    }
    return $value.Trim()
}

function Get-ImageDigest([string]$Image, [string]$Repository) {
    $raw = & docker image inspect $Image --format '{{json .RepoDigests}}'
    if ($LASTEXITCODE -ne 0) {
        throw "Cannot inspect image digest: $Image"
    }

    $entries = @($raw | ConvertFrom-Json)
    $prefix = "$Repository@"
    $match = @(
        $entries |
            ForEach-Object { [string]$_ } |
            Where-Object { $_.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase) }
    ) | Select-Object -First 1

    if ($null -eq $match) { return $null }
    return ([string]$match).Substring($prefix.Length)
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
        $repository = "$Registry/$Namespace/$($image.Name)"
        $plans += @{
            Source = $source
            Target = $target
            Repository = $repository
            SourceId = Get-ImageId $source
        }
    }

    if ($plans.Count -eq 0) { throw 'No local images are available to push.' }

    Write-Step 'Checking local running services'
    $runningServiceIds = @{}
    $composeOutput = @(& docker compose ps --status running --format json 2>$null)
    if ($LASTEXITCODE -eq 0) {
        foreach ($line in $composeOutput) {
            try {
                $service = ([string]$line | ConvertFrom-Json)
                $labelText = [string]$service.Labels
                if ($labelText -match '(^|,)com\.docker\.compose\.image=([^,]+)') {
                    $runningServiceIds[[string]$service.Service] = $Matches[2]
                }
            } catch {
                Write-Host 'Could not parse a running Compose service record; continuing with image ID checks.' -ForegroundColor Yellow
            }
        }
    } else {
        Write-Host 'Could not inspect running Compose services; image ID checks will still be performed.' -ForegroundColor Yellow
    }

    foreach ($plan in $plans) {
        $serviceName = $plan.Source.Split(':')[0] -replace '^medical-report-mvp-', ''
        if ($runningServiceIds.ContainsKey($serviceName)) {
            if ($runningServiceIds[$serviceName] -ne $plan.SourceId) {
                throw "Running local service '$serviceName' does not use $($plan.Source). Rebuild/restart the local stack before pushing."
            }
            Write-Host "  $serviceName uses the exact source image ID $($plan.SourceId)" -ForegroundColor Green
        } else {
            Write-Host "  $serviceName is not running; source image ID will be checked directly." -ForegroundColor Yellow
        }
    }

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

        $taggedId = Get-ImageId $plan.Target
        if ($taggedId -ne $plan.SourceId) {
            throw "Image tag verification failed for $($plan.Target). Source and target image IDs differ."
        }

        $remoteDigest = Invoke-DockerPush $plan.Target

        $pushedId = Get-ImageId $plan.Target
        if ($pushedId -ne $plan.SourceId) {
            throw "Image verification failed after push for $($plan.Target). Local target image changed."
        }

        $pushedDigest = Get-ImageDigest $plan.Target $plan.Repository
        if ($pushedDigest -and ($remoteDigest -ne $pushedDigest)) {
            throw "Digest verification failed for $($plan.Target). Registry: $remoteDigest; local target: $pushedDigest"
        }

        Write-Host "Verified exact image digest: $($plan.Repository)@$remoteDigest" -ForegroundColor Green

        if ($PushLatest -and $Tag -ne 'latest') {
            $latestTarget = "$($plan.Repository):latest"
            Invoke-Docker @('tag', $plan.Source, $latestTarget)
            $latestId = Get-ImageId $latestTarget
            if ($latestId -ne $plan.SourceId) {
                throw "Image tag verification failed for $latestTarget. Source and target image IDs differ."
            }
            $latestRemoteDigest = Invoke-DockerPush $latestTarget
            $latestDigest = Get-ImageDigest $latestTarget $plan.Repository
            if ($latestDigest -and ($latestRemoteDigest -ne $latestDigest)) {
                throw "Digest verification failed for $latestTarget. Registry: $latestRemoteDigest; local target: $latestDigest"
            }
            Write-Host "Updated and verified latest: $latestTarget ($latestRemoteDigest)" -ForegroundColor Green
        }
    }

    Write-Host "`nAll images pushed successfully. Target: $Registry/$Namespace; tag: $Tag" -ForegroundColor Green
}
finally {
    Pop-Location
}
