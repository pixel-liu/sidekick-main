# Windows integration checks using real file handles and an isolated fake Maven build.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$testRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('sidekick-updater-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null
$originalPath = $env:PATH
$processes = @()
$handles = @()
function Assert-True($Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}
function New-Fixture([string]$Name) {
    $root = Join-Path $testRoot $Name
    foreach ($dir in @('scripts', 'bin', 'target', 'distribution\bin', 'install\lib', 'install\bin')) {
        New-Item -ItemType Directory -Path (Join-Path $root $dir) -Force | Out-Null
    }
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'update-local.ps1') -Destination (Join-Path $root 'scripts\update-local.ps1')
    Set-Content -LiteralPath (Join-Path $root 'pom.xml') -Value '<project><artifactId>test</artifactId><version>1</version></project>'
    Set-Content -LiteralPath (Join-Path $root 'target\test-1.jar') -Value 'new-jar'
    Set-Content -LiteralPath (Join-Path $root 'distribution\bin\sidekick.cmd') -Value 'new-cmd'
    Set-Content -LiteralPath (Join-Path $root 'distribution\bin\sidekick') -Value 'new-sh'
    Set-Content -LiteralPath (Join-Path $root 'install\lib\sidekick.jar') -Value 'old-jar'
    Set-Content -LiteralPath (Join-Path $root 'install\bin\sidekick.cmd') -Value 'old-cmd'
    Set-Content -LiteralPath (Join-Path $root 'install\bin\sidekick') -Value 'old-sh'
    Set-Content -LiteralPath (Join-Path $root 'install\.env') -Value 'preserved-config'
    Set-Content -LiteralPath (Join-Path $root 'bin\mvn.cmd') -Value '@powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1"'
    Set-Content -LiteralPath (Join-Path $root 'bin\build.ps1') -Value @'
$root = Split-Path -Parent $PSScriptRoot
Set-Content -LiteralPath (Join-Path $root 'build-started') -Value 'yes'
if (Test-Path -LiteralPath (Join-Path $root 'fail-build')) { exit 7 }
if (Test-Path -LiteralPath (Join-Path $root 'pause-build')) {
    while (-not (Test-Path -LiteralPath (Join-Path $root 'continue-build'))) { Start-Sleep -Milliseconds 50 }
}
exit 0
'@
    return $root
}
function Start-Update([string]$Root, [int]$WaitSeconds = 10, [string]$Extra = '') {
    $env:PATH = (Join-Path $Root 'bin') + ';' + $originalPath
    $arguments = '-NoProfile -ExecutionPolicy Bypass -File "{0}\scripts\update-local.ps1" -InstallDir "{0}\install" -LockWaitSeconds {1} {2}' -f $Root, $WaitSeconds, $Extra
    $process = Start-Process powershell.exe -ArgumentList $arguments -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $Root 'stdout.log') -RedirectStandardError (Join-Path $Root 'stderr.log')
    $null = $process.Handle # Keep the handle so Windows PowerShell can read ExitCode after exit.
    $script:processes += $process
    return $process
}
function Wait-Until([scriptblock]$Condition) {
    $timer = [System.Diagnostics.Stopwatch]::StartNew()
    while (-not (& $Condition)) {
        if ($timer.Elapsed.TotalSeconds -gt 20) { throw 'Timed out waiting for updater test condition.' }
        Start-Sleep -Milliseconds 100
    }
}
function Assert-Success($Process, [string]$Root) {
    Assert-True ($Process.WaitForExit(20000)) 'Updater did not exit.'
    $Process.Refresh()
    Assert-True ($Process.ExitCode -eq 0) (Get-Content -LiteralPath (Join-Path $Root 'stdout.log') -Raw)
    foreach ($entry in @(@('lib\sidekick.jar', 'jar'), @('bin\sidekick.cmd', 'cmd'), @('bin\sidekick', 'sh'))) {
        $relative = $entry[0]
        Assert-True ((Get-Content -LiteralPath (Join-Path $Root "install\$relative") -Raw).Trim() -eq "new-$($entry[1])") "Not updated: $relative"
        $backup = @(Get-ChildItem -LiteralPath (Join-Path $Root 'install\.update-backups') -Directory)
        Assert-True ($backup.Count -eq 1) 'Expected one backup directory.'
        Assert-True ((Get-Content -LiteralPath (Join-Path $backup[0].FullName $relative) -Raw).Trim() -eq "old-$($entry[1])") "Bad backup: $relative"
    }
    Assert-True ((Get-Content -LiteralPath (Join-Path $Root 'install\.env') -Raw).Trim() -eq 'preserved-config') 'Configuration changed.'
    Assert-True (Test-Path -LiteralPath (Join-Path $Root 'install\update-sidekick.bat')) 'Launcher missing.'
    Assert-True (@(Get-ChildItem -LiteralPath (Join-Path $Root 'install') -Recurse -Filter '*.new').Count -eq 0) 'Staged files left behind.'
}
function Lock-Jar([string]$Root) {
    $handle = [System.IO.File]::Open((Join-Path $Root 'install\lib\sidekick.jar'), 'Open', 'Read', 'Read')
    $script:handles += $handle
    return $handle
}
try {
    $root = New-Fixture 'normal update with spaces'
    Assert-Success (Start-Update $root) $root
    Write-Host 'PASS: normal update, backups, configuration, launcher and staging cleanup'

    $root = New-Fixture 'locked-timeout'
    $handle = Lock-Jar $root
    $process = Start-Update $root 0
    Assert-True ($process.WaitForExit(20000)) 'Locked updater did not exit.'
    $process.Refresh()
    Assert-True ($process.ExitCode -eq 1) 'Locked update should fail.'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $root 'build-started'))) 'Build started while locked.'
    Assert-True ((Get-Content -LiteralPath (Join-Path $root 'stdout.log') -Raw).Contains('sidekick.jar')) 'Locked path not reported.'
    $handle.Dispose()
    Assert-True ((Get-Content -LiteralPath (Join-Path $root 'install\lib\sidekick.jar') -Raw).Trim() -eq 'old-jar') 'Locked installation changed.'
    Write-Host 'PASS: lock timeout reports file and leaves installation unchanged before build'

    $root = New-Fixture 'lock-release'
    $handle = Lock-Jar $root
    $process = Start-Update $root
    Wait-Until { (Get-Content -LiteralPath (Join-Path $root 'stdout.log') -Raw) -match 'File in use:' }
    $handle.Dispose()
    Assert-Success $process $root
    Write-Host 'PASS: releasing pre-build lock automatically resumes update'

    $root = New-Fixture 'replacement-lock'
    Set-Content -LiteralPath (Join-Path $root 'pause-build') -Value 'yes'
    $process = Start-Update $root
    Wait-Until { Test-Path -LiteralPath (Join-Path $root 'build-started') }
    $handle = Lock-Jar $root
    Set-Content -LiteralPath (Join-Path $root 'continue-build') -Value 'yes'
    Wait-Until { (Get-Content -LiteralPath (Join-Path $root 'stdout.log') -Raw) -match 'File in use:' }
    $handle.Dispose()
    Assert-Success $process $root
    Write-Host 'PASS: lock acquired during build is retried during replacement'

    $root = New-Fixture 'replacement-rollback'
    Set-Content -LiteralPath (Join-Path $root 'pause-build') -Value 'yes'
    $process = Start-Update $root 0
    Wait-Until { Test-Path -LiteralPath (Join-Path $root 'build-started') }
    $handle = [System.IO.File]::Open((Join-Path $root 'install\bin\sidekick.cmd'), 'Open', 'Read', 'Read')
    $handles += $handle
    Set-Content -LiteralPath (Join-Path $root 'continue-build') -Value 'yes'
    Assert-True ($process.WaitForExit(20000)) 'Replacement timeout did not exit.'
    $process.Refresh()
    Assert-True ($process.ExitCode -eq 1) 'Replacement timeout should fail.'
    $handle.Dispose()
    Assert-True ((Get-Content -LiteralPath (Join-Path $root 'install\lib\sidekick.jar') -Raw).Trim() -eq 'old-jar') 'Earlier JAR replacement was not rolled back.'
    Assert-True ((Get-Content -LiteralPath (Join-Path $root 'install\bin\sidekick.cmd') -Raw).Trim() -eq 'old-cmd') 'Locked launcher changed.'
    Assert-True (@(Get-ChildItem -LiteralPath (Join-Path $root 'install') -Recurse -Filter '*.new').Count -eq 0) 'Failed update left staged files.'
    Write-Host 'PASS: replacement timeout rolls back earlier replacement and cleans staging'

    $root = New-Fixture 'build-failure'
    Set-Content -LiteralPath (Join-Path $root 'fail-build') -Value 'yes'
    $process = Start-Update $root
    Assert-True ($process.WaitForExit(20000)) 'Failed build updater did not exit.'
    $process.Refresh()
    Assert-True ($process.ExitCode -eq 1) 'Failed build should fail update.'
    Assert-True ((Get-Content -LiteralPath (Join-Path $root 'install\lib\sidekick.jar') -Raw).Trim() -eq 'old-jar') 'Failed build changed installation.'
    Write-Host 'PASS: build failure preserves installation'

    $root = New-Fixture 'register-only'
    $handle = Lock-Jar $root
    $process = Start-Update $root 0 '-RegisterOnly'
    Assert-True ($process.WaitForExit(20000)) 'Registration did not exit.'
    $process.Refresh()
    Assert-True ($process.ExitCode -eq 0) 'Registration should work while installed jar is locked.'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $root 'build-started'))) 'Registration started a build.'
    Write-Host 'PASS: registration bypasses locks and build'
} finally {
    foreach ($handle in $handles) { $handle.Dispose() }
    foreach ($process in $processes) {
        if (-not $process.HasExited) { $process.Kill(); $process.WaitForExit() }
        $process.Dispose()
    }
    $env:PATH = $originalPath
    $resolvedTestRoot = [System.IO.Path]::GetFullPath($testRoot)
    $tempPrefix = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\') + '\sidekick-updater-test-'
    if ($resolvedTestRoot.StartsWith($tempPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
    }
}
