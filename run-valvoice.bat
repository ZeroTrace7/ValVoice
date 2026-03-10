@echo off
:: ============================================
:: ValVoice — Silent Windows Launcher
:: ============================================
:: Starts ValVoice in the background with no console window.
:: Uses javaw (windowless JVM) for clean desktop integration.
::
:: Requirements:
::   - Java 17+ installed and on PATH
::   - VB-Audio Virtual Cable installed
::   - SoundVolumeView.exe in this directory
::   - engine/ directory with XTTS backend
::
:: Usage: Double-click this file or run from command line.
:: ============================================

title ValVoice Launcher

:: Check if Java is available
where javaw >nul 2>nul
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Java not found. Please install Java 17 or later.
    echo Download from: https://adoptium.net/
    pause
    exit /b 1
)

:: Launch ValVoice silently (no console window)
start "" javaw -Xms256m -Xmx1024m -jar valvoice-1.0.0.jar

exit

