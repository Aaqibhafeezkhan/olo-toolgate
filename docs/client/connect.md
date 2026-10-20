<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Chrome Connect

The current Clients table combines registered devices and pending requests, with device names, registered users and detail tooltips. Enable/Disable and Approve/Deapprove are independent; approval may be timed or explicitly unlimited.

See [Device registry and tool-call controls](../control-plane/device-registry.md).

In the Admin console, open Devices ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ Enroll device and click **Connect**. Chrome on Windows is currently supported. Other browsers and operating systems use the existing client downloads.

1. Download **Install Chrome extension and client**. This is one Windows EXE containing the service, Chrome native bridge, extension files and tray icon. Open it and approve Windows permission. Setup shows an optional **Gateway URL** field, defaulting to `https://localhost:18450` for a new installation. Keep that value for a local gateway, or copy the current **Gateway URL** shown beside the downloads on Enroll Device. Setup obtains the local quickstart CA over loopback, verifies the HTTPS gateway, and saves app-specific trust under `C:\ProgramData\OLO\ToolGate\` without a Windows Root store import. No gateway credentials are required. Repair pre-fills the existing gateway; `/SERVER=` can pre-fill a managed installation.
2. Chrome requires extension approval. If a Web Store listing is configured, setup registers that listing with Chrome. Until the listing is published, open `chrome://extensions`, enable Developer mode and **Load unpacked** from `Program Files\OLO\ToolGateSetup\chrome-extension`. The waiting page detects the extension automatically without reloading.
3. Setup starts enrollment and opens the console. You can also choose **Enroll this device** from the tray menu without Chrome. For browser coordination, click **Connect** on Enroll Device. The page contacts the extension, which asks the native bridge to configure the installed client for this site's published gateway address. There are no popup setup questions. The client starts enrollment if needed; review and approve its code and fingerprint in the console. Installation alone does not approve enrollment.
4. One installed client belongs to one organization. Connecting from a different ToolGate site returns `TENANT_MISMATCH` and preserves the current identity. Changing organization requires administrator uninstall with explicit purge, followed by a fresh installation and enrollment. Purge removes the previous device key too. No gateway credentials are accepted by this setup protocol yet.
5. An enrolled client checks in at the configured interval while connected (2 s on Quickstart). Each poll receives permission replacements and queued remote tool calls; clients provisioned with builder trust and runtimes also poll for builder jobs on this cycle. Requests are serialized, and failures retain bounded backoff. The tray icon shows service availability, enrollment/connection state, readiness and check-in counts. Its menu opens the console or uninstaller.

The tray shows **Enrollment required** for an installed but unenrolled service, with the next step to start enrollment. Installation alone does not make protected tools ready. **Connected** means enrollment and a recent authenticated gateway check-in have completed; **Offline** means that check-in is no longer fresh. Pending approval and revoked enrollment have their own messages.

Running the EXE again offers **Repair / reinstall** or **Uninstall**. Repair preserves configuration and enrollment for the same gateway. Uninstall removes the system service, native bridge and tray startup; enrollment keys are retained by default.

The local console address (`http://localhost:18090` or `http://127.0.0.1:18090`) is resolved through its public configuration endpoint before installation changes the service. The client stores the published HTTPS gateway origin in its protected app-specific configuration. Remote gateways require an HTTPS origin. If the local console is stopped or its configuration is invalid, setup explains how to enter the current Gateway URL and allows a retry. This resolution does not require the Chrome extension.

**Cancel Connect** stops the page's coordination and prevents pending responses from starting enrollment. A Windows operation already in progress may continue. Retry restarts checks. Navigating away stops page polling. The system service keeps running after logout; Chrome is only needed for setup and gateway changes.

## Publishing and configuration

The endpoint-client workflow builds one Windows setup EXE containing the native service, bridge, tray and Manifest V3 extension. The extension ZIP is a release/Web Store packaging artifact, and Windows native ZIPs are internal build inputs. Public client downloads contain the setup EXE, checksums, license, notices, release notes and this guide. `extension.json` selects the exact supported protocol, Chrome version and package checksum. The public endpoint is `/api/public/v1/clients/extension`.

Chrome's numeric version is the repository version's major/minor/patch plus the full-history Git commit count (0..65535); `version_name` retains the full repository version. This gives the same commit the same extension version across client and Quickstart workflows, with increasing versions on main. The public key in `apps/browser-extension/identity.json` fixes the extension ID; Windows' native host permits only that ID. It is public material, not a signing credential. This is currently a development identity. Before enabling Web Store distribution, obtain the public key and item ID from the Chrome Developer Dashboard, update identity.json and the native-host, installer, console and Control allowlists together, then rebuild the client and extension. The identity tests check these agree. After adopting the store identity, set repository Actions variable `TOOLGATE_CHROME_STORE_URL` to `https://chromewebstore.google.com/detail/<extension-id>` **only after that listing is published**. The workflow publishes a ZIP to release assets; it does not publish to the Web Store. Store submission credentials and review are a separate deployment prerequisite.

The Windows installer installs and registers the native messaging host in HKLM. Health and enrollment use OS-authenticated IPC. Connect resolves the site's public gateway configuration and invokes only the protected packaged CLI for installation or a gateway update, through Windows administrator approval. It accepts no tool execution commands or secrets. A local health response confirms service presence, not authorization to this organization. The existing fingerprint confirmation and Control check-in provide that binding.

Before sending a Windows IPC request, the client verifies that the pipe's server
process is the running ToolGate service registered with the local Service Control
Manager, using LocalSystem and the fixed protected service executable. This works
for normal desktop accounts without requiring access to a SYSTEM process token.
The service separately authenticates each caller's actual SID and its allowlist.

## Debugging

- No extension detected: confirm Chrome on Windows, reload both the extension and console, then Retry Connect. Check the exact Chrome version shown in the console.
- Permission denied: confirm the Chrome extension has its installation permissions. Connect scopes state to the console origin; no administrator session token is sent to the extension.
- Missing native host: install the latest Windows installer; archive-only installations do not register Chrome. Inspect `HKLM\Software\Google\Chrome\NativeMessagingHosts\io.ololabs.toolgate.connect` and the registered manifest's allowed origin. Do not broaden it to other extensions.
- Service unavailable: inspect the ToolGate Windows service and existing client logs. The browser account must be in the client's OS peer allowlist. Installing using another administrator's credentials does not grant the browser account IPC access. Connect adds the invoking Chrome user SID through the protected configure command after administrator approval; retry Connect from that account. Do not make the pipe public.
- Tray icon missing: look in Windows' hidden system-tray icons. The setup launcher uses STA and a process-scoped RemoteSigned PowerShell policy for the administrator-owned local tray script; it does not change the machine's execution policy. Repair with the current EXE to update the startup entry. A Group Policy that prohibits this script still applies.
- Download or checksum failure: verify public installer assets are mounted and belong to the same release. A failure never opens an installer. Browser downloads retain normal security warnings.
- Waiting for check-in: verify the HTTPS Control address, certificate trust, enrollment confirmation and network reachability. A revoked device, stale heartbeat or Control outage never produces a Connected result.

The native host's IPC deadline is 20 seconds. Gateway configuration fetches are bounded to 15 seconds and Windows operations to ten minutes. Status requests have a 25-second deadline; the overall page flow is bounded to fifteen minutes. Extension progress is stored per console origin and survives service-worker restarts. No secrets are stored in browser storage.

See [Client tools through the Gateway](server-mcp.md) for permission caching, MCP discovery, queued execution and dashboard progress.

The organization **Clients** list shows connection status from authenticated server check-ins. Green means an enabled client with active enrollment has checked in within the last two minutes, matching the Gateway's availability window. Red indicates offline, disabled, revoked, not yet enrolled or unavailable status; the accompanying label and last check-in tooltip explain which applies. Status refreshes every two seconds while the list is open.
