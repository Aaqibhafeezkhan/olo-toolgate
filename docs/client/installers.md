# Unsigned client installers

The current Clients table combines registered devices and pending requests, with device names, registered users and detail tooltips. Enable/Disable and Approve/Deapprove are independent; approval may be timed or explicitly unlimited. Download or installation is not enrollment approval; the current protected client retains identity during temporary suspension and recovers a certificate only for the same still-approved key.

See [Device registry and tool-call controls](../control-plane/device-registry.md).

Downloads are available on the Control home page without logging in. Choose your
OS and CPU architecture. Installer and archive SHA-256 values are shown beside
their links. These development installers are unsigned; signing and notarization
are separate release steps. An installer cannot remove SmartScreen or Gatekeeper
warnings. Do not disable machine-wide security protections.

Windows: download the `.setup.exe` from your ToolGate page and approve UAC.
Setup has an optional **Gateway URL** field. For a new installation it defaults to
the gateway carried by the console download link, falling back to `https://localhost:18450`; leave it unchanged for a gateway running locally, or paste
the current **Gateway URL** shown alongside the downloads on Enroll Device. No
credentials are required. Setup installs the Windows LocalSystem service with
automatic startup and registers an entry in Installed Apps for uninstallation.
Setup configures local quickstart CA trust automatically in `C:\ProgramData\OLO\ToolGate\`, verifies HTTPS, and starts enrollment in the console. Approve the device code and fingerprint there. The tray also has **Enroll this device**, so Chrome is optional for enrollment. No debug helper or manual CA export is needed for client installation.
It also installs the Chrome extension files, protected native messaging bridge and a tray icon. The tray starts at login and shows connection and enrollment status. Use Enroll device Ã¢â€ â€™
Connect in the Admin console and follow the [Chrome Connect guide](connect.md).

Installer downloads use stable names, such as
`olo-toolgate-client-x86_64-pc-windows-msvc.setup.exe`, across releases. The public
download API resolves that name to the current validated installer and sends
`Cache-Control: no-store`. Versioned prerelease files also use `no-store`, because
development builds can reuse the same filenames. Versioned final-release files
use `public, max-age=31536000, immutable`. Manifest endpoints use `no-store`;
every artifact is verified against its manifest checksum. GitHub releases also
include these stable installer names.
In Connect, **Install Chrome extension and client** downloads the same Windows setup EXE, which
contains the client, native bridge and unpacked Chrome extension. There is no
separate ZIP download. Open chrome://extensions, enable Developer mode and choose
Load unpacked from `Program Files\OLO\ToolGateSetup\chrome-extension`.
Chrome still requires the user's extension approval. Windows client ZIP archives
are internal CI build inputs and are not public installation downloads.

The configured Windows download endpoint is `/api/public/v1/clients/setup/<target>`.
It uses the server-owned `toolgate.control.endpoint.control-url` setting and carries
that public HTTPS origin as a hex suffix in the suggested EXE filename for interactive and unattended
installation. The hint is limited to 90 ASCII characters to
fit Windows filename limits. The EXE bytes and checksums remain identical to the
versioned release. Plain release EXEs can use `/SERVER=<HTTPS origin>` for managed
silent installation. Interactive setup uses the optional field; the configured download prefills it, then repair falls back to
the existing gateway. A blank field uses `https://localhost:18450`. Both setup modes use
`/SERVER=`, then the download hint, then the existing gateway, then the localhost
default. A loopback HTTP console address is resolved to its published HTTPS gateway
before changing the service. Client communication remains HTTPS. The local console
must be running for this resolution; setup offers a retry if it is unavailable.

macOS: open the `.dmg`, then `Install ToolGate.app`. Enter your HTTPS Control server
and approve administrator authentication. A system LaunchDaemon runs independently
of user login. The app includes the client, licenses, dependency notices and guides.
Gatekeeper may block unsigned builds; managed administrators must approve their use
under their organization's policy. Installer signing/notarization is deferred.

Linux: the self-contained `.run` uses the native systemd service installer. On a
desktop with Zenity and PolicyKit it asks for the server and administrator approval.
Without a graphical frontend it prompts in a terminal and uses sudo. Browser
downloads do not preserve executable permission: use file properties to mark it
executable, or run `chmod +x ./olo-toolgate-client-*.run`, then open/run it. This
permission step depends on the desktop; universal unattended browser-to-install
behavior is not supported. Linux requires systemd; this is not a .deb/.rpm package.

Windows setup detects an existing client and offers repair/reinstall or uninstall.
Repair retains configuration and enrollment for the same organization. Connect from another
ToolGate site returns `TENANT_MISMATCH`. Linux/macOS installs remain initial-install only.
Uninstall retains tenant state by default; explicit `--purge` removes enrollment, local
data and the device key before another organization can be installed. Unsupported
pre-release journals and orphan keys are rejected without conversion.
If a fresh debug data volume replaces the local gateway CA, use explicit uninstall
and purge before installing that recreated organization. Setup never silently clears
the tenant UUID anchor or accepts the replacement CA. Removing a container while retaining its data volume preserves
the gateway CA and enrollment. The Clients list refreshes automatically after enrollment.
Use the installed client `uninstall` command as administrator on Linux/macOS;
Windows Installed Apps invokes this command. Installation does not authorize tools
or enroll a device. Complete enrollment using the [endpoint guide](../../apps/endpoint-client/README.md).
The service continues after locking the screen or logging out, and protected calls
block if Gateway authorization is unavailable.

Diagnostics: inspect Windows Services/Event Viewer, `journalctl -u
olo-toolgate-client` on Linux, or `sudo launchctl print
system/io.ololabs.toolgate.client` on macOS. Run the installed client `health`.
See [production/debugging](../operations/debugging.md) for TLS and enrollment errors.

Build with the existing tested native archive in `build/client/release`:
`python tools/client/installer.py --target <native-target>`. Windows requires Inno
Setup 6.7.1 (`CLIENT_ISCC_PATH` can select its compiler); macOS uses osacompile and
hdiutil; Linux uses Python standard libraries. Native CI builds all six target
installers after service tests. The public bundle includes `installers.json` and
per-installer checksums, and development releases reuse those exact artifacts.

## Windows status, activity and licensing

The tray **Show status** opens a live OLO-branded window. While a tool call, runtime
preparation or builder test executes, it shows its tool/command name and elapsed
time. A measured percentage is shown when supplied; commands without measured
progress use an animated bar. When idle, it shows the last completed, failed or
interrupted command. Health/catalog polling does not replace that command.

The **Activity log** tab shows the latest 100 device events. The service persists
these bounded records and last-command state in `activity.json` inside its
protected state directory (`C:\ProgramData\OLO\ToolGate` on Windows). Events cover
command start/completion/interruption, service lifecycle, connection transitions
and check-in failures. Arguments, results, credentials and secrets are excluded.
The tray reads activity through authenticated local IPC even while execution is
busy; it does not read the protected file or acquire credentials. A service
restart marks unfinished commands interrupted, never successful. This local
rolling diagnostic history supplements the server authorization audit.

**About OLO ToolGate** displays the version returned by the installed client,
branding and license information. The interactive Windows installer displays the
bundled Apache-2.0 license and requires acceptance before installation. Native
installer silent mode retains the existing administrator automation behavior.

## Windows status, activity and licensing

The tray **Show status** opens a live OLO-branded window. While a tool call, runtime
preparation or builder test executes, it shows its tool/command name and elapsed
time. A measured percentage is shown when supplied; commands without measured
progress use an animated bar. When idle, it shows the last completed, failed or
interrupted command. Health/catalog polling does not replace that command.

The **Activity log** tab shows the latest 100 device events. The service persists
these bounded records and last-command state in `activity.json` inside its
protected state directory (`C:\ProgramData\OLO\ToolGate` on Windows). Events cover
command start/completion/interruption, service lifecycle, connection transitions
and check-in failures. Arguments, results, credentials and secrets are excluded.
The tray reads activity through authenticated local IPC even while execution is
busy; it does not read the protected file or acquire credentials. A service
restart marks unfinished commands interrupted, never successful. This local
rolling diagnostic history supplements the server authorization audit.

**About OLO ToolGate** displays the version returned by the installed client,
branding and license information. The interactive Windows installer displays the
bundled Apache-2.0 license and requires acceptance before installation. Native
installer silent mode retains the existing administrator automation behavior.
