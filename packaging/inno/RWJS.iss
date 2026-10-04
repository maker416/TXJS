; Copyright 2023-2025 RWPP contributors
; 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
; Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
; https://github.com/Minxyzgo/RWPP/blob/main/LICENSE

#ifndef AppVersion
  #error AppVersion must be supplied by package.ps1
#endif
#ifndef AppSource
  #error AppSource must be supplied by package.ps1
#endif
#ifndef GameSource
  #error GameSource must be supplied by package.ps1
#endif
#ifndef RepoRoot
  #error RepoRoot must be supplied by package.ps1
#endif
#ifndef InstallerOutput
  #error InstallerOutput must be supplied by package.ps1
#endif

[Setup]
; 全新的 RWJS 身份；发布后不要更换 AppId。
AppId={{C9F7D3ED-E974-4C19-9291-9D5460608316}
AppName=铁锈战争极速版 RWJS
AppVersion={#AppVersion}
AppPublisher=RWJS Contributors
AppPublisherURL=https://gitee.com/maker416/TXJS
AppSupportURL=https://gitee.com/maker416/TXJS/issues
AppUpdatesURL=https://gitee.com/maker416/TXJS/releases
DefaultDirName={autopf}\RWJS
DefaultGroupName=铁锈战争极速版 RWJS
DisableProgramGroupPage=yes
DisableWelcomePage=no
UsePreviousAppDir=yes
UsePreviousTasks=yes
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
OutputDir={#InstallerOutput}
OutputBaseFilename=RWJS-Setup
SetupIconFile={#RepoRoot}\rwpp-desktop\logo.ico
UninstallDisplayIcon={app}\RWJS.exe
LicenseFile={#RepoRoot}\LICENSE
WizardStyle=modern
Compression=lzma2
SolidCompression=yes
CloseApplications=yes
CloseApplicationsFilter=*.exe,*.dll,*.jar
RestartApplications=no
UninstallLogMode=append
SetupLogging=yes

[Languages]
Name: "chinesesimplified"; MessagesFile: "ChineseSimplified.isl"

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; GroupDescription: "快捷方式："

[Dirs]
; 引擎和配置仍写入游戏根目录，普通用户运行时需要写权限。
Name: "{app}"; Permissions: users-modify
Name: "{app}\mods\units"
Name: "{app}\mods\maps"

[Files]
Source: "{#AppSource}\RWJS.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#AppSource}\app\*"; DestDir: "{app}\app"; Excludes: "skiko-awt-runtime-macos-*,skiko-awt-runtime-linux-*"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#AppSource}\runtime\*"; DestDir: "{app}\runtime"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#GameSource}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#RepoRoot}\rwpp-desktop\logo.ico"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#RepoRoot}\LICENSE"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\铁锈战争极速版 RWJS"; Filename: "{app}\RWJS.exe"; WorkingDir: "{app}"; IconFilename: "{app}\logo.ico"
Name: "{autodesktop}\铁锈战争极速版 RWJS"; Filename: "{app}\RWJS.exe"; WorkingDir: "{app}"; IconFilename: "{app}\logo.ico"; Tasks: desktopicon

[Registry]
Root: HKLM64; Subkey: "SOFTWARE\RWJS"; ValueType: string; ValueName: "InstallDir"; ValueData: "{app}"; Flags: uninsdeletevalue uninsdeletekeyifempty
Root: HKLM64; Subkey: "SOFTWARE\RWJS"; ValueType: string; ValueName: "InstalledVersion"; ValueData: "{#AppVersion}"; Flags: uninsdeletevalue uninsdeletekeyifempty

[InstallDelete]
; jpackage 的依赖 jar 可能随版本改名；仅清理安装器管理的 app jar。
Type: files; Name: "{app}\app\*.jar"

[Run]
Filename: "{app}\RWJS.exe"; WorkingDir: "{app}"; Description: "启动铁锈战争极速版 RWJS"; Flags: nowait postinstall skipifsilent runasoriginaluser

[Code]
const
  InstallKey = 'SOFTWARE\RWJS';
var
  UpdateMode: Boolean;
  InstalledDir: String;

function LooksLikeInstall(const Dir: String): Boolean;
begin
  Result := (Dir <> '') and FileExists(AddBackslash(Dir) + 'RWJS.exe') and
    FileExists(AddBackslash(Dir) + 'app\RWJS.cfg') and
    FileExists(AddBackslash(Dir) + 'unins000.exe');
end;

function InitializeSetup: Boolean;
begin
  UpdateMode := ExpandConstant('{param:RWJS_UPDATE|0}') = '1';
  RegQueryStringValue(HKLM64, InstallKey, 'InstallDir', InstalledDir);
  Result := True;
  if UpdateMode and not LooksLikeInstall(InstalledDir) then
  begin
    SuppressibleMsgBox('未找到有效的 RWJS 安装信息，请先运行安装包完成正常安装。',
      mbError, MB_OK, IDOK);
    Result := False;
  end;
end;

procedure InitializeWizard;
begin
  if UpdateMode then
    WizardForm.DirEdit.Text := InstalledDir;
end;

function ShouldSkipPage(PageID: Integer): Boolean;
begin
  Result := UpdateMode and ((PageID = wpWelcome) or (PageID = wpLicense) or
    (PageID = wpSelectDir) or (PageID = wpSelectTasks) or (PageID = wpReady));
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  Result := '';
  // 自动更新只允许覆盖注册表指向的 RWJS 安装，拒绝 /DIR 重定向。
  if UpdateMode and (CompareText(RemoveBackslashUnlessRoot(ExpandConstant('{app}')),
    RemoveBackslashUnlessRoot(InstalledDir)) <> 0) then
    Result := '更新路径与 RWJS 注册表记录不一致，请重新运行安装包。';
  if not UpdateMode and LooksLikeInstall(InstalledDir) and
    (CompareText(RemoveBackslashUnlessRoot(ExpandConstant('{app}')),
      RemoveBackslashUnlessRoot(InstalledDir)) <> 0) then
    Result := '已安装 RWJS。如需更换安装目录，请先卸载已安装的 RWJS。';
end;

// 不使用 [UninstallDelete] 通配清理：用户配置、存档、回放及自行添加的模组保留。
