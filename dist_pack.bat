@echo off
setlocal
cd /d %~dp0

set DIST=dist
if exist %DIST% rmdir /s /q %DIST%
mkdir %DIST% || goto :eof

echo Building ValVoice JAR...
mvn -DskipTests package || goto :eof

echo Copying files to %DIST%...
copy /y target\valvoice-1.0.0.jar %DIST%\ >nul
if exist valvoice-xmpp.exe copy /y valvoice-xmpp.exe %DIST%\ >nul
copy /y run_valvoice.bat %DIST%\ >nul

echo NOTE:
echo  - Place SoundVolumeView.exe into the %DIST% folder (for auto audio routing).
echo  - Install VB-Audio Virtual Cable and set Valorant mic to 'CABLE Output'.
echo  - If valvoice-xmpp.exe is missing, start the app once to auto-build it ^(requires Node.js/npm^).

echo Done. See the 'dist' folder.
