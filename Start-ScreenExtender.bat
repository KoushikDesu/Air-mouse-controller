@echo off
title Air Connect Pro - 120 FPS Laptop Screen Extender
echo ============================================================
echo   Air Connect Pro - Fullscreen Laptop Screen Extender
echo ============================================================
echo.
echo Select Target Stream Frame Rate:
echo   [1] 120 FPS (Super Smooth - Recommended for iQOO Neo AMOLED)
echo   [2] 144 FPS (Ultra High Refresh Rate)
echo   [3]  90 FPS (Balanced Smooth)
echo   [4]  60 FPS (Standard)
echo.
set /p FPS_CHOICE="Enter option [1-4] (or press ENTER for 120 FPS): "

set TARGET_FPS=120
if "%FPS_CHOICE%"=="1" set TARGET_FPS=120
if "%FPS_CHOICE%"=="2" set TARGET_FPS=144
if "%FPS_CHOICE%"=="3" set TARGET_FPS=90
if "%FPS_CHOICE%"=="4" set TARGET_FPS=60

echo.
echo Starting display stream at %TARGET_FPS% FPS over USB and Wi-Fi...
python -u "%~dp0AirConnectPro-ScreenExtender.py" --fps %TARGET_FPS%
pause
