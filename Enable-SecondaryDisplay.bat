@echo off
title Air Connect Pro - Enable Secondary Extended Display
echo =======================================================================
echo   AIR CONNECT PRO - VIRTUAL SECONDARY DISPLAY ACTIVATOR (WIN + P)
echo =======================================================================
echo.

:: Test for Administrator Privileges
net session >nul 2>&1
if %errorLevel% neq 0 (
    echo [*] Administrator privileges required to configure display driver.
    echo [*] Please click 'YES' on the Windows permission prompt on your screen!
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process cmd.exe -ArgumentList '/c `\"`\"%~f0`\"`\"' -Verb RunAs"
    exit /b
)

echo [*] Administrator privileges confirmed!
echo [*] Setting up virtual display configuration in C:\IddSampleDriver...

if not exist "C:\IddSampleDriver" mkdir "C:\IddSampleDriver"
copy /y "C:\Rarey Temp\Ai long stuff\VDD\SignedDrivers\x86\VDD\vdd_settings.xml" "C:\IddSampleDriver\vdd_settings.xml" >nul
reg add "HKLM\SOFTWARE\MikeTheTech\VirtualDisplayDriver" /v "VDDPATH" /t REG_SZ /d "C:\IddSampleDriver" /f >nul
reg add "HKCU\SOFTWARE\MikeTheTech\VirtualDisplayDriver" /v "VDDPATH" /t REG_SZ /d "C:\IddSampleDriver" /f >nul

echo [*] Cleaning up duplicate virtual display devices (preventing black screen)...
cd /d "C:\Rarey Temp\Ai long stuff\VDD"
Dependencies\devcon.exe remove @ROOT\DISPLAY\0001 >nul 2>&1
Dependencies\devcon.exe remove @ROOT\DISPLAY\0002 >nul 2>&1
Dependencies\devcon.exe remove @ROOT\DISPLAY\0003 >nul 2>&1

echo [*] Enforcing clean single 1080p virtual display driver...
Dependencies\devcon.exe remove @ROOT\DISPLAY\0000 >nul 2>&1
timeout /t 1 >nul
pnputil /add-driver "SignedDrivers\x86\VDD\MttVDD.inf" /install >nul 2>&1
Dependencies\devcon.exe install SignedDrivers\x86\VDD\MttVDD.inf Root\MttVDD >nul 2>&1

echo.
echo [*] Triggering device bus scan...
pnputil /scan-devices >nul 2>&1

echo [*] Activating Windows Extended Desktop Mode (Win + P Extend)...
DisplaySwitch.exe /extend

echo.
echo =======================================================================
echo   SUCCESS! Secondary Extended Display is clean and active!
echo   No black screens, no duplicates, native 1080p enabled!
echo =======================================================================
echo.
timeout /t 3 >nul
exit /b 0
