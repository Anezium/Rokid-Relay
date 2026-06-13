param(
    [string]$PhoneSerial = "",
    [string]$GlassesSerial = "",
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [switch]$ForceInstall
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$ArtifactsRoot = Join-Path $Root "qa\artifacts"
$RunId = Get-Date -Format "yyyyMMdd-HHmmss"
$Artifacts = Join-Path $ArtifactsRoot $RunId
$PhonePackage = "com.anezium.rokidrelay.phone"
$GlassesPackage = "com.anezium.rokidrelay.glasses"
$PhoneApk = Join-Path $Root "phone\build\outputs\apk\debug\phone-debug.apk"
$GlassesApk = Join-Path $Root "glasses\build\outputs\apk\debug\glasses-debug.apk"
$RemoteScreenshot = "/sdcard/rokid-regression.png"
$Results = New-Object System.Collections.Generic.List[string]

New-Item -ItemType Directory -Force -Path $Artifacts | Out-Null

function Add-Result {
    param(
        [string]$Status,
        [string]$Scenario,
        [string]$Message
    )

    $line = "| $Status | $Scenario | $Message |"
    $Results.Add($line) | Out-Null
    Write-Host "[$Status] $Scenario - $Message"
}

function Get-AdbPath {
    $localAdb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
    if (Test-Path -LiteralPath $localAdb) {
        return $localAdb
    }
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }
    throw "adb introuvable. Installe Android platform-tools ou ajoute adb au PATH."
}

function Get-AdbDevices {
    param([string]$Adb)

    $devices = @()
    foreach ($line in (& $Adb devices)) {
        $clean = ($line -replace "`r", "").Trim()
        if ($clean -match "^(\S+)\s+device$") {
            $devices += $Matches[1]
        }
    }
    return $devices
}

function Resolve-Serial {
    param(
        [string]$Adb,
        [string]$Serial,
        [string]$Label,
        [string[]]$Devices
    )

    if (-not [string]::IsNullOrWhiteSpace($Serial)) {
        $clean = $Serial.Trim()
        if ($Devices -notcontains $clean) {
            throw "$Label serial '$clean' introuvable dans adb devices: $($Devices -join ', ')"
        }
        return $clean
    }

    if ($Devices.Count -eq 1) {
        return $Devices[0]
    }

    throw "Plusieurs devices ADB sont connectes ($($Devices -join ', ')). Relance avec -PhoneSerial et -GlassesSerial."
}

function Invoke-Adb {
    param(
        [string]$Adb,
        [string]$Serial,
        [string[]]$Arguments,
        [switch]$AllowFailure
    )

    $oldPreference = $ErrorActionPreference
    $oldNativePreference = $null
    $hasNativePreference = Get-Variable -Name PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue
    if ($hasNativePreference) {
        $oldNativePreference = $PSNativeCommandUseErrorActionPreference
        $PSNativeCommandUseErrorActionPreference = $false
    }
    try {
        $ErrorActionPreference = "Continue"
        $output = & $Adb -s $Serial @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $oldPreference
        if ($hasNativePreference) {
            $PSNativeCommandUseErrorActionPreference = $oldNativePreference
        }
    }
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "adb -s $Serial $($Arguments -join ' ') failed ($exitCode): $($output -join "`n")"
    }
    return [pscustomobject]@{
        ExitCode = $exitCode
        Output = $output
    }
}

function Save-Text {
    param(
        [string]$Path,
        [object]$Content
    )

    $Content | Out-File -LiteralPath $Path -Encoding utf8
}

function Capture-Logcat {
    param(
        [string]$Adb,
        [string]$Serial,
        [string]$Name
    )

    $path = Join-Path $Artifacts "$Name-logcat.txt"
    $result = Invoke-Adb $Adb $Serial @("logcat", "-d", "-t", "500") -AllowFailure
    Save-Text $path $result.Output
    return $path
}

function Capture-PackageState {
    param(
        [string]$Adb,
        [string]$Serial,
        [string]$PackageName,
        [string]$Name
    )

    $path = Join-Path $Artifacts "$Name-package.txt"
    $result = Invoke-Adb $Adb $Serial @("shell", "dumpsys", "package", $PackageName) -AllowFailure
    Save-Text $path $result.Output
    return $path
}

function Capture-NotificationState {
    param(
        [string]$Adb,
        [string]$Serial,
        [string]$Name
    )

    $path = Join-Path $Artifacts "$Name-notifications.txt"
    $result = Invoke-Adb $Adb $Serial @("shell", "dumpsys", "notification", "--noredact") -AllowFailure
    Save-Text $path $result.Output
    return $path
}

function Test-PackageInstalled {
    param(
        [string]$Adb,
        [string]$Serial,
        [string]$PackageName
    )

    $result = Invoke-Adb $Adb $Serial @("shell", "pm", "path", $PackageName) -AllowFailure
    if ($result.ExitCode -ne 0) {
        return $false
    }
    return (($result.Output -join "`n") -match "package:")
}

function Install-IfNeeded {
    param(
        [string]$Adb,
        [string]$Serial,
        [string]$PackageName,
        [string]$ApkPath,
        [string]$Label
    )

    if (-not (Test-Path -LiteralPath $ApkPath)) {
        throw "$Label APK missing: $ApkPath"
    }

    if ((Test-PackageInstalled $Adb $Serial $PackageName) -and -not $ForceInstall) {
        return "$Label already installed; skipped reinstall (use -ForceInstall to force)"
    }

    Invoke-Adb $Adb $Serial @("install", "-r", $ApkPath) | Out-Null
    return "$Label installed"
}

function Capture-Screenshot {
    param(
        [string]$Adb,
        [string]$Serial,
        [string]$Name
    )

    $path = Join-Path $Artifacts "$Name.png"
    Invoke-Adb $Adb $Serial @("shell", "screencap", "-p", $RemoteScreenshot) | Out-Null
    Invoke-Adb $Adb $Serial @("pull", $RemoteScreenshot, $path) | Out-Null
    Invoke-Adb $Adb $Serial @("shell", "rm", $RemoteScreenshot) -AllowFailure | Out-Null
    return $path
}

function Launch-Package {
    param(
        [string]$Adb,
        [string]$Serial,
        [string]$PackageName
    )

    Invoke-Adb $Adb $Serial @(
        "shell",
        "monkey",
        "-p",
        $PackageName,
        "-c",
        "android.intent.category.LAUNCHER",
        "1"
    ) | Out-Null
}

Write-Host "Rokid regression smoke for Rokid Relay"
Write-Host "Artifacts directory: $Artifacts"

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

$Adb = Get-AdbPath
$Devices = @(Get-AdbDevices $Adb)
if ($Devices.Count -eq 0) {
    throw "Aucun device ADB connecte."
}

$PhoneSerial = Resolve-Serial $Adb $PhoneSerial "Phone" $Devices
$GlassesSerial = Resolve-Serial $Adb $GlassesSerial "Glasses" $Devices
Write-Host "Phone serial: $PhoneSerial"
Write-Host "Glasses serial: $GlassesSerial"

Invoke-Adb $Adb $PhoneSerial @("logcat", "-c") -AllowFailure | Out-Null
Invoke-Adb $Adb $GlassesSerial @("logcat", "-c") -AllowFailure | Out-Null

if (-not $SkipInstall) {
    $glassesInstall = Install-IfNeeded $Adb $GlassesSerial $GlassesPackage $GlassesApk "Glasses"
    $phoneInstall = Install-IfNeeded $Adb $PhoneSerial $PhonePackage $PhoneApk "Phone"
    Add-Result "PASS" "app-install" "$glassesInstall; $phoneInstall"
} else {
    Add-Result "SKIP" "app-install" "Skipped by -SkipInstall"
}

Launch-Package $Adb $PhoneSerial $PhonePackage
Start-Sleep -Milliseconds 700
Launch-Package $Adb $GlassesSerial $GlassesPackage
Start-Sleep -Milliseconds 1200
Capture-PackageState $Adb $PhoneSerial $PhonePackage "phone" | Out-Null
Capture-PackageState $Adb $GlassesSerial $GlassesPackage "glasses" | Out-Null
Capture-Logcat $Adb $PhoneSerial "phone-after-launch" | Out-Null
Capture-Logcat $Adb $GlassesSerial "glasses-after-launch" | Out-Null
Capture-Screenshot $Adb $GlassesSerial "glasses-launch" | Out-Null
Add-Result "PASS" "app-launch-smoke" "Launched phone and glasses packages, captured package state/logcat/glasses screenshot"

Invoke-Adb $Adb $GlassesSerial @("shell", "input", "keyevent", "22", "20") | Out-Null
Start-Sleep -Milliseconds 500
Capture-Logcat $Adb $GlassesSerial "glasses-input-alias" | Out-Null
Capture-Screenshot $Adb $GlassesSerial "glasses-input-alias" | Out-Null
Add-Result "PARTIAL" "input-alias-debounce" "Replayed ADB keyevent trace [22,20]; exact selectionDelta assertion still needs DUMP_STATE/debug hook"

Write-Host "Posting debug-only phone test notification; the installed phone app must be a debug build."
Invoke-Adb $Adb $PhoneSerial @(
    "shell",
    "am",
    "broadcast",
    "-n",
    "com.anezium.rokidrelay.phone/.TestNotificationReceiver",
    "-a",
    "com.anezium.rokidrelay.phone.POST_TEST_NOTIFICATION",
    "--ei",
    "rokid_relay_test_count",
    "3",
    "--ez",
    "rokid_relay_test_long",
    "true"
) | Out-Null
Start-Sleep -Milliseconds 2200
Capture-Logcat $Adb $PhoneSerial "phone-test-notification" | Out-Null
Capture-Logcat $Adb $GlassesSerial "glasses-test-notification" | Out-Null
Capture-NotificationState $Adb $PhoneSerial "phone-test-notification" | Out-Null
Capture-Screenshot $Adb $GlassesSerial "glasses-test-notification" | Out-Null
Add-Result "PARTIAL" "notification-direct-reply" "Posted explicit debug-only phone test notification, captured phone notification dump and glasses screenshot; automated replyable/state assertion still needs DUMP_STATE or app-private state dump"

Add-Result "SKIP" "cxr-protocol-roundtrip" "Requires debug.SEND_FAKE_CXR_MESSAGE hook"
Add-Result "SKIP" "helper-install-version-refresh" "Requires helper version/fingerprint DUMP_STATE hook"
Add-Result "SKIP" "voice-capture-cancel-and-timeout" "Requires fake voice/audio debug hooks"

$report = Join-Path $Artifacts "report.md"
@(
    "# Rokid Regression Report"
    ""
    "- Run: $RunId"
    "- Phone serial: $PhoneSerial"
    "- Glasses serial: $GlassesSerial"
    ""
    "| Status | Scenario | Message |"
    "| --- | --- | --- |"
    $Results
    ""
    "Artifacts are in this directory."
) | Out-File -LiteralPath $report -Encoding utf8

Write-Host ""
Write-Host "Report: $report"
