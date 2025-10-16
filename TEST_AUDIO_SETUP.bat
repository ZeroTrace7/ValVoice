@echo off
echo ========================================
echo Testing AudioDeviceCmdlets Installation
echo ========================================
echo.

REM Test if AudioDeviceCmdlets module is installed
echo Checking AudioDeviceCmdlets installation...
powershell -NoProfile -ExecutionPolicy Bypass -Command "if (Get-Module -ListAvailable -Name AudioDeviceCmdlets) { Write-Host 'SUCCESS: AudioDeviceCmdlets is installed' -ForegroundColor Green; Get-Module -ListAvailable -Name AudioDeviceCmdlets | Select-Object Name, Version } else { Write-Host 'FAILED: AudioDeviceCmdlets not installed' -ForegroundColor Red; Write-Host 'Installing now...' -ForegroundColor Yellow; Install-PackageProvider -Name NuGet -MinimumVersion 2.8.5.201 -Force; Install-Module -Name AudioDeviceCmdlets -Scope CurrentUser -Force -AllowClobber; Write-Host 'Installation complete!' -ForegroundColor Green }"

echo.
echo ========================================
echo Testing VB-Cable Detection
echo ========================================
echo.

REM Test if VB-Cable is detected
powershell -NoProfile -ExecutionPolicy Bypass -Command "Import-Module AudioDeviceCmdlets -ErrorAction SilentlyContinue; $devices = Get-AudioDevice -List | Where-Object { $_.Name -like '*CABLE*' }; if ($devices) { Write-Host 'SUCCESS: VB-Cable devices found:' -ForegroundColor Green; $devices | Select-Object Name, Type, Default | Format-Table } else { Write-Host 'WARNING: No VB-Cable devices found' -ForegroundColor Yellow }"

echo.
echo ========================================
echo Complete! Press any key to continue...
echo ========================================
pause >nul

