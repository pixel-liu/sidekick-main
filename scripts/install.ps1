[CmdletBinding()]
param(
    [string]$Version = "latest",
    [string]$InstallDir = (Join-Path $env:LOCALAPPDATA "SideKick"),
    [string]$ArchivePath,
    [switch]$NoPathUpdate
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repository = "pixel-liu/sidekick-main"
$temporaryRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("sidekick-install-" + [guid]::NewGuid().ToString("N"))
$stagingPath = $null
$backupPath = $null

function Assert-JavaVersion {
    $java = Get-Command java -ErrorAction SilentlyContinue
    if ($null -eq $java) {
        throw "Java was not found. Install Java 17 or newer and add it to PATH first."
    }

    $versionOutput = (& $java.Source -version 2>&1 | Select-Object -First 1).ToString()
    if ($versionOutput -notmatch '"(?<major>\d+)(?:\.(?<minor>\d+))?') {
        throw "Unable to determine the installed Java version: $versionOutput"
    }

    $major = [int]$Matches.major
    if ($major -eq 1 -and $Matches.minor) {
        $major = [int]$Matches.minor
    }
    if ($major -lt 17) {
        throw "SideKick requires Java 17 or newer. Detected: $versionOutput"
    }
}

function Resolve-ReleaseVersion {
    param([string]$RequestedVersion)

    if ($RequestedVersion -ne "latest") {
        return $RequestedVersion.TrimStart("v")
    }

    $release = Invoke-RestMethod -Uri "https://api.github.com/repos/$repository/releases/latest" -Headers @{ "User-Agent" = "SideKick-Installer" }
    if ($null -eq $release.tag_name -or [string]::IsNullOrWhiteSpace([string]$release.tag_name)) {
        throw "The latest GitHub Release does not contain a version tag."
    }
    return ([string]$release.tag_name).TrimStart("v")
}

function Add-UserPathEntry {
    param([string]$Directory)

    $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
    $entries = @($userPath -split ";" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    $normalizedDirectory = $Directory.TrimEnd("\")
    $alreadyPresent = $entries | Where-Object { $_.Trim().TrimEnd("\") -ieq $normalizedDirectory }
    if (-not $alreadyPresent) {
        $newPath = (($entries + $Directory) -join ";")
        [Environment]::SetEnvironmentVariable("Path", $newPath, "User")
        Write-Host "Added to the user PATH: $Directory"
    }

    $processEntries = @($env:Path -split ";")
    if (-not ($processEntries | Where-Object { $_.Trim().TrimEnd("\") -ieq $normalizedDirectory })) {
        $env:Path = "$Directory;$env:Path"
    }
}

Assert-JavaVersion

$resolvedInstallDir = [System.IO.Path]::GetFullPath($InstallDir)
$installRoot = [System.IO.Path]::GetPathRoot($resolvedInstallDir)
if ($resolvedInstallDir.TrimEnd("\") -ieq $installRoot.TrimEnd("\")) {
    throw "Refusing to install into a filesystem root: $resolvedInstallDir"
}

New-Item -ItemType Directory -Path $temporaryRoot | Out-Null
try {
    if ($ArchivePath) {
        $archive = (Resolve-Path -LiteralPath $ArchivePath).Path
        $resolvedVersion = "local"
    } else {
        $resolvedVersion = Resolve-ReleaseVersion -RequestedVersion $Version
        $archive = Join-Path $temporaryRoot "sidekick-$resolvedVersion.zip"
        $downloadUrl = "https://github.com/$repository/releases/download/v$resolvedVersion/sidekick-$resolvedVersion.zip"
        Write-Host "Downloading SideKick $resolvedVersion..."
        Invoke-WebRequest -UseBasicParsing -Uri $downloadUrl -OutFile $archive
        $checksumUrl = "https://github.com/$repository/releases/download/v$resolvedVersion/sidekick-$resolvedVersion-sha256.txt"
        $checksumFile = Join-Path $temporaryRoot "sidekick-$resolvedVersion-sha256.txt"
        Invoke-WebRequest -UseBasicParsing -Uri $checksumUrl -OutFile $checksumFile
        $archiveName = Split-Path -Leaf $archive
        $escapedArchiveName = [regex]::Escape($archiveName)
        $checksumLine = Get-Content -LiteralPath $checksumFile |
            Where-Object { $_ -match ("^[0-9a-fA-F]{64}\s+\*?" + $escapedArchiveName + "$") } |
            Select-Object -First 1
        if (-not $checksumLine) {
            throw "No SHA-256 checksum was published for $archiveName."
        }
        $expectedHash = ($checksumLine -split "\s+")[0].ToUpperInvariant()
        $actualHash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToUpperInvariant()
        if ($actualHash -ne $expectedHash) {
            throw "SHA-256 verification failed for $archiveName."
        }
    }

    $extractPath = Join-Path $temporaryRoot "extracted"
    Expand-Archive -LiteralPath $archive -DestinationPath $extractPath
    $packageRoot = Get-ChildItem -LiteralPath $extractPath -Directory |
        Where-Object {
            (Test-Path -LiteralPath (Join-Path $_.FullName "bin\sidekick.cmd")) -and
            (Test-Path -LiteralPath (Join-Path $_.FullName "lib\sidekick.jar"))
        } |
        Select-Object -First 1
    if ($null -eq $packageRoot) {
        throw "The archive is not a valid SideKick distribution."
    }

    $installParent = Split-Path -Parent $resolvedInstallDir
    $installName = Split-Path -Leaf $resolvedInstallDir
    New-Item -ItemType Directory -Path $installParent -Force | Out-Null
    $suffix = [guid]::NewGuid().ToString("N")
    $stagingPath = Join-Path $installParent (".$installName.installing-$suffix")
    $backupPath = Join-Path $installParent (".$installName.backup-$suffix")
    Copy-Item -LiteralPath $packageRoot.FullName -Destination $stagingPath -Recurse

    if (Test-Path -LiteralPath $resolvedInstallDir) {
        Move-Item -LiteralPath $resolvedInstallDir -Destination $backupPath
    }
    try {
        Move-Item -LiteralPath $stagingPath -Destination $resolvedInstallDir
    } catch {
        if ((Test-Path -LiteralPath $backupPath) -and -not (Test-Path -LiteralPath $resolvedInstallDir)) {
            Move-Item -LiteralPath $backupPath -Destination $resolvedInstallDir
        }
        throw
    }
    if (Test-Path -LiteralPath $backupPath) {
        Remove-Item -LiteralPath $backupPath -Recurse -Force
    }

    $binDirectory = Join-Path $resolvedInstallDir "bin"
    if (-not $NoPathUpdate) {
        Add-UserPathEntry -Directory $binDirectory
    }

    Write-Host "SideKick $resolvedVersion installed in: $resolvedInstallDir"
    if ($NoPathUpdate) {
        Write-Host "PATH was not changed. Add this directory manually: $binDirectory"
    } else {
        Write-Host "Open a new terminal, enter a project directory, and run: sidekick"
    }
} finally {
    if ($stagingPath -and (Test-Path -LiteralPath $stagingPath)) {
        Remove-Item -LiteralPath $stagingPath -Recurse -Force
    }
    if (Test-Path -LiteralPath $temporaryRoot) {
        Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
    }
}
