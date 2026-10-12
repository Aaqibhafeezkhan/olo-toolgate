; Copyright 2026 OLO Labs
; SPDX-License-Identifier: Apache-2.0
[Setup]
AppId=OLO.ToolGate.Client
AppName=OLO ToolGate Client
AppVersion={#ProductVersion}
AppPublisher=OLO Labs
LicenseFile={#PayloadDirectory}\LICENSE
DefaultDirName={autopf}\OLO\ToolGateSetup
DisableDirPage=yes
DisableProgramGroupPage=yes
DisableWelcomePage=yes
DisableReadyPage=yes
DisableFinishedPage=yes
PrivilegesRequired=admin
ArchitecturesAllowed={#NativeArchitecture}
ArchitecturesInstallIn64BitMode={#NativeArchitecture}
OutputDir={#OutputDirectory}
OutputBaseFilename={#OutputName}
Compression=lzma2
SolidCompression=yes
UninstallDisplayName=OLO ToolGate Client
CloseApplications=yes

[Files]
Source: "{#PayloadDirectory}\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[Registry]
Root: HKLM; Subkey: "Software\Google\Chrome\NativeMessagingHosts\io.ololabs.toolgate.connect"; ValueType: string; ValueData: "{app}\browser-host.json"; Flags: uninsdeletekey
Root: HKLM32; Subkey: "Software\Google\Chrome\Extensions\emmemldedebhbloibichmmdlbpjakfkf"; ValueType: string; ValueName: "update_url"; ValueData: "https://clients2.google.com/service/update2/crx"; Check: StorePublished; Flags: uninsdeletekey
Root: HKLM; Subkey: "Software\OLO\ToolGate"; ValueType: string; ValueName: "ServerUrl"; ValueData: "{code:ServerUrl}"; Flags: uninsdeletevalue
Root: HKLM; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "OloToolGateTray"; ValueData: """{sys}\WindowsPowerShell\v1.0\powershell.exe"" -NoProfile -STA -ExecutionPolicy RemoteSigned -WindowStyle Hidden -File ""{app}\packaging\toolgate-tray.ps1"""; Flags: uninsdeletevalue

[Run]
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -STA -ExecutionPolicy RemoteSigned -WindowStyle Hidden -File ""{app}\packaging\toolgate-tray.ps1"""; Flags: nowait runasoriginaluser
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -STA -ExecutionPolicy RemoteSigned -WindowStyle Hidden -File ""{app}\packaging\toolgate-enroll.ps1"""; Flags: nowait runasoriginaluser skipifsilent

[UninstallDelete]
; These manifests are generated after installation and have no [Files] entry.
Type: files; Name: "{app}\browser-host.json"
Type: files; Name: "{app}\browser-host.uninstalling.json"

[Code]
#include "windows-context.iss"
var MaintenancePage: TInputOptionWizardPage;
    GatewayPage: TInputQueryWizardPage;
    ConfiguredServer, SetupPeer, SetupSource, HostManifestBackup: String; ExistingClient, NamedServer, MaintenanceComplete, SetupCompleted: Boolean;
function StorePublished: Boolean;
begin
  Result := '{#ChromeStoreUrl}' <> '';
end;
function ServerUrl(Param: String): String;
begin
  Result := ConfiguredServer;
end;
function RegisteredUninstaller: String;
var Command: String;
begin
  Result := '';
  if not RegQueryStringValue(HKLM64, 'Software\Microsoft\Windows\CurrentVersion\Uninstall\OLO.ToolGate.Client_is1', 'UninstallString', Command) then
    if not RegQueryStringValue(HKLM32, 'Software\Microsoft\Windows\CurrentVersion\Uninstall\OLO.ToolGate.Client_is1', 'UninstallString', Command) then Exit;
  Command := RemoveQuotes(Trim(Command));
  if (CompareText(ExtractFileDir(Command), ExpandConstant('{app}')) = 0) and
    (Pos('unins', Lowercase(ExtractFileName(Command))) = 1) and
    (CompareText(ExtractFileExt(Command), '.exe') = 0) and FileExists(Command) then Result := Command;
end;
procedure InitializeWizard;
begin
  ExistingClient := FileExists(ExpandConstant('{commonappdata}\OLO\ToolGate\client.json'));
  ConfiguredServer := ExpandConstant('{param:SERVER|}');
  if ConfiguredServer = '' then ConfiguredServer := ServerFromDownload;
  // A download from a console names its gateway: install and connect without further questions.
  NamedServer := ConfiguredServer <> '';
  if ExistingClient and (ConfiguredServer = '') then
    RegQueryStringValue(HKLM, 'Software\OLO\ToolGate', 'ServerUrl', ConfiguredServer);
  ConfiguredServer := SetupServer(ConfiguredServer);
  MaintenancePage := CreateInputOptionPage(wpWelcome, 'ToolGate is already installed',
    'Choose what to do with the existing client',
    'Reinstall preserves enrollment for the same gateway. Switching gateways starts a new enrollment. Uninstall removes the service, tray icon and all ToolGate data on this computer, including every gateway enrollment; only the device key is kept so approved gateways recognise this device after a reinstall.', True, False);
  MaintenancePage.Add('Repair / reinstall');
  MaintenancePage.Add('Uninstall');
  MaintenancePage.SelectedValueIndex := 0;
  GatewayPage := CreateInputQueryPage(MaintenancePage.ID, 'Gateway connection',
    'Choose the gateway for this computer',
    'Copy the Gateway URL shown on Enroll Device. For a local gateway, enter its console address (for example http://127.0.0.1:18091) so setup can trust its certificate. No credentials are required.');
  GatewayPage.Add('&Gateway URL (optional):', False);
  GatewayPage.Values[0] := ConfiguredServer;
end;
function ShouldSkipPage(PageID: Integer): Boolean;
begin
  Result := ((PageID = MaintenancePage.ID) and (not ExistingClient or NamedServer)) or
    ((PageID = GatewayPage.ID) and NamedServer);
end;
function NextButtonClick(CurPageID: Integer): Boolean;
var ExitCode: Integer; Uninstaller: String;
begin
  Result := True;
  if CurPageID = GatewayPage.ID then begin
    ConfiguredServer := SetupServer(GatewayPage.Values[0]);
    Result := ValidSetupServer(ConfiguredServer);
    if not Result then
      MsgBox('Enter a gateway URL, such as https://localhost:18450.', mbError, MB_OK);
  end;
  if ExistingClient and (CurPageID = MaintenancePage.ID) and (MaintenancePage.SelectedValueIndex = 1) then begin
    Result := False;
    Uninstaller := RegisteredUninstaller;
    if (Uninstaller <> '') and Exec(Uninstaller, '/SILENT /SUPPRESSMSGBOXES /NORESTART', '',
      SW_HIDE, ewWaitUntilTerminated, ExitCode) and (ExitCode = 0) then begin
      MaintenanceComplete := True;
      WizardForm.Close;
    end else MsgBox('Uninstallation failed. Check the Windows service logs.', mbError, MB_OK);
  end;
end;
procedure CancelButtonClick(CurPageID: Integer; var Cancel, Confirm: Boolean);
begin
  if MaintenanceComplete then begin Cancel := True; Confirm := False; end;
end;
function PrepareToInstall(var NeedsRestart: Boolean): String;
var ExitCode: Integer; Output: TExecOutput;
begin
  Result := '';
  SetupPeer := ExpandConstant('{param:PEER|}');
  if SetupPeer = '' then begin
    ExtractTemporaryFile('toolgate-setup-peer.ps1');
    if not ExecAndCaptureOutput(ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe'),
      '-NoProfile -ExecutionPolicy RemoteSigned -File "' + ExpandConstant('{tmp}\toolgate-setup-peer.ps1') + '"', '', SW_HIDE, ewWaitUntilTerminated, ExitCode, Output) or
      (ExitCode <> 0) or Output.Error or (GetArrayLength(Output.StdOut) <> 1) then begin
      Result := 'Could not identify the desktop account. Retry setup from your Windows desktop.';
      Exit;
    end;
    SetupPeer := Trim(Output.StdOut[0]);
  end;
  if not WizardSilent then ConfiguredServer := SetupServer(GatewayPage.Values[0]);
  SetupSource := ConfiguredServer;
  if not ValidSetupServer(ConfiguredServer) then
    Result := 'Enter a valid gateway URL, such as https://localhost:18450.'
  else begin
    ExtractTemporaryFile('olo-toolgate-client.exe');
    if not ExecAndCaptureOutput(ExpandConstant('{tmp}\olo-toolgate-client.exe'),
      'resolve-installation --server "' + ConfiguredServer + '"', '', SW_HIDE, ewWaitUntilTerminated, ExitCode, Output) or
      (ExitCode <> 0) or Output.Error or (GetArrayLength(Output.StdOut) <> 1) then
      Result := 'Could not verify the gateway connection. Start the local gateway container before setup, or enter its published HTTPS Gateway URL, then retry.'
    else if not ValidServer(Output.StdOut[0]) then
      Result := 'The console did not publish a valid HTTPS Gateway URL.'
    else ConfiguredServer := Output.StdOut[0];
  end;
  if (Result = '') and ExistingClient then begin
    HostManifestBackup := ExpandConstant('{app}\browser-host.updating.json');
    if FileExists(HostManifestBackup) then begin
      Result := 'Another client upgrade is pending. Finish that setup before retrying.';
      Exit;
    end;
    if FileExists(ExpandConstant('{app}\browser-host.json')) and
      not RenameFile(ExpandConstant('{app}\browser-host.json'), HostManifestBackup) then begin
      Result := 'Could not pause the Chrome bridge for upgrade.';
      Exit;
    end;
    ExtractTemporaryFile('toolgate-upgrade.ps1');
    if not Exec(ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe'),
      '-NoProfile -ExecutionPolicy RemoteSigned -File "' + ExpandConstant('{tmp}\toolgate-upgrade.ps1') + '" -InstalledDirectory "' + ExpandConstant('{app}') + '"', '', SW_HIDE, ewWaitUntilTerminated, ExitCode) or
      (ExitCode <> 0) then Result := 'Could not close the installed tray and Chrome bridge. Retry setup.';
  end;
end;
procedure CurStepChanged(CurStep: TSetupStep);
var ExitCode: Integer; HostPath, HostDocument, Operation, Parameters, Peer: String;
begin
  if CurStep = ssPostInstall then begin
    Operation := 'install';
    if ExistingClient then Operation := 'reinstall';
    Parameters := Operation + ' --server "' + SetupSource + '"';
    Peer := SetupPeer;
    if Peer <> '' then begin
      if (Pos('S-1-', Peer) <> 1) or (Pos('"', Peer) <> 0) or (Pos(' ', Peer) <> 0) then
        RaiseException('Invalid Windows account.');
      Parameters := Parameters + ' --peer "' + Peer + '"';
    end;
    if ExistingClient then begin
      if not Exec(ExpandConstant('{app}\olo-toolgate-client.exe'),
        'configure --server "' + SetupSource + '"', '', SW_HIDE, ewWaitUntilTerminated, ExitCode) or (ExitCode <> 0) then
        RaiseException('Could not update the existing gateway configuration.');
    end;
    if not Exec(ExpandConstant('{app}\olo-toolgate-client.exe'),
      Parameters, '', SW_HIDE, ewWaitUntilTerminated, ExitCode) then
      RaiseException('Could not start service installation.');
    if ExitCode <> 0 then RaiseException('Service installation failed. Check the client configuration and Windows service logs.');
    HostPath := ExpandConstant('{app}\olo-toolgate-browser-host.exe');
    StringChangeEx(HostPath, '\', '\\', True);
    HostDocument := '{"name":"io.ololabs.toolgate.connect","description":"ToolGate protected client bridge","path":"' + HostPath + '","type":"stdio","allowed_origins":["chrome-extension://emmemldedebhbloibichmmdlbpjakfkf/"]}';
    if not SaveStringToFile(ExpandConstant('{app}\browser-host.json'), HostDocument, False) then
      RaiseException('Could not register the Chrome client bridge.');
    if HostManifestBackup <> '' then DeleteFile(HostManifestBackup);
    SetupCompleted := True;
  end;
end;
procedure DeinitializeSetup;
var ExitCode: Integer;
begin
  if not SetupCompleted and (HostManifestBackup <> '') and FileExists(HostManifestBackup) then begin
    if not FileExists(ExpandConstant('{app}\browser-host.json')) then
      RenameFile(HostManifestBackup, ExpandConstant('{app}\browser-host.json'));
    ExecAsOriginalUser(ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe'),
      '-NoProfile -STA -ExecutionPolicy RemoteSigned -WindowStyle Hidden -File "' + ExpandConstant('{app}\packaging\toolgate-tray.ps1') + '"', '', SW_HIDE, ewNoWait, ExitCode);
  end;
end;
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var ExitCode: Integer;
begin
  if CurUninstallStep = usUninstall then begin
    // This runs after confirmation. Cancellation leaves the service and tray intact.
    if FileExists(ExpandConstant('{app}\browser-host.json')) and
      not RenameFile(ExpandConstant('{app}\browser-host.json'), ExpandConstant('{app}\browser-host.uninstalling.json')) then
      RaiseException('Could not pause the Chrome bridge. Close ToolGate browser connections and retry uninstall.');
    if not Exec(ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe'),
      '-NoProfile -ExecutionPolicy RemoteSigned -File "' + ExpandConstant('{app}\packaging\toolgate-upgrade.ps1') + '" -InstalledDirectory "' + ExpandConstant('{app}') + '"', '',
      SW_HIDE, ewWaitUntilTerminated, ExitCode) or (ExitCode <> 0) then
      RaiseException('Could not close the ToolGate tray and browser bridge. Retry uninstall.');
    // Uninstall is complete: enrollments and remembered gateways go too; the device key stays.
    if not Exec(ExpandConstant('{app}\olo-toolgate-client.exe'), 'uninstall --purge', '',
      SW_HIDE, ewWaitUntilTerminated, ExitCode) or (ExitCode <> 0) then
      RaiseException('Could not remove the ToolGate service or local data. Retry uninstall; check the Windows service logs if it fails again.');
  end;
  if CurUninstallStep = usPostUninstall then
    DeleteFile(ExpandConstant('{app}\browser-host.uninstalling.json'));
end;
