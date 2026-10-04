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

reg query "HKLM\SOFTWARE\MikeTheTech\VirtualDisplayDriver" /v "VDDPATH" >nul 2>&1
if %errorLevel% neq 0 (
    echo [*] Configuring native 20:9 ultra-wide display driver...
    echo [*] Please click 'YES' on the Windows prompt on your screen!
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process cmd.exe -ArgumentList '/c `\"`\"%SCRIPT_DIR%\Enable-SecondaryDisplay.bat`\"`\"' -Verb RunAs -Wait"
)

python -u "%SCRIPT_PATH%" --fps 120 --res 2400x1080 --quality 82

echo.
echo ============================================================
echo   Stream session ended.
echo ============================================================
pause
