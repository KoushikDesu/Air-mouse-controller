@echo off
title Air Connect Pro - 120 FPS Laptop Screen Extender
echo ============================================================
echo   Air Connect Pro - Fullscreen Laptop Screen Extender
echo ============================================================
echo.
echo Starting display stream at 120 FPS (AMOLED High-Speed Mode)...
echo First Priority: USB Cable (Zero Lag) ^| Fallback: Wi-Fi
echo Windows Projection: Win + P (Extend / Duplicate)
echo.

set SCRIPT_DIR=C:\Rarey Temp\Ai long stuff
set SCRIPT_PATH=%SCRIPT_DIR%\AirConnectPro-ScreenExtender.py

:: Check if Secondary Display (Display 2) is active in Windows
python -c "import mss; s=mss.MSS(); exit(0 if len(s.monitors) > 2 else 1)" >nul 2>&1
if %errorLevel% neq 0 (
    echo [!] Windows Secondary Display (Display 2) is not yet detected.
    echo [*] Activating Windows Virtual Extended Monitor...
    echo [*] Please click 'YES' on the Windows permission prompt on your screen!
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process cmd.exe -ArgumentList '/c `\"`\"%SCRIPT_DIR%\Enable-SecondaryDisplay.bat`\"`\"' -Verb RunAs -Wait"
    timeout /t 2 >nul
)

:: Switch Windows to Extend desktop mode
DisplaySwitch.exe /extend >nul 2>&1

:: Launch screen streaming engine at 120 FPS with 1080p native clarity
python -u "%SCRIPT_PATH%" --fps 120 --res 1920x1080 --quality 82
pause
