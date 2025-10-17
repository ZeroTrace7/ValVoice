@echo off
echo ========================================
echo Testing TTS on Your Speakers
echo ========================================
echo.
echo This will play TTS through your DEFAULT audio device (speakers/headphones)
echo NOT through VB-CABLE!
echo.
pause

powershell -NoProfile -ExecutionPolicy Bypass -Command "$speak = New-Object -ComObject SAPI.SpVoice; $speak.Speak('Hello! This is a test. If you can hear this, your text to speech is working perfectly!', 1)"

echo.
echo ========================================
echo Test complete!
echo.
echo Did you hear the voice?
echo - YES = TTS is working! Audio just goes to VB-CABLE in ValVoice.
echo - NO  = TTS may have an issue.
echo ========================================
pause

