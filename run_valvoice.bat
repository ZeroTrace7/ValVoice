@echo off
setlocal
cd /d %~dp0

if not exist target\valvoice-1.0.0.jar (
  echo Building ValVoice...
  mvn -DskipTests package
  if errorlevel 1 (
    echo Build failed.
    exit /b 1
  )
)

java -jar target\valvoice-1.0.0.jar

