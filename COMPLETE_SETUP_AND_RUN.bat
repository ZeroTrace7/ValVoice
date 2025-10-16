@echo off
echo ========================================
echo ValVoice Complete Setup with AudioDeviceCmdlets
echo ========================================
echo.

echo Step 1: Verifying AudioDeviceCmdlets installation...
echo.
powershell -NoProfile -ExecutionPolicy Bypass -Command "if (Get-Module -ListAvailable -Name AudioDeviceCmdlets) { Write-Host 'SUCCESS: AudioDeviceCmdlets is already installed' -ForegroundColor Green; Get-Module -ListAvailable -Name AudioDeviceCmdlets | Select-Object Name, Version, Path | Format-List } else { Write-Host 'AudioDeviceCmdlets not found. Installing now...' -ForegroundColor Yellow; Install-PackageProvider -Name NuGet -MinimumVersion 2.8.5.201 -Force -ErrorAction SilentlyContinue; Install-Module -Name AudioDeviceCmdlets -Scope CurrentUser -Force -AllowClobber; Write-Host 'Installation complete!' -ForegroundColor Green }"

echo.
echo Step 2: Testing VB-Cable detection...
echo.
powershell -NoProfile -ExecutionPolicy Bypass -Command "try { Import-Module AudioDeviceCmdlets -ErrorAction Stop; Write-Host 'AudioDeviceCmdlets module loaded successfully' -ForegroundColor Green; Write-Host ''; Write-Host 'Searching for VB-Cable devices...' -ForegroundColor Cyan; $devices = Get-AudioDevice -List | Where-Object { $_.Name -like '*CABLE*' }; if ($devices) { Write-Host 'SUCCESS: Found VB-Cable devices:' -ForegroundColor Green; $devices | Select-Object Name, Type, Default | Format-Table -AutoSize } else { Write-Host 'WARNING: No VB-Cable devices found via AudioDeviceCmdlets' -ForegroundColor Yellow; Write-Host 'Checking via Win32_SoundDevice (fallback)...' -ForegroundColor Cyan; $soundDevices = Get-CimInstance Win32_SoundDevice | Select-Object -ExpandProperty Name; $foundVB = $soundDevices | Where-Object { $_ -like '*VB-Audio*' -and $_ -like '*Cable*' }; if ($foundVB) { Write-Host 'SUCCESS: VB-Audio Virtual Cable found via Win32_SoundDevice' -ForegroundColor Green; $foundVB | ForEach-Object { Write-Host \"  - $_\" } } else { Write-Host 'ERROR: VB-Audio Virtual Cable not detected at all!' -ForegroundColor Red } } } catch { Write-Host 'ERROR: Failed to load AudioDeviceCmdlets module' -ForegroundColor Red; Write-Host $_.Exception.Message -ForegroundColor Red }"

echo.
echo Step 3: Stopping any running ValVoice instances...
echo.
tasklist /FI "IMAGENAME eq java.exe" 2>NUL | find /I /N "java.exe">NUL
if "%ERRORLEVEL%"=="0" (
    echo Found running Java processes. Stopping them...
    taskkill /F /IM java.exe >nul 2>&1
    timeout /t 2 /nobreak >nul
    echo Java processes stopped.
) else (
    echo No running Java instances found.
)

echo.
echo Step 4: Rebuilding ValVoice with all fixes...
echo.
call mvn clean package -DskipTests
if errorlevel 1 (
    echo.
    echo ========================================
    echo ERROR: Build failed!
    echo ========================================
    pause
    exit /b 1
)

echo.
echo ========================================
echo Setup Complete! Starting ValVoice...
echo ========================================
echo.
echo Expected results:
echo   1. VB-Cable DETECTED by ValVoiceController
echo   2. VB-Cable DETECTED by AudioRouter
echo   3. Audio routing SUCCESS (via AudioDeviceCmdlets)
echo   4. TTS routed to VB-Cable automatically
echo.
echo Starting application...
echo.

java -jar target\valvoice-1.0.0.jar

pause

