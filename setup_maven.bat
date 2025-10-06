@echo off
REM Automated Maven installation and PATH setup for Windows
set MAVEN_VERSION=3.9.6
set MAVEN_DIR="C:\Program Files\Apache\Maven"
set MAVEN_BIN="C:\Program Files\Apache\Maven\bin"

REM Download Maven
powershell -Command "Invoke-WebRequest -Uri https://dlcdn.apache.org/maven/maven-3/%MAVEN_VERSION%/binaries/apache-maven-%MAVEN_VERSION%-bin.zip -OutFile maven.zip"
REM Extract Maven
powershell -Command "Expand-Archive -Path maven.zip -DestinationPath 'C:\Program Files\Apache'"
move "C:\Program Files\Apache\apache-maven-%MAVEN_VERSION%" "C:\Program Files\Apache\Maven"

REM Add Maven to PATH
setx PATH "%PATH%;%MAVEN_BIN%"

REM Clean up
if exist maven.zip del maven.zip

echo Maven installed. Open a new Command Prompt and run: mvn -version
pause
ValVoice Project Setup and Run Instructions
=========================================

1. Install Java (JDK 21 or newer)
   - Download from https://adoptium.net or https://jdk.java.net
   - Add JAVA_HOME to your system environment variables.

2. Install Maven
   - Download Maven from https://maven.apache.org/download.cgi
   - Extract to C:\Program Files\Apache\Maven
   - Add C:\Program Files\Apache\Maven\bin to your system PATH.
   - Open a new Command Prompt and run: mvn -version
     You should see Maven version info.

3. Build the Project
   - Open Command Prompt and run:
     cd C:\Users\HP\IdeaProjects\ValVoice
     mvn clean package

4. Run the Application
   - After building, run:
     java -jar target\ValVoice.jar
   - Or run from IntelliJ IDEA: Right-click Main.java > Run 'Main'.

5. Troubleshooting
   - If you see 'ClassNotFoundException', check that Main.class exists in target\classes\com\someone\valvoice\
   - If you see JavaFX errors, ensure JavaFX dependencies are in pom.xml and your Java version matches.
   - If you see config.properties errors, ensure src\main\resources\com\someone\valvoice\config.properties exists and contains:
     version=1.0
     buildTimestamp=2025-10-04

6. Maven Setup Script (Windows)
   - See setup_maven.bat in this folder for automated Maven installation and PATH setup.

