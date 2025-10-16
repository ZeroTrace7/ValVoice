@echo off
echo ========================================
echo ValVoice - VB-Cable Fix Applied
echo ========================================
echo.
echo Stopping any running instances...
tasklist /FI "IMAGENAME eq java.exe" 2>NUL | find /I /N "java.exe">NUL
if "%ERRORLEVEL%"=="0" (
    taskkill /F /IM java.exe >nul 2>&1
    echo Stopped running Java processes
    timeout /t 3 /nobreak >nul
) else (
    echo No running instances found
)

echo.
echo ========================================
echo Forcing clean rebuild to ensure latest code...
echo ========================================
echo.

REM Delete old JAR to force rebuild
if exist target\valvoice-1.0.0.jar (
    echo Deleting old JAR file...
    del /F /Q target\valvoice-1.0.0.jar
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
echo Expected result: VB-Cable should be detected!
echo ========================================
echo.

REM Run the newly built JAR
java -jar target\valvoice-1.0.0.jar

pause
