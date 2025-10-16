@echo off
setlocal
cd /d %~dp0

echo ========================================
echo ValVoice - Starting with Clean Build
echo ========================================
echo.

REM Stop any running instances first
echo Checking for running instances...
tasklist /FI "IMAGENAME eq java.exe" 2>NUL | find /I /N "java.exe">NUL
if "%ERRORLEVEL%"=="0" (
    echo Stopping running Java processes...
    taskkill /F /IM java.exe >nul 2>&1
    timeout /t 2 /nobreak >nul
    echo Stopped.
) else (
    echo No running instances found.
)

echo.
echo ========================================
echo Forcing clean rebuild with all fixes...
echo ========================================
echo.

REM Always force a clean rebuild to ensure latest code
echo Cleaning previous build...
if exist target\valvoice-1.0.0.jar (
    del /F /Q target\valvoice-1.0.0.jar
)

echo Building ValVoice with Maven...
call mvn clean package -DskipTests

if errorlevel 1 (
    echo.
    echo ========================================
    echo ERROR: Build failed!
    echo ========================================
    echo Please check the error messages above.
    pause
    exit /b 1
)

echo.
echo ========================================
echo Build successful! Starting ValVoice...
echo ========================================
echo.
echo Expected results:
echo   - VB-Cable detected
echo   - Audio routing configured
echo   - XMPP connected
echo.

java -jar target\valvoice-1.0.0.jar
