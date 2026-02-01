; ValVoice Installer Script
; Built with Inno Setup
; Mirrors ValorantNarrator installer behavior exactly

#define MyAppName "ValVoice"
#define MyAppVersion "1.0.0"
#define MyAppPublisher "ValVoice"
#define MyAppURL "https://github.com/valvoice"
#define MyAppExeName "valvoice-1.0.0.jar"

[Setup]
; NOTE: The value of AppId uniquely identifies this application.
AppId={{A1B2C3D4-E5F6-7890-ABCD-EF1234567890}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
AppSupportURL={#MyAppURL}
AppUpdatesURL={#MyAppURL}
DefaultDirName={pf}\{#MyAppName}
DefaultGroupName={#MyAppName}
AllowNoIcons=yes
OutputDir=..\target
OutputBaseFilename=ValVoice-{#MyAppVersion}-Setup
Compression=lzma
SolidCompression=yes
PrivilegesRequired=admin

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
; Main application JAR
Source: "..\target\valvoice-1.0.0.jar"; DestDir: "{app}"; Flags: ignoreversion

; MITM executable (bundled with Node runtime)
Source: "..\mitm\valvoice-mitm.exe"; DestDir: "{app}"; Flags: ignoreversion

; Audio routing utility
Source: "..\SoundVolumeView.exe"; DestDir: "{app}"; Flags: ignoreversion

; Documentation
Source: "..\README.md"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
; Start Menu shortcut - launches Java JAR directly
Name: "{group}\{#MyAppName}"; Filename: "javaw.exe"; Parameters: "-jar ""{app}\{#MyAppExeName}"""; WorkingDir: "{app}"
Name: "{group}\Uninstall {#MyAppName}"; Filename: "{uninstallexe}"

; Desktop shortcut (optional)
Name: "{commondesktop}\{#MyAppName}"; Filename: "javaw.exe"; Parameters: "-jar ""{app}\{#MyAppExeName}"""; WorkingDir: "{app}"; Tasks: desktopicon

[Run]
; Option to run app after install
Filename: "javaw.exe"; Parameters: "-jar ""{app}\{#MyAppExeName}"""; WorkingDir: "{app}"; Description: "{cm:LaunchProgram,{#StringChange(MyAppName, '&', '&&')}}"; Flags: nowait postinstall skipifsilent
