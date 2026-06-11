param(
    [string]$PhoneSerial = "",
    [string]$GlassesSerial = "",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Artifacts = Join-Path $Root "qa\artifacts"
New-Item -ItemType Directory -Force -Path $Artifacts | Out-Null

Write-Host "Rokid regression smoke for Rokid Relay"
Write-Host "Scenarios:"
Write-Host ' - app-launch-smoke: qa/scenarios/app-launch-smoke.yaml'
Write-Host ' - input-alias-debounce: qa/scenarios/input-alias-debounce.yaml'
Write-Host ' - back-and-confirm-navigation: qa/scenarios/back-and-confirm-navigation.yaml'
Write-Host ' - notification-direct-reply: qa/scenarios/notification-direct-reply.yaml'
Write-Host ' - cxr-protocol-roundtrip: qa/scenarios/cxr-protocol-roundtrip.yaml'
Write-Host ' - helper-install-version-refresh: qa/scenarios/helper-install-version-refresh.yaml'
Write-Host ' - voice-capture-cancel-and-timeout: qa/scenarios/voice-capture-cancel-and-timeout.yaml'

if (-not $SkipBuild) {
    Push-Location $Root
    try {
        cmd /c ".\\gradlew.bat :glasses:testDebugUnitTest"
        cmd /c ".\\gradlew.bat :glasses:assembleDebug"
        cmd /c ".\\gradlew.bat :phone:assembleDebug"
    } finally {
        Pop-Location
    }
}

Write-Host ""
Write-Host "Next: implement scenario execution or ask Codex to flesh out this runner."
Write-Host "For now it builds and points at generated scenario YAML."
Write-Host "Artifacts directory: $Artifacts"
