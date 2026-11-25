[CmdletBinding()]
param(
    [string]$InstallDir = 'D:\software\SideKick',
    [switch]$RegisterOnly,
    [ValidateRange(0, 3600)]
    [int]$LockWaitSeconds = 60
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-WithFileLockRetry {
    param([string]$Path, [scriptblock]$Action)

    $timer = [System.Diagnostics.Stopwatch]::StartNew()
    $reported = $false
    while ($true) {
        try {
            & $Action
            return
        } catch {
            $cause = $_.Exception
            while ($null -ne $cause.InnerException) { $cause = $cause.InnerException }
            $code = $cause.HResult -band 0xffff
            # Only sharing/lock violations are transient. Do not hide permission or disk errors.
            if ($cause -isnot [System.IO.IOException] -or $code -notin @(32, 33)) { throw }
            if (-not $reported) {
                Write-Host "[SideKick] File in use: $Path" -ForegroundColor Yellow
                Write-Host "Close the SideKick session or other program using this file. Waiting up to $LockWaitSeconds seconds; update will continue automatically."
                $reported = $true
            }
            if ($timer.Elapsed.TotalSeconds -ge $LockWaitSeconds) {
                throw "File still in use after $LockWaitSeconds seconds: $Path. Close the program using it and retry."
            }
            Start-Sleep -Milliseconds 250
        }
    }
}

function Wait-ForInstallationFiles {
    foreach ($relativeFile in $relativeFiles) {
        $destination = Join-Path $resolvedInstallDir $relativeFile
        Invoke-WithFileLockRetry -Path $destination -Action {
            $handle = [System.IO.File]::Open($destination, [System.IO.FileMode]::Open,
                [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
            $handle.Dispose()
        }
    }
}

function Register-LocalUpdater {
    $batchSourceRoot = $sourceRoot.Replace('%', '%%')
    $launcher = @"
@echo off
chcp 65001 >nul
setlocal
set "SIDEKICK_SOURCE_DIR=$batchSourceRoot"
if not exist "%SIDEKICK_SOURCE_DIR%\update-sidekick.bat" (
    echo [SideKick] Source repository was moved. Register this updater again.
    if not "%~1"=="--no-pause" pause
    exit /b 1
)
call "%SIDEKICK_SOURCE_DIR%\update-sidekick.bat" "%~dp0." "%~1"
exit /b %errorlevel%
"@
    $launcherPath = Join-Path $resolvedInstallDir 'update-sidekick.bat'
    [System.IO.File]::WriteAllText($launcherPath, ($launcher -replace '\r?\n', "`r`n") + "`r`n", [System.Text.UTF8Encoding]::new($false))
    Write-Host "Double-click updater: $launcherPath"
}

try {
    $sourceRoot = Split-Path -Parent $PSScriptRoot
    $resolvedInstallDir = [System.IO.Path]::GetFullPath($InstallDir)
    $relativeFiles = @('lib\sidekick.jar', 'bin\sidekick.cmd', 'bin\sidekick')
    foreach ($relativeFile in $relativeFiles) {
        if (-not (Test-Path -LiteralPath (Join-Path $resolvedInstallDir $relativeFile) -PathType Leaf)) {
            throw "Not an existing SideKick installation: $resolvedInstallDir (missing $relativeFile)"
        }
    }
    if ($RegisterOnly) {
        Register-LocalUpdater
        exit 0
    }
    # Fail before an expensive build if an existing session keeps the installation locked.
    Wait-ForInstallationFiles
    $maven = Get-Command mvn.cmd -ErrorAction Stop
    Get-Command java.exe -ErrorAction Stop | Out-Null

    Push-Location -LiteralPath $sourceRoot
    try {
        Write-Host "Building SideKick from: $sourceRoot"
        & $maven.Source clean package
        if ($LASTEXITCODE -ne 0) {
            throw "Maven build failed (exit $LASTEXITCODE). Installation was not changed."
        }
    } finally {
        Pop-Location
    }

    [xml]$pom = Get-Content -LiteralPath (Join-Path $sourceRoot 'pom.xml') -Raw
    $jarName = '{0}-{1}.jar' -f $pom.project.artifactId, $pom.project.version
    $sourceFiles = @(
        (Join-Path $sourceRoot "target\$jarName"),
        (Join-Path $sourceRoot 'distribution\bin\sidekick.cmd'),
        (Join-Path $sourceRoot 'distribution\bin\sidekick')
    )
    foreach ($sourceFile in $sourceFiles) {
        if (-not (Test-Path -LiteralPath $sourceFile -PathType Leaf)) {
            throw "Missing build output: $sourceFile. Installation was not changed."
        }
    }

    $suffix = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N')
    $backupRoot = Join-Path $resolvedInstallDir ".update-backups\$suffix"
    $stagedFiles = @()
    $replacedFiles = @()
    try {
        # Stage every file on the destination volume before replacing anything.
        for ($i = 0; $i -lt $relativeFiles.Count; $i++) {
            $destination = Join-Path $resolvedInstallDir $relativeFiles[$i]
            $backup = Join-Path $backupRoot $relativeFiles[$i]
            New-Item -ItemType Directory -Path (Split-Path -Parent $backup) -Force | Out-Null
            $staged = "$destination.$suffix.new"
            $stagedFiles += $staged
            Copy-Item -LiteralPath $sourceFiles[$i] -Destination $staged
        }
        for ($i = 0; $i -lt $relativeFiles.Count; $i++) {
            $destination = Join-Path $resolvedInstallDir $relativeFiles[$i]
            $backup = Join-Path $backupRoot $relativeFiles[$i]
            # A session or scanner can acquire a lock after the pre-build check.
            Invoke-WithFileLockRetry -Path $destination -Action {
                [System.IO.File]::Replace($stagedFiles[$i], $destination, $backup)
            }
            $replacedFiles += $relativeFiles[$i]
        }
    } catch {
        $updateError = $_
        foreach ($relativeFile in $replacedFiles) {
            try {
                Copy-Item -LiteralPath (Join-Path $backupRoot $relativeFile) `
                    -Destination (Join-Path $resolvedInstallDir $relativeFile) -Force
            } catch {
                Write-Warning "Restore failed for $relativeFile. Restore it manually from: $backupRoot"
            }
        }
        throw "Update failed. Backups of replaced files: $backupRoot. $updateError"
    } finally {
        foreach ($staged in $stagedFiles) {
            if (Test-Path -LiteralPath $staged) {
                Remove-Item -LiteralPath $staged -Force
            }
        }
    }

    Register-LocalUpdater
    Write-Host "Updated SideKick in: $resolvedInstallDir"
    Write-Host "Previous program files: $backupRoot"
    Write-Host 'Your .env and other configuration files were preserved.'
    Write-Host "Enter your project directory, then run: & `"$resolvedInstallDir\bin\sidekick.cmd`""
    exit 0
} catch {
    Write-Host "[SideKick] $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
