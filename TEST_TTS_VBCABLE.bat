@echo off
echo ========================================
echo Testing TTS Routed to VB-CABLE
echo ========================================
echo.
echo This simulates what ValVoice does:
echo - TTS audio goes to CABLE Input
echo - You should hear it IF you enabled "Listen to this device"
echo - Your Valorant teammates would hear this through voice chat
echo.
pause

echo.
echo Playing TTS through VB-CABLE Input...
echo.

REM Note: This test assumes PowerShell is already routed to VB-CABLE by ValVoice
REM If you haven't run ValVoice yet, this will play through default speakers instead

powershell -NoProfile -ExecutionPolicy Bypass -Command "$speak = New-Object -ComObject SAPI.SpVoice; $speak.Speak('This is a test message sent to VB-CABLE. If you hear this on your speakers, your audio monitoring is configured correctly. Your Valorant teammates would also hear this through voice chat!', 1)"

echo.
echo ========================================
echo Test complete!
echo.
echo Did you hear the voice on your SPEAKERS?
echo.
echo - YES = Perfect! Audio monitoring is working!
echo          You will hear TTS, teammates will too.
echo.
echo - NO  = You need to enable "Listen to this device"
echo         Run: SETUP_AUDIO_MONITORING.bat for help
echo.
echo Check Sound Control Panel - Recording tab:
echo CABLE Output should show green audio bars when TTS plays.
echo ========================================
pause
