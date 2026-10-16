@echo off
setlocal

set "SIDEKICK_HOME=%~dp0.."
set "SIDEKICK_JAR=%SIDEKICK_HOME%\lib\sidekick.jar"

if not exist "%SIDEKICK_JAR%" (
    echo [SideKick] Missing application JAR: "%SIDEKICK_JAR%" 1>&2
    exit /b 1
)

where java >nul 2>nul
if errorlevel 1 (
    echo [SideKick] Java was not found. Install Java 17 or newer and add it to PATH. 1>&2
    exit /b 1
)

java -jar "%SIDEKICK_JAR%" %*
exit /b %errorlevel%
