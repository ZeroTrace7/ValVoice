@echo off
echo ========================================
echo ValVoice - Automated VB-CABLE Monitoring Setup
echo ========================================
echo.
echo This script will attempt to automatically enable
echo "Listen to this device" on CABLE Output
echo so you can hear TTS on your speakers!
echo.
pause

echo.
echo Attempting automatic setup via PowerShell...
echo.

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
"$ErrorActionPreference='Continue'; ^
try { ^
    Write-Host 'Step 1: Checking for VB-CABLE...' -ForegroundColor Cyan; ^
    $devices = Get-PnpDevice -Class 'MEDIA' -Status OK | Where-Object { $_.FriendlyName -like '*CABLE*' }; ^
    if (-not $devices) { ^
        Write-Host 'ERROR: VB-CABLE not found!' -ForegroundColor Red; ^
        Write-Host 'Please install VB-Audio Virtual Cable from: https://vb-audio.com/Cable/' -ForegroundColor Yellow; ^
        exit 1; ^
    } ^
    Write-Host 'VB-CABLE detected!' -ForegroundColor Green; ^
    Write-Host ''; ^
    Write-Host 'Step 2: Enabling audio monitoring via registry...' -ForegroundColor Cyan; ^
    $regPath = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\MMDevices\Audio\Capture'; ^
    $cableKeys = Get-ChildItem -Path $regPath -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.GetValue('DeviceName') -like '*CABLE Output*' }; ^
    if ($cableKeys) { ^
        foreach ($key in $cableKeys) { ^
            $listenPath = Join-Path $key.PSPath 'Properties'; ^
            try { ^
                Set-ItemProperty -Path $listenPath -Name '{24DBB0FC-9311-4B3D-9CF0-18FF155639D4},0' -Value 1 -ErrorAction SilentlyContinue; ^
                Write-Host 'Registry key updated (may require restart)' -ForegroundColor Yellow; ^
            } catch { ^
                Write-Host 'Could not modify registry automatically' -ForegroundColor Yellow; ^
            } ^
        } ^
    } ^
    Write-Host ''; ^
    Write-Host 'Step 3: Opening Sound Control Panel for manual verification...' -ForegroundColor Cyan; ^
    Start-Process 'control' 'mmsys.cpl'; ^
    Start-Sleep -Seconds 1; ^
    Write-Host ''; ^
    Write-Host '========================================' -ForegroundColor Yellow; ^
    Write-Host 'MANUAL VERIFICATION REQUIRED:' -ForegroundColor Yellow; ^
    Write-Host '========================================' -ForegroundColor Yellow; ^
    Write-Host ''; ^
    Write-Host 'The Sound Control Panel should now be open.' -ForegroundColor White; ^
    Write-Host ''; ^
    Write-Host '1. Click the RECORDING tab' -ForegroundColor Cyan; ^
    Write-Host ''; ^
    Write-Host '2. Find: CABLE Output (VB-Audio Virtual Cable)' -ForegroundColor Cyan; ^
    Write-Host '   (If you do not see it, right-click and Show Disabled Devices)' -ForegroundColor Gray; ^
    Write-Host ''; ^
    Write-Host '3. RIGHT-CLICK on CABLE Output' -ForegroundColor Cyan; ^
    Write-Host ''; ^
    Write-Host '4. Select Properties' -ForegroundColor Cyan; ^
    Write-Host ''; ^
    Write-Host '5. Go to the LISTEN tab' -ForegroundColor Cyan; ^
    Write-Host ''; ^
    Write-Host '6. CHECK the box: [X] Listen to this device' -ForegroundColor Green; ^
    Write-Host ''; ^
    Write-Host '7. In Playback through dropdown: Select your SPEAKERS' -ForegroundColor Green; ^
    Write-Host '   (Your normal audio output device where you hear games)' -ForegroundColor Gray; ^
    Write-Host ''; ^
    Write-Host '8. Click APPLY then OK' -ForegroundColor Cyan; ^
    Write-Host ''; ^
    Write-Host '========================================' -ForegroundColor Yellow; ^
    Write-Host ''; ^
    Write-Host 'After this setup:' -ForegroundColor White; ^
    Write-Host '  YOU will hear TTS on your speakers' -ForegroundColor Green; ^
    Write-Host '  TEAMMATES will hear TTS in Valorant' -ForegroundColor Green; ^
    Write-Host '  GAME AUDIO will work normally' -ForegroundColor Green; ^
    Write-Host ''; ^
    Write-Host '========================================' -ForegroundColor Yellow; ^
    Write-Host ''; ^
    Write-Host 'Press any key when complete...' -ForegroundColor White; ^
} catch { ^
    Write-Host ''; ^
    Write-Host 'Automated setup failed: ' $_.Exception.Message -ForegroundColor Red; ^
    Write-Host ''; ^
    Write-Host 'Opening Sound Control Panel for manual setup...' -ForegroundColor Yellow; ^
    Start-Process 'control' 'mmsys.cpl'; ^
    Write-Host 'Follow the manual steps in COMPLETE_AUDIO_GUIDE.md' -ForegroundColor Yellow; ^
}"

echo.
echo ========================================
echo After completing the manual steps above,
echo test with: TEST_TTS_SPEAKERS.bat
echo ========================================
echo.
pause

