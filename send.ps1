# Copy the newest library pack onto the plugged-in Lunii.
# STUdio must already be running. The same title replaces the copy already on the device.
$ErrorActionPreference = 'Stop'

function Get-DevicePacks {
    $raw = Invoke-RestMethod -Uri 'http://localhost:8080/api/device/packs' -TimeoutSec 20
    # One JSON array arrives as a single pipeline object. Unwrap it before reading fields.
    return @($raw | ForEach-Object { $_ })
}

function Find-Pack($Packs, [string]$Uuid) {
    foreach ($pack in $Packs) {
        if ([string]$pack.uuid -eq $Uuid) { return $pack }
    }
    return $null
}

function Wait-Pack([string]$Uuid, [bool]$WantPresent) {
    for ($i = 0; $i -lt 40; $i++) {
        Start-Sleep -Seconds 2
        $found = $null -ne (Find-Pack (Get-DevicePacks) $Uuid)
        if ($found -eq $WantPresent) { return }
    }
    throw "The Lunii did not $(if ($WantPresent) {'show'} else {'drop'}) $Uuid"
}

try {
    $infos = Invoke-RestMethod -Uri 'http://localhost:8080/api/device/infos' -TimeoutSec 5
} catch {
    Write-Host 'STUdio is not running on http://localhost:8080.'
    Write-Host 'Open STUdio, plug in the Lunii, then run .\send.ps1 again.'
    exit 1
}
if (-not $infos.plugged) {
    Write-Host 'STUdio is open, but no Lunii is plugged in.'
    exit 1
}

$library = Join-Path $env:USERPROFILE '.studio\library'
$folder = Get-ChildItem -LiteralPath $library -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -like '*.converted_*' } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $folder) {
    Write-Host 'No pack in the library. Run .\pack.ps1 first.'
    exit 1
}
$uuid = ($folder.Name -split '\.converted_', 2)[0]
Write-Host "Pack $uuid"

$already = Find-Pack (Get-DevicePacks) $uuid
if ($null -ne $already) {
    Write-Host "Replacing '$($already.title)' on the Lunii"
    Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/device/removeFromDevice' `
        -ContentType 'application/json' -TimeoutSec 60 `
        -Body (@{ uuid = $uuid } | ConvertTo-Json -Compress) | Out-Null
    Wait-Pack $uuid $false
}

Write-Host "Copying $($folder.Name)"
Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/device/addFromLibrary' `
    -ContentType 'application/json' -TimeoutSec 30 `
    -Body (@{ uuid = $uuid; path = $folder.Name } | ConvertTo-Json -Compress) | Out-Null
Wait-Pack $uuid $true
$done = Find-Pack (Get-DevicePacks) $uuid
Write-Host "On the Lunii: $($done.title)"
