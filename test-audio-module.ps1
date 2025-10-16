Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Testing AudioDeviceCmdlets Installation" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Step 1: Check if module is installed
Write-Host "Step 1: Checking if AudioDeviceCmdlets is installed..." -ForegroundColor Yellow
$module = Get-Module -ListAvailable -Name AudioDeviceCmdlets

if ($module) {
    Write-Host "SUCCESS: AudioDeviceCmdlets is installed!" -ForegroundColor Green
    Write-Host "Version: $($module.Version)" -ForegroundColor Gray
    Write-Host "Path: $($module.Path)" -ForegroundColor Gray
    Write-Host ""

    # Step 2: Try to import the module
    Write-Host "Step 2: Importing AudioDeviceCmdlets module..." -ForegroundColor Yellow
    try {
        Import-Module AudioDeviceCmdlets -ErrorAction Stop
        Write-Host "SUCCESS: Module imported successfully!" -ForegroundColor Green
        Write-Host ""

        # Step 3: Test VB-Cable detection
        Write-Host "Step 3: Detecting VB-Cable devices..." -ForegroundColor Yellow
        $devices = Get-AudioDevice -List | Where-Object { $_.Name -like '*CABLE*' }

        if ($devices) {
            Write-Host "SUCCESS: VB-Cable devices found!" -ForegroundColor Green
            Write-Host ""
            $devices | Format-Table Name, Type, Default -AutoSize
            Write-Host ""
            Write-Host "RESULT: Everything is working perfectly!" -ForegroundColor Green
            exit 0
        } else {
            Write-Host "WARNING: AudioDeviceCmdlets works, but no CABLE devices found" -ForegroundColor Yellow
            Write-Host "Checking via Win32_SoundDevice fallback..." -ForegroundColor Yellow
            $soundDevices = Get-CimInstance Win32_SoundDevice | Select-Object -ExpandProperty Name
            $foundVB = $soundDevices | Where-Object { $_ -like '*VB-Audio*' -and $_ -like '*Cable*' }
            if ($foundVB) {
                Write-Host "VB-Audio Virtual Cable found via Win32_SoundDevice:" -ForegroundColor Green
                $foundVB | ForEach-Object { Write-Host "  - $_" -ForegroundColor Gray }
            }
        }
    } catch {
        Write-Host "ERROR: Failed to import AudioDeviceCmdlets module" -ForegroundColor Red
        Write-Host $_.Exception.Message -ForegroundColor Red
        exit 1
    }
} else {
    Write-Host "FAILED: AudioDeviceCmdlets is NOT installed" -ForegroundColor Red
    Write-Host ""
    Write-Host "Installing AudioDeviceCmdlets now..." -ForegroundColor Yellow

    try {
        Install-PackageProvider -Name NuGet -MinimumVersion 2.8.5.201 -Force -ErrorAction Stop
        Write-Host "NuGet provider installed" -ForegroundColor Gray

        Install-Module -Name AudioDeviceCmdlets -Scope CurrentUser -Force -AllowClobber -ErrorAction Stop
        Write-Host "SUCCESS: AudioDeviceCmdlets installed!" -ForegroundColor Green
        Write-Host ""
        Write-Host "Please run this script again to verify." -ForegroundColor Yellow
        exit 0
    } catch {
        Write-Host "ERROR: Failed to install AudioDeviceCmdlets" -ForegroundColor Red
        Write-Host $_.Exception.Message -ForegroundColor Red
        exit 1
    }
}

