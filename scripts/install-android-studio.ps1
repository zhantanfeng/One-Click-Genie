$ErrorActionPreference = "Stop"

$installerUrl = "https://edgedl.me.gvt1.com/android/studio/install/2026.1.3.7/android-studio-quail3-windows.exe"
$installerSha256 = "33C0DA36175DBAB84B16257E9709FCE0CA9BDC533AF92ED08D6634116F78BCDD"
$installerDirectory = "D:\AndroidStudio"
$installerPath = Join-Path $installerDirectory "android-studio-installer.exe"
$partsDirectory = Join-Path $installerDirectory "download-parts"
$logPath = "D:\One-Click-Genie\android-studio-install.log"
$totalBytes = [Int64]1508410976
$partCount = 16

function Write-InstallLog {
    param([string]$Message)

    Add-Content -LiteralPath $logPath -Value "[$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')] $Message" -Encoding utf8
}

try {
    New-Item -ItemType Directory -Force -Path $installerDirectory, $partsDirectory | Out-Null

    if (Test-Path -LiteralPath $installerPath) {
        $partialPath = "$installerPath.partial-single"
        Move-Item -LiteralPath $installerPath -Destination $partialPath -Force
        Write-InstallLog "Preserved incomplete single-stream download at $partialPath."
    }

    $parts = @()
    $processes = @()
    for ($index = 0; $index -lt $partCount; $index++) {
        $start = [Int64][math]::Floor($totalBytes * $index / $partCount)
        $end = [Int64][math]::Floor($totalBytes * ($index + 1) / $partCount) - 1
        $partPath = Join-Path $partsDirectory ("part-{0:D2}.bin" -f $index)
        $expectedLength = $end - $start + 1
        $parts += [pscustomobject]@{ Path = $partPath; ExpectedLength = $expectedLength }

        if ((Test-Path -LiteralPath $partPath) -and ((Get-Item -LiteralPath $partPath).Length -eq $expectedLength)) {
            Write-InstallLog "Part $index already complete."
            continue
        }

        Remove-Item -LiteralPath $partPath -Force -ErrorAction SilentlyContinue
        $arguments = @(
            "--location", "--fail", "--retry", "5", "--retry-delay", "5",
            "--range", "$start-$end", "--output", $partPath, $installerUrl
        )
        $process = Start-Process -FilePath "curl.exe" -ArgumentList $arguments -WindowStyle Hidden -PassThru
        $processes += [pscustomobject]@{ Index = $index; Process = $process }
    }

    Write-InstallLog "Started $($processes.Count) download processes."
    while ($processes.Count -gt 0) {
        Start-Sleep -Seconds 15
        $active = @()
        foreach ($item in $processes) {
            if ($item.Process.HasExited) {
                if ($item.Process.ExitCode -ne 0) {
                    throw "Download part $($item.Index) failed with exit code $($item.Process.ExitCode)."
                }
            } else {
                $active += $item
            }
        }
        $processes = $active
        $completed = ($parts | Where-Object { (Test-Path -LiteralPath $_.Path) -and ((Get-Item -LiteralPath $_.Path).Length -eq $_.ExpectedLength) }).Count
        Write-InstallLog "Download progress: $completed/$partCount parts complete."
    }

    foreach ($part in $parts) {
        if (-not (Test-Path -LiteralPath $part.Path) -or (Get-Item -LiteralPath $part.Path).Length -ne $part.ExpectedLength) {
            throw "A downloaded part is incomplete: $($part.Path)"
        }
    }

    $assembledPath = "$installerPath.assembled"
    $destination = [System.IO.File]::Open($assembledPath, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write)
    try {
        foreach ($part in $parts) {
            $source = [System.IO.File]::OpenRead($part.Path)
            try {
                $source.CopyTo($destination, 1048576)
            } finally {
                $source.Dispose()
            }
        }
    } finally {
        $destination.Dispose()
    }
    Move-Item -LiteralPath $assembledPath -Destination $installerPath -Force

    $actualHash = (Get-FileHash -LiteralPath $installerPath -Algorithm SHA256).Hash
    if ($actualHash -ne $installerSha256) {
        throw "SHA-256 verification failed. Expected $installerSha256, got $actualHash."
    }
    Write-InstallLog "Installer SHA-256 verified."

    $installProcess = Start-Process -FilePath $installerPath -ArgumentList @("/S", "/D=$installerDirectory") -Wait -PassThru
    if ($installProcess.ExitCode -ne 0) {
        throw "Android Studio installer failed with exit code $($installProcess.ExitCode)."
    }

    $studio = Join-Path $installerDirectory "bin\studio64.exe"
    if (-not (Test-Path -LiteralPath $studio)) {
        throw "Android Studio installation finished but studio64.exe was not found at $studio."
    }
    Remove-Item -LiteralPath $partsDirectory -Recurse -Force
    Start-Process -FilePath $studio -ArgumentList "D:\One-Click-Genie"
    Write-InstallLog "Android Studio installed and opened with D:\One-Click-Genie."
}
catch {
    Write-InstallLog "FAILED: $($_.Exception.Message)"
    exit 1
}
