@echo off
setlocal enableextensions
cd /d %~dp0

echo ========================================
echo ValVoice - Start with Clean Rebuild and Audio Fixes
echo ========================================
echo.

echo Stopping any running instances...
tasklist /FI "IMAGENAME eq java.exe" 2>NUL | find /I /N "java.exe">NUL
if "%ERRORLEVEL%"=="0" (
    taskkill /F /IM java.exe >nul 2>&1
    echo Stopped running Java processes
    timeout /t 2 /nobreak >nul
) else (
    echo No running instances found
)

echo.
echo ========================================
echo Forcing clean rebuild to ensure latest code...
echo ========================================
echo.

REM Clean target folder fully
if exist target (
    echo Deleting previous build artifacts...
    rmdir /s /q target 2>nul
)

REM Clean build with Maven
echo Running Maven clean package...
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
echo Build complete! Starting ValVoice...
echo Expected result: VB-Cable detected, TTS-only routing active.
echo ========================================
echo.

REM Run the newly built JAR
java -jar target\valvoice-1.0.0.jar
set APP_EXIT_CODE=%ERRORLEVEL%

echo.
echo ========================================
echo App closed (exit code %APP_EXIT_CODE%). Performing audio cleanup...
echo ========================================
echo.

REM Optional: Clear any app-specific routing for powershell.exe back to Default via SoundVolumeView
if exist "%cd%\SoundVolumeView.exe" (
    echo Resetting PowerShell app-specific routing to Default using SoundVolumeView...
    "%cd%\SoundVolumeView.exe" /SetAppDefault "Default" all "powershell.exe"
) else (
    echo SoundVolumeView.exe not found in working directory; skipping explicit routing reset.
)

echo Done.
pause
exit /b %APP_EXIT_CODE%
