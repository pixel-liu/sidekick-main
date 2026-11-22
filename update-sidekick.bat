@echo off
setlocal
title SideKick Local Update

set "SIDEKICK_INSTALL_DIR=%~1"
if not defined SIDEKICK_INSTALL_DIR set "SIDEKICK_INSTALL_DIR=D:\software\SideKick"

echo Close running SideKick sessions before updating.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\update-local.ps1" -InstallDir "%SIDEKICK_INSTALL_DIR%"
set "SIDEKICK_UPDATE_RESULT=%ERRORLEVEL%"

echo.
if not "%~2"=="--no-pause" pause
exit /b %SIDEKICK_UPDATE_RESULT%
