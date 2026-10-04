@echo off
title Air Connect Pro - Enable Secondary Extended Display
echo =======================================================================
echo   AIR CONNECT PRO - VIRTUAL SECONDARY DISPLAY SETUP
echo =======================================================================
echo.
echo This wizard installs the Virtual Display Driver so Windows recognizes
echo a REAL SECOND MONITOR on your laptop!
echo.
echo Once installed:
echo   1. Windows + P will show "Extend" option working!
echo   2. You can drag any app, Chrome tab, or window onto your phone screen!
echo   3. Your phone acts as a true extended secondary display!
echo.
echo Launching the Official Windows Display Driver Installer...
echo Please click 'Next' and 'Install' in the installer window that appears.
echo.

start "" msiexec /i "%~dp0Install-Spacedesk-Driver.msi"

echo.
echo After installation finishes:
echo Press 'Windows + P' on your keyboard and choose 'Extend'!
echo Then launch 'Start-ScreenExtender.bat' to stream your extended desktop!
echo.
pause
