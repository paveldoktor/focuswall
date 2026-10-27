; Установщик FocusWall для Windows (Inno Setup 6). Ставится без прав администратора.
#define AppVersion GetEnv("FOCUSWALL_VERSION")
#if AppVersion == ""
  #define AppVersion "0.1.0"
#endif

[Setup]
AppId={{7C4B6A52-3F0E-4D6B-9E4B-6B1F2D9A51C3}
AppName=FocusWall
AppVersion={#AppVersion}
AppPublisher=FocusWall
DefaultDirName={localappdata}\Programs\FocusWall
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=..\dist
OutputBaseFilename=FocusWall-Setup
SetupIconFile=FocusWall.Desktop\app.ico
UninstallDisplayIcon={app}\FocusWall.exe
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
CloseApplications=force

[Languages]
Name: "russian"; MessagesFile: "compiler:Languages\Russian.isl"

[Files]
Source: "publish\FocusWall.exe"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{userprograms}\FocusWall"; Filename: "{app}\FocusWall.exe"

[Run]
Filename: "{app}\FocusWall.exe"; Description: "Запустить FocusWall"; Flags: nowait postinstall

[UninstallRun]
Filename: "schtasks.exe"; Parameters: "/Delete /F /TN ""FocusWall Watchdog"""; Flags: runhidden; RunOnceId: "DelWatchdog"
Filename: "taskkill.exe"; Parameters: "/F /IM FocusWall.exe"; Flags: runhidden; RunOnceId: "KillApp"

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueName: "FocusWall"; Flags: uninsdeletevalue dontcreatekey
