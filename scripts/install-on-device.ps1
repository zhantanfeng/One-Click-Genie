$ErrorActionPreference = "Stop"

$adb = "D:\Android\SDK\platform-tools\adb.exe"
$apk = "D:\One-Click-Genie\app\build\outputs\apk\debug\app-debug.apk"
$log = "D:\One-Click-Genie\install-device.log"

function Write-InstallLog {
    param([string]$Message)

    $timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    Add-Content -LiteralPath $log -Value "[$timestamp] $Message" -Encoding utf8
}

try {
    if (-not (Test-Path -LiteralPath $adb)) {
        throw "adb was not found at $adb"
    }
    if (-not (Test-Path -LiteralPath $apk)) {
        throw "APK was not found at $apk"
    }

    Write-InstallLog "Waiting for an authorized Android device."
    do {
        $deviceInfo = (& $adb devices -l 2>&1 | Out-String).Trim()
        $authorizedDevice = $deviceInfo -split "`r?`n" | Where-Object { $_ -match "\sdevice(\s|$)" } | Select-Object -First 1
        if (-not $authorizedDevice) {
            Start-Sleep -Seconds 5
        }
    } while (-not $authorizedDevice)
    Write-InstallLog "Authorized device detected: $authorizedDevice"

    $installOutput = (& $adb install -r $apk 2>&1 | Out-String).Trim()
    $installExitCode = $LASTEXITCODE
    Write-InstallLog "Install output: $installOutput"
    if ($installExitCode -ne 0) {
        throw "APK installation failed with exit code $installExitCode"
    }

    $launchOutput = (& $adb shell am start -n "com.example.gesturereplay/.MainActivity" 2>&1 | Out-String).Trim()
    $launchExitCode = $LASTEXITCODE
    Write-InstallLog "Launch output: $launchOutput"
    if ($launchExitCode -ne 0) {
        throw "App launch failed with exit code $launchExitCode"
    }

    Write-InstallLog "Installation and launch completed successfully."
}
catch {
    Write-InstallLog "FAILED: $($_.Exception.Message)"
    exit 1
}
