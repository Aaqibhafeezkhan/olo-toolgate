<!-- Copyright 2026 OLO Labs -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# ToolGate in 36 seconds

[Watch the video](toolgate-overview.mp4). This is a captioned, silent overview,
not a recording of a live installation. An [animated GIF preview](toolgate-overview.gif)
and [WebVTT captions](toolgate-overview.vtt) are also available.

## Transcript

| Time | On-screen explanation |
| --- | --- |
| 00:00–00:06 | **Control what AI can do.** Build, distribute, authorize and govern tools across users, agents and devices. |
| 00:06–00:12 | **One request. Clear boundaries.** An agent calls `hotfolder.write_text`. The Gateway checks access before a protected effect. |
| 00:12–00:18 | **Access starts with groups.** Groups hold access mappings. Default groups grant no access; new verified users start disabled. |
| 00:18–00:24 | **Allow. Ask. Block.** Allow permits an authorized action. Ask requires independent approval. Block prevents execution. |
| 00:24–00:30 | **Manage it. See what happened.** Manage groups and devices in the console. The Windows tray shows command progress and activity. |
| 00:30–00:36 | **Your first PR can be small.** Visit `github.com/olo-labs/olo-toolgate`. Choose a good first issue and help improve the docs. |

See the [initial configuration guide](../../config/initial/README.md) for group
semantics and [Windows status documentation](../client/installers.md#windows-status-activity-and-licensing)
for the device UI. Approvals do not substitute for required grants or device eligibility.

## Rebuild the media

From the repository root, with Python 3 and either Windows Segoe UI or Linux
DejaVu Sans fonts installed:

```bash
python -m pip install Pillow imageio-ffmpeg
python tools/docs/render_overview.py
```

The script renders six diagram scenes, the MP4, GIF, poster and WebVTT captions.
It uses the existing OLO logo, contains no live credentials or device data, and
does not contact a running ToolGate instance. `imageio-ffmpeg` supplies the encoder.
The media and source are distributed under this repository's Apache-2.0 license.

## Live console and OLO incident walkthrough

The README has four focused sets: audit/approvals (15 steps), device/client (14),
tools/access (15) and workspace administration (12). The generator rejects a set
outside 10–15 frames. [Follow each set step by step](TOURS.md); [tours.json](tours.json)
records the explicit order and source-image checksums for earlier device captures.

Current console/operator frames use the unmodified browser JPEG originals. The
device set also includes eight existing repository enrollment/client images, each
captioned **Earlier capture**. They come from a separate earlier session, not the
October 27 Ollama run. Native client images retain their existing backdrop. All
original files are kept unchanged. The full-page Analytics image is linked
individually rather than added as a long slide.

Rebuild the four sets with Pillow:

```bash
python tools/docs/build_console_tours.py
```

## Current capture refresh

**In progress:** 49 fresh capture entries from the October 27 GPU-backed walkthrough. The expired sign-in view is excluded. Eight earlier device/client captures supplement the device set and are labelled separately. The capture session ended before every view was collected; the remaining views are listed below. This is not a complete end-to-end model qualification.

**Model:** Qwen2.5 7B (`qwen2.5:7b`) through Ollama 0.40.11 on four NVIDIA Quadro P4000 GPUs, driver 582.99. RAG uses one short fictional runbook and one retrieved passage. Every new capture pauses for two seconds after the requested page/state loads.

A native diagnosis completed and was read from governed history after a reload; [Chat refresh defect #25](https://github.com/olo-labs/olo-mono/issues/25) tracks that UI issue. The model also produced unsupported diagnosis claims and initially invalid delegation JSON; [model qualification #26](https://github.com/olo-labs/olo-mono/issues/26) tracks these separately. The retry reached a real independent ToolGate approval. Its final recovery result was not captured. Approval and tool-call screenshots are actual records; model-generated prose requires review.

The separate scripted acceptance passed real authorization, worker-crash recovery, three Temporal child workflows and approval terminal checks. That evidence does not qualify native model behavior. Operator session `ui-tour-fixture-checks` contains caller-declared acceptance correlations, not verified Chat session identity. Scheduled Studio pages remain placeholders.

[Capture integrity and remaining coverage](capture-status.json) · [Capture manifest](screenshots.json)

## Fresh snapshot index

| Screen | View |
| --- | --- |
| [console-sign-in](screenshots/console-sign-in.jpg) | ToolGate tenant console sign-in |
| [console-overview](screenshots/console-overview.jpg) | Loaded tenant overview and scoped demo directories |
| [console-approvals-pending](screenshots/console-approvals-pending.jpg) | Live Ollama remediation waiting for independent approval |
| [console-approval-review](screenshots/console-approval-review.jpg) | Independent reviewer checks the exact delegated remediation invocation |
| [console-approval-approved](screenshots/console-approval-approved.jpg) | Independent approval recorded; original invocation resumes through ToolGate |
| [console-tools](screenshots/console-tools.jpg) | Tools · current incident walkthrough |
| [console-toolGroups](screenshots/console-toolGroups.jpg) | Tool groups · current incident walkthrough |
| [console-grants](screenshots/console-grants.jpg) | Access grants · current incident walkthrough |
| [console-bindings](screenshots/console-bindings.jpg) | Execution bindings · current incident walkthrough |
| [console-policies](screenshots/console-policies.jpg) | Policies · current incident walkthrough |
| [console-extractors](screenshots/console-extractors.jpg) | Resource extractors · current incident walkthrough |
| [console-users](screenshots/console-users.jpg) | Users · current incident walkthrough |
| [console-teams](screenshots/console-teams.jpg) | Teams · current incident walkthrough |
| [console-roles](screenshots/console-roles.jpg) | Typed roles · current incident walkthrough |
| [console-identityBindings](screenshots/console-identityBindings.jpg) | Human identities · current incident walkthrough |
| [console-delegations](screenshots/console-delegations.jpg) | Human delegations · current incident walkthrough |
| [console-agents](screenshots/console-agents.jpg) | Agents · current incident walkthrough |
| [console-agentGroups](screenshots/console-agentGroups.jpg) | Agent groups · current incident walkthrough |
| [console-workloadBindings](screenshots/console-workloadBindings.jpg) | Workload identities · current incident walkthrough |
| [console-agentDelegations](screenshots/console-agentDelegations.jpg) | Agent delegations · current incident walkthrough |
| [console-devices](screenshots/console-devices.jpg) | Devices · current incident walkthrough |
| [console-deviceGroups](screenshots/console-deviceGroups.jpg) | Device groups · current incident environment |
| [console-deviceEvidence](screenshots/console-deviceEvidence.jpg) | Device evidence · current incident walkthrough |
| [console-mapping](screenshots/console-mapping.jpg) | Agent Group tool mapping · current incident walkthrough |
| [console-config-server](screenshots/console-config-server.jpg) | Server · current incident walkthrough |
| [console-config-device](screenshots/console-config-device.jpg) | Device · current incident walkthrough |
| [console-config-backup](screenshots/console-config-backup.jpg) | Backup/Restore · current incident walkthrough |
| [console-mcp-servers](screenshots/console-mcp-servers.jpg) | MCP servers · current incident walkthrough |
| [console-about](screenshots/console-about.jpg) | About · current incident walkthrough |
| [console-config-reviews](screenshots/console-config-reviews.jpg) | Configuration reviews · current incident walkthrough |
| [console-outcomes](screenshots/console-outcomes.jpg) | Effect outcomes · current incident walkthrough |
| [console-simulation](screenshots/console-simulation.jpg) | Access simulation · current incident walkthrough |
| [console-server-packages](screenshots/console-server-packages.jpg) | Server packages · deployed official incident package |
| [console-builder](screenshots/console-builder.jpg) | Tool builder · current incident walkthrough |
| [console-local](screenshots/console-local.jpg) | Local vault · current incident environment |
| [console-requests](screenshots/console-requests.jpg) | Client tool requests · current incident walkthrough |
| [console-enroll](screenshots/console-enroll.jpg) | Enroll device · current incident walkthrough |
| [console-fleet](screenshots/console-fleet.jpg) | Packages · current incident walkthrough |
| [console-analytics](screenshots/console-analytics.jpg) | Tenant Analytics · actual memory, RAG and incident tool calls |
| [console-analytics-full](screenshots/console-analytics-full.jpg) | Full tenant Analytics page with per-tool call counts |
| [console-analytics-tool](screenshots/console-analytics-tool.jpg) | Remediation tool drilldown · actual calls and outcomes |
| [operator-sign-in](screenshots/operator-sign-in.jpg) | Labelled operator test gateway with separate mTLS certificate |
| [console-audit](screenshots/console-audit.jpg) | ToolGate audit trail with actor and request correlation |
| [operator-usage-charts](screenshots/operator-usage-charts.jpg) | Operator usage charts and busiest tools · scrolled dashboard |
| [console-approvals](screenshots/console-approvals.jpg) | Approval requests in the current incident environment |
| [console-analytics-tools](screenshots/console-analytics-tools.jpg) | Actual tool-call counts for memory, RAG and the incident agents |
| [console-overview-light](screenshots/console-overview-light.jpg) | Current tenant console in light theme |
| [operator-session-timeline](screenshots/operator-session-timeline.jpg) | Caller-declared fixture session · denial, cancellation and expiry checks |
| [operator-denied-detail](screenshots/operator-denied-detail.jpg) | Denied test operation · identity, spans and ledger transitions |

## Pending retakes

- `console-downloads-full.jpg` — Retake in the current GPU-backed walkthrough; previous capture excluded.
- `console-tool-picker.jpg` — Retake in the current GPU-backed walkthrough; previous capture excluded.
- `console-triage-tool-scope.jpg` — Retake in the current GPU-backed walkthrough; previous capture excluded.
- `console-test.jpg` — Session expired before capture; sign-in page excluded.
- `operator-overview.jpg` — Capture the dashboard heading and overview cards; the saved view contains charts and busiest tools.
- `operator-invocation-detail.jpg` — required new state not captured.

Also finish the remaining Temporal detail tabs and successful operator invocation/attempt views.
