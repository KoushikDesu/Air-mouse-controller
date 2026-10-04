@echo off
title Air Connect Pro - Install Windows Virtual Secondary Display
echo =======================================================================
echo   AIR CONNECT PRO - VIRTUAL SECONDARY DISPLAY INSTALLER (IddCx / VDD)
echo =======================================================================
echo.
echo This utility installs a Virtual Display Driver in Windows so that:
echo   1. Windows + P shows "Extend" display option.
echo   2. Your phone acts as a TRUE second monitor (drag windows onto phone).
echo   3. Supports 120 FPS / 144 FPS AMOLED high-refresh rates!
echo.

net session >nul 2>&1
if %errorLevel% neq 0 (
    echo [!] Administrator privileges required to install Windows Display Driver.
    echo [*] Launching elevation prompt (click "Yes" when prompted)...
    powershell -Command "Start-Process '%~f0' -Verb RunAs"
    exit /b
)

set VDD_PKG=C:\Users\Admin\AppData\Local\Microsoft\WinGet\Packages\VirtualDrivers.Virtual-Display-Driver_Microsoft.Winget.Source_8wekyb3d8bbwe

if not exist "%VDD_PKG%\Dependencies\devcon.exe" (
    echo [*] Preparing Virtual Display Driver via Winget...
    winget install --id VirtualDrivers.Virtual-Display-Driver --accept-source-agreements --accept-package-agreements
)

echo [*] Installing Virtual Display Device Driver...
cd /d "%VDD_PKG%"
Dependencies\devcon.exe install SignedDrivers\x86\VDD\MttVDD.inf Root\MttVDD

echo.
echo =======================================================================
echo   SUCCESS! Virtual Secondary Display has been registered in Windows!
echo =======================================================================
echo.
echo Press 'Windows + P' on your keyboard:
echo   - Choose 'Extend' to drag windows and mouse onto your phone!
echo   - Choose 'Duplicate' to mirror laptop screen.
echo   - Choose 'Second screen only' to display exclusively on phone.
echo.
pause
