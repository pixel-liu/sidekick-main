[CmdletBinding()]
param(
    [string]$InstallDir = 'D:\software\SideKick'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

try {
    $sourceRoot = Split-Path -Parent $PSScriptRoot
    $resolvedInstallDir = [System.IO.Path]::GetFullPath($InstallDir)
    $relativeFiles = @('lib\sidekick.jar', 'bin\sidekick.cmd', 'bin\sidekick')
    foreach ($relativeFile in $relativeFiles) {
        if (-not (Test-Path -LiteralPath (Join-Path $resolvedInstallDir $relativeFile) -PathType Leaf)) {
            throw "Not an existing SideKick installation: $resolvedInstallDir (missing $relativeFile)"
        }
    }
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
            [System.IO.File]::Replace($stagedFiles[$i], $destination, $backup)
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
        throw "Update failed. Close SideKick and retry. Backups: $backupRoot. $updateError"
    } finally {
        foreach ($staged in $stagedFiles) {
            if (Test-Path -LiteralPath $staged) {
                Remove-Item -LiteralPath $staged -Force
            }
        }
    }

    Write-Host "Updated SideKick in: $resolvedInstallDir"
    Write-Host "Previous program files: $backupRoot"
    Write-Host 'Your .env and other configuration files were preserved.'
    Write-Host "Enter your project directory, then run: & `"$resolvedInstallDir\bin\sidekick.cmd`""
    exit 0
} catch {
    Write-Host "[SideKick] $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
