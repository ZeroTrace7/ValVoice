@echo off
REM Stop any running Java processes (ValVoice)
tasklist /FI "IMAGENAME eq java.exe" 2>NUL | find /I /N "java.exe">NUL
if "%ERRORLEVEL%"=="0" (
    echo Stopping running ValVoice instances...
    taskkill /F /IM java.exe >nul 2>&1
    timeout /t 2 /nobreak >nul
)

REM Start ValVoice with the new compiled version
echo Starting ValVoice with VB-Cable fix...
cd /d "%~dp0"
java -jar target\valvoice-1.0.0.jar

