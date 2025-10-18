@echo off
title ValVoice - Complete Audio Test Suite
color 0A

:MENU
cls
echo ========================================
echo     ValVoice Audio Test Suite
echo ========================================
echo.
echo Choose a test:
echo.
echo 1. Test TTS on Default Speakers
echo    (Verifies Windows TTS is working)
echo.
echo 2. Test VB-CABLE Routing
echo    (Verifies ValVoice routing + monitoring)
echo.
echo 3. Setup Audio Monitoring (REQUIRED!)
echo    (Enable "Listen to this device")
echo.
echo 4. Auto-Setup Audio Monitoring
echo    (Automated setup attempt)
echo.
echo 5. Check Current Audio Status
echo.
echo 6. Open Sound Control Panel
echo.
echo 7. Exit
echo.
echo ========================================
set /p choice="Enter your choice (1-7): "

if "%choice%"=="1" goto TEST_SPEAKERS
if "%choice%"=="2" goto TEST_VBCABLE
if "%choice%"=="3" goto SETUP_MANUAL
if "%choice%"=="4" goto SETUP_AUTO
if "%choice%"=="5" goto CHECK_STATUS
if "%choice%"=="6" goto OPEN_SOUND
if "%choice%"=="7" goto EXIT
goto MENU

:TEST_SPEAKERS
cls
echo ========================================
echo Test 1: TTS on Default Speakers
echo ========================================
echo.
echo This plays TTS through your DEFAULT audio device
echo (Should work even without VB-CABLE)
echo.
pause
echo.
echo Playing test message...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$speak = New-Object -ComObject SAPI.SpVoice; $speak.Speak('Hello! This is test number one. If you can hear this, your Windows text to speech is working correctly!', 1)"
echo.
echo Did you hear the voice?
echo - YES = Windows TTS is working!
echo - NO  = Check your speaker volume and audio drivers
echo.
pause
goto MENU

:TEST_VBCABLE
cls
echo ========================================
echo Test 2: VB-CABLE Routing
echo ========================================
echo.
echo This tests if you can hear TTS routed to VB-CABLE
echo.
echo IMPORTANT: This only works if you completed
echo the audio monitoring setup (Option 3 or 4)!
echo.
pause
echo.
echo Playing test message through VB-CABLE...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$speak = New-Object -ComObject SAPI.SpVoice; $speak.Speak('This is test number two. If you hear this on your speakers, your audio monitoring is configured correctly. Your Valorant teammates would also hear this!', 1)"
echo.
echo Did you hear the voice on your SPEAKERS?
echo.
echo - YES = Perfect! Audio monitoring works!
echo         You and teammates will hear TTS.
echo.
echo - NO  = Audio monitoring not enabled yet.
echo         Choose Option 3 or 4 to set it up.
echo.
pause
goto MENU

:SETUP_MANUAL
cls
echo ========================================
echo Setup Audio Monitoring (Manual)
echo ========================================
echo.
echo Opening Sound Control Panel...
start control mmsys.cpl
timeout /t 2 /nobreak >nul
echo.
echo ========================================
echo FOLLOW THESE STEPS:
echo ========================================
echo.
echo 1. Click the RECORDING tab
echo.
echo 2. Find: CABLE Output (VB-Audio Virtual Cable)
echo    (If not visible: right-click and Show Disabled Devices)
echo.
echo 3. RIGHT-CLICK on "CABLE Output"
echo.
echo 4. Select "Properties"
echo.
echo 5. Go to the "LISTEN" tab
echo.
echo 6. CHECK the box: [X] Listen to this device
echo.
echo 7. In "Playback through" dropdown:
echo    SELECT YOUR SPEAKERS/HEADPHONES
echo    (Where you normally hear game audio)
echo.
echo 8. Click APPLY, then OK
echo.
echo ========================================
echo.
echo After completing these steps, run Test 2
echo to verify everything is working!
echo.
pause
goto MENU

:SETUP_AUTO
cls
call AUTO_SETUP_MONITORING.bat
goto MENU

:CHECK_STATUS
cls
echo ========================================
echo Current Audio Configuration
echo ========================================
echo.
echo Checking VB-CABLE installation...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$devices = Get-PnpDevice -Class 'MEDIA' -Status OK | Where-Object { $_.FriendlyName -like '*CABLE*' }; if ($devices) { Write-Host '[OK] VB-CABLE is installed' -ForegroundColor Green; Write-Host ''; Write-Host 'Checking audio devices...' -ForegroundColor Cyan; Write-Host ''; Get-CimInstance Win32_SoundDevice | Where-Object { $_.Name -like '*CABLE*' } | ForEach-Object { Write-Host '  - ' $_.Name -ForegroundColor White; }; } else { Write-Host '[ERROR] VB-CABLE not found!' -ForegroundColor Red; Write-Host 'Install from: https://vb-audio.com/Cable/' -ForegroundColor Yellow; }"
echo.
echo ========================================
echo.
echo To check if "Listen to this device" is enabled:
echo - Open Sound Control Panel (Option 6)
echo - Recording tab - CABLE Output - Properties - Listen tab
echo.
pause
goto MENU

:OPEN_SOUND
cls
echo Opening Sound Control Panel...
start control mmsys.cpl
timeout /t 2 /nobreak >nul
echo.
echo Sound Control Panel opened!
echo.
echo Useful tabs:
echo - Playback: Your speakers/headphones
echo - Recording: CABLE Output (enable monitoring here!)
echo.
pause
goto MENU

:EXIT
cls
echo ========================================
echo Thank you for using ValVoice!
echo ========================================
echo.
echo For more help, see: COMPLETE_AUDIO_GUIDE.md
echo.
timeout /t 2 /nobreak >nul
exit

