@echo off
title Air Connect Pro - 120 FPS Laptop Screen Extender
color 0A
echo ============================================================
echo   Air Connect Pro - Fullscreen Laptop Screen Extender
echo ============================================================
echo.
echo Starting display stream at 120 FPS (AMOLED High-Speed Mode)...
echo First Priority: USB Cable (Zero Lag) ^| Fallback: Wi-Fi
echo Windows Projection: Win + P (Extend / Duplicate)
echo.

set "SCRIPT_DIR=C:\Rarey Temp\Ai long stuff"
set "SCRIPT_PATH=%SCRIPT_DIR%\AirConnectPro-ScreenExtender.py"

python -u "%SCRIPT_PATH%" --fps 120 --res 1920x1080 --quality 82

echo.
echo ============================================================
echo   Stream session ended.
echo ============================================================
pause
