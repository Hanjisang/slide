[CmdletBinding()]
param(
    [string]$Registry = $(if ($env:COMPANY_REGISTRY) { $env:COMPANY_REGISTRY } else { '10.25.13.206:5000' }),
    [string]$Namespace = $(if ($env:COMPANY_REGISTRY_NAMESPACE) { $env:COMPANY_REGISTRY_NAMESPACE } else { 'custom-develop' }),
    [string]$SourceTag = 'latest',
    [string]$Tag = 'latest',
    [switch]$Login,
    [switch]$SkipVendor,
    [switch]$SkipRuntime,
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

function Get-RegistrySetupHint([string]$Registry) {
    return @"
Registry setup hint for ${Registry}:
1. Connect the company VPN and confirm the registry host/port is reachable.
2. If the registry is plain HTTP, open Docker Desktop -> Settings -> Docker Engine and add:
   "insecure-registries": ["$Registry"]
   Apply & restart Docker Desktop, then retry.
3. If the registry is HTTPS with a private CA, install the CA certificate for this registry instead of using insecure-registries.
4. If Docker Desktop uses a proxy, add the registry host to its proxy bypass / noProxy list.
"@
}

function Invoke-DockerPush([string]$Image, [string]$Registry) {
    $output = @(& docker push $Image 2>&1)
    $exitCode = $LASTEXITCODE
    $output | ForEach-Object { Write-Host $_ }
    if ($exitCode -ne 0) {
        throw "Docker push failed (exit code $exitCode): docker push $Image`n$(Get-RegistrySetupHint $Registry)"
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

$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot
try {
    Write-Host 'Medical Report Platform - local image publisher' -ForegroundColor Green
    Write-Host "Target registry: $Registry/$Namespace"
    Write-Host "Source tag: $SourceTag; target tag: $Tag"

    Write-Step 'Checking Docker service'
    & docker version *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop is not running or the current user cannot access Docker.' }

    $images = @(
        @{ Name = 'medical-report-mvp-backend'; SourceTag = $SourceTag; Service = 'backend'; Kind = 'business'; Required = $true },
        @{ Name = 'medical-report-mvp-frontend'; SourceTag = $SourceTag; Service = 'frontend'; Kind = 'business'; Required = $true },
        @{ Name = 'medical-report-mvp-go-parser'; SourceTag = $SourceTag; Service = 'go-parser'; Kind = 'business'; Required = $true },
        @{ Name = 'medical-report-mvp-slide-worker'; SourceTag = $SourceTag; Service = 'slide-worker'; Kind = 'business'; Required = $true },
        @{ Name = 'medical-report-mvp-go-parser-vendor'; SourceTag = $SourceTag; Service = 'go-parser-vendor'; Kind = 'business'; Required = -not $SkipVendor },
        @{ Name = 'mysql'; SourceTag = '8.4'; Service = 'mysql'; Kind = 'runtime'; Required = -not $SkipRuntime },
        @{ Name = 'minio/minio'; SourceTag = 'RELEASE.2025-04-22T22-12-26Z'; Service = 'minio'; Kind = 'runtime'; Required = -not $SkipRuntime },
        @{ Name = 'nginx'; SourceTag = '1.29-alpine'; Service = 'nginx'; Kind = 'runtime'; Required = -not $SkipRuntime }
    )

    $plans = @()
    foreach ($image in $images) {
        if (($SkipVendor -and $image.Service -eq 'go-parser-vendor') -or ($SkipRuntime -and $image.Kind -eq 'runtime')) {
            Write-Host "Skipping selected image: $($image.Name):$($image.SourceTag)" -ForegroundColor Yellow
            continue
        }
        $source = "$($image.Name):$($image.SourceTag)"
        if (-not (Test-LocalImage $source)) {
            if ($image.Required) { throw "Local image does not exist: $source. Build it first." }
            Write-Host "Skipping optional image: $source" -ForegroundColor Yellow
            continue
        }
        $targetTag = if ($image.Kind -eq 'runtime') { $image.SourceTag } else { $Tag }
        $target = "$Registry/$Namespace/$($image.Name):$targetTag"
        $plans += @{
            Source = $source
            Target = $target
            SourceId = Get-ImageId $source
            Service = $image.Service
            Kind = $image.Kind
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
        $serviceName = $plan.Service
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
    $businessPlans = @($plans | Where-Object { $_.Kind -eq 'business' })
    $runtimePlans = @($plans | Where-Object { $_.Kind -eq 'runtime' })
    Write-Host "Business images: $($businessPlans.Count)"
    Write-Host "Runtime images:  $($runtimePlans.Count)"
    Write-Host "Total images:    $($plans.Count)"
    $plans | ForEach-Object { Write-Host "  [$($_.Kind)] $($_.Source)  ->  $($_.Target)" }
    $answer = Read-Host 'Type YES to start pushing'
    if ($answer.Trim().ToUpperInvariant() -ne 'YES') {
        Write-Host 'Cancelled. No local tags were changed and nothing was pushed.' -ForegroundColor Yellow
        exit 0
    }

    Write-Step 'Checking registry connectivity (connect company VPN first)'
    $endpoint = Get-RegistryEndpoint $Registry
    $connection = Test-NetConnection -ComputerName $endpoint.Host -Port $endpoint.Port -WarningAction SilentlyContinue
    if (-not $connection.TcpTestSucceeded) {
        throw "Cannot connect to $Registry. Check company VPN and registry address, then run again.`n$(Get-RegistrySetupHint $Registry)"
    }

    if ($Login) {
        Write-Step 'Logging in to registry'
        Write-Host 'Docker will read the username and password interactively; credentials are not stored in this script.'
        try {
            Invoke-Docker @('login', $Registry)
        } catch {
            throw "$($_.Exception.Message)`n$(Get-RegistrySetupHint $Registry)"
        }
    }

    Write-Step 'Tagging and pushing'
    foreach ($plan in $plans) {
        Invoke-Docker @('tag', $plan.Source, $plan.Target)

        $taggedId = Get-ImageId $plan.Target
        if ($taggedId -ne $plan.SourceId) {
            throw "Image tag verification failed for $($plan.Target). Source and target image IDs differ."
        }

        $remoteDigest = Invoke-DockerPush $plan.Target $Registry

        $pushedId = Get-ImageId $plan.Target
        if ($pushedId -ne $plan.SourceId) {
            throw "Image verification failed after push for $($plan.Target). Local target image changed."
        }

        Write-Host "Verified local image ID and registry manifest digest: $remoteDigest" -ForegroundColor Green

        if ($PushLatest -and $plan.Kind -eq 'business' -and $Tag -ne 'latest') {
            $latestTarget = "$Registry/$Namespace/$($plan.Source.Split(':')[0]):latest"
            Invoke-Docker @('tag', $plan.Source, $latestTarget)
            $latestId = Get-ImageId $latestTarget
            if ($latestId -ne $plan.SourceId) {
                throw "Image tag verification failed for $latestTarget. Source and target image IDs differ."
            }
            $latestRemoteDigest = Invoke-DockerPush $latestTarget $Registry
            Write-Host "Updated and verified latest: $latestTarget ($latestRemoteDigest)" -ForegroundColor Green
        }
    }

    Write-Host "`nAll selected images pushed successfully. Target: $Registry/$Namespace; business tag: $Tag" -ForegroundColor Green
}
finally {
    Pop-Location
}
