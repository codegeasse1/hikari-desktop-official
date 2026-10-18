[Setup]
#include "version.iss"
AppId={{C626E83F-8C3A-4D78-B5B3-FA19FE223E0C}}
AppName=Hikari
AppVersion={#AppVersion}
AppPublisher=Hikari
AppPublisherURL=https://github.com/codegeasse1/hikari-desktop-official
AppSupportURL=https://github.com/codegeasse1/hikari-desktop-official
AppUpdatesURL=https://github.com/codegeasse1/hikari-desktop-official
DefaultDirName={autopf}\Hikari
DefaultGroupName=Hikari
AllowNoIcons=yes
UsePreviousAppDir=yes
SetupIconFile=..\desktop-app\src\main\resources\app_icon.ico
UninstallDisplayIcon={app}\Hikari-Desktop.exe
; Output directory for the compiled installer
OutputDir=..\desktop-app\build\outputs
OutputBaseFilename=Hikari-Setup
Compression=lzma2/ultra64
SolidCompression=yes
LZMAUseSeparateProcess=yes
ArchitecturesAllowed=x64
ArchitecturesInstallIn64BitMode=x64
TimeStampsInUTC=yes

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
; Copy all files and folders from the AppImage output
Source: "..\desktop-app\build\compose\binaries\main\app\Hikari-Desktop\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\Hikari"; Filename: "{app}\Hikari-Desktop.exe"
Name: "{group}\{cm:UninstallProgram,Hikari}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\Hikari"; Filename: "{app}\Hikari-Desktop.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\Hikari-Desktop.exe"; Description: "{cm:LaunchProgram,Hikari}"; Flags: nowait postinstall skipifsilent runasoriginaluser
