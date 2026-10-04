@echo off
title Air Connect Pro - 120 FPS Laptop Screen Extender
echo ============================================================
echo   Air Connect Pro - Fullscreen Laptop Screen Extender
echo ============================================================
echo.
echo Starting display stream at 120 FPS (AMOLED High-Speed Mode)...
echo First Priority: USB Cable (Zero Lag) ^| Fallback: Wi-Fi
echo.
echo Tip: If Windows + P 'Extend' is not yet enabled, double-click:
echo      '%~dp0Install-VirtualDisplay.bat' to enable Extended Monitor!
echo.

set SCRIPT_PATH="%~dp0AirConnectPro-ScreenExtender.py"
if not exist %SCRIPT_PATH% set SCRIPT_PATH="%~dp0BluetoothAirMouse\AirConnectPro-ScreenExtender.py"
if not exist %SCRIPT_PATH% set SCRIPT_PATH="C:\temporary\AirConnectPro-ScreenExtender.py"
if not exist %SCRIPT_PATH% set SCRIPT_PATH="C:\temporary\BluetoothAirMouse\AirConnectPro-ScreenExtender.py"

python -u %SCRIPT_PATH% --fps 120
pause
