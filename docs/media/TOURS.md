# ToolGate demo flows

Four focused sets with 10–15 screens each. All links open the original saved images.
Earlier device/client captures are explicitly marked; current console/operator captures are from October 27, 2026.

## Audit, approvals and tool-call evidence · 15 steps

[Animated tour](audit-flow.gif)

| Step | Screen | Capture |
| --- | --- | --- |
| 1 | [Client tool requests · current incident walkthrough](screenshots/console-requests.jpg) | October 27 console/operator |
| 2 | [Approval requests in the current incident environment](screenshots/console-approvals.jpg) | October 27 console/operator |
| 3 | [Live Ollama remediation waiting for independent approval](screenshots/console-approvals-pending.jpg) | October 27 console/operator |
| 4 | [Independent reviewer checks the exact delegated remediation invocation](screenshots/console-approval-review.jpg) | October 27 console/operator |
| 5 | [Independent approval recorded; original invocation resumes through ToolGate](screenshots/console-approval-approved.jpg) | October 27 console/operator |
| 6 | [Effect outcomes · current incident walkthrough](screenshots/console-outcomes.jpg) | October 27 console/operator |
| 7 | [ToolGate audit trail with actor and request correlation](screenshots/console-audit.jpg) | October 27 console/operator |
| 8 | [Tenant Analytics · actual memory, RAG and incident tool calls](screenshots/console-analytics.jpg) | October 27 console/operator |
| 9 | [Actual tool-call counts for memory, RAG and the incident agents](screenshots/console-analytics-tools.jpg) | October 27 console/operator |
| 10 | [Remediation tool drilldown · actual calls and outcomes](screenshots/console-analytics-tool.jpg) | October 27 console/operator |
| 11 | [Labelled operator test gateway with separate mTLS certificate](screenshots/operator-sign-in.jpg) | October 27 console/operator |
| 12 | [Operator usage charts and busiest tools · scrolled dashboard](screenshots/operator-usage-charts.jpg) | October 27 console/operator |
| 13 | [Caller-declared fixture session · denial, cancellation and expiry checks](screenshots/operator-session-timeline.jpg) | October 27 console/operator |
| 14 | [Denied test operation · identity, spans and ledger transitions](screenshots/operator-denied-detail.jpg) | October 27 console/operator |
| 15 | [Configuration reviews · current incident walkthrough](screenshots/console-config-reviews.jpg) | October 27 console/operator |

## Device enrollment and client operation · 14 steps

[Animated tour](device-flow.gif)

| Step | Screen | Capture |
| --- | --- | --- |
| 1 | [Open the device client from its tray menu](device-client-tray-menu.jpg) | Earlier capture |
| 2 | [Inspect device client status](device-client-status.jpg) | Earlier capture |
| 3 | [Enroll device · current incident walkthrough](screenshots/console-enroll.jpg) | October 27 console/operator |
| 4 | [Connect a device for enrollment](device-flow-1-enroll.jpg) | Earlier capture |
| 5 | [Device waits for administrator approval](device-flow-2-waiting-for-approval.jpg) | Earlier capture |
| 6 | [Compare the device fingerprint before approval](device-flow-3-compare-fingerprint.jpg) | Earlier capture |
| 7 | [Approved device connects](device-flow-4-approved-and-connected.jpg) | Earlier capture |
| 8 | [Inspect the connected device details](device-flow-5-connected-device-details.jpg) | Earlier capture |
| 9 | [Review the device client activity log](device-client-activity-log.jpg) | Earlier capture |
| 10 | [Devices · current incident walkthrough](screenshots/console-devices.jpg) | October 27 console/operator |
| 11 | [Device groups · current incident environment](screenshots/console-deviceGroups.jpg) | October 27 console/operator |
| 12 | [Device evidence · current incident walkthrough](screenshots/console-deviceEvidence.jpg) | October 27 console/operator |
| 13 | [Packages · current incident walkthrough](screenshots/console-fleet.jpg) | October 27 console/operator |
| 14 | [Device · current incident walkthrough](screenshots/console-config-device.jpg) | October 27 console/operator |

## Tools and delegated access · 15 steps

[Animated tour](console-tools-tour.gif)

| Step | Screen | Capture |
| --- | --- | --- |
| 1 | [Tools · current incident walkthrough](screenshots/console-tools.jpg) | October 27 console/operator |
| 2 | [Tool builder · current incident walkthrough](screenshots/console-builder.jpg) | October 27 console/operator |
| 3 | [Server packages · deployed official incident package](screenshots/console-server-packages.jpg) | October 27 console/operator |
| 4 | [Tool groups · current incident walkthrough](screenshots/console-toolGroups.jpg) | October 27 console/operator |
| 5 | [Resource extractors · current incident walkthrough](screenshots/console-extractors.jpg) | October 27 console/operator |
| 6 | [Agents · current incident walkthrough](screenshots/console-agents.jpg) | October 27 console/operator |
| 7 | [Agent groups · current incident walkthrough](screenshots/console-agentGroups.jpg) | October 27 console/operator |
| 8 | [Workload identities · current incident walkthrough](screenshots/console-workloadBindings.jpg) | October 27 console/operator |
| 9 | [Human delegations · current incident walkthrough](screenshots/console-delegations.jpg) | October 27 console/operator |
| 10 | [Agent delegations · current incident walkthrough](screenshots/console-agentDelegations.jpg) | October 27 console/operator |
| 11 | [Agent Group tool mapping · current incident walkthrough](screenshots/console-mapping.jpg) | October 27 console/operator |
| 12 | [Access grants · current incident walkthrough](screenshots/console-grants.jpg) | October 27 console/operator |
| 13 | [Policies · current incident walkthrough](screenshots/console-policies.jpg) | October 27 console/operator |
| 14 | [Execution bindings · current incident walkthrough](screenshots/console-bindings.jpg) | October 27 console/operator |
| 15 | [Access simulation · current incident walkthrough](screenshots/console-simulation.jpg) | October 27 console/operator |

## Workspace and administration · 12 steps

[Animated tour](console-administration.gif)

| Step | Screen | Capture |
| --- | --- | --- |
| 1 | [ToolGate tenant console sign-in](screenshots/console-sign-in.jpg) | October 27 console/operator |
| 2 | [Loaded tenant overview and scoped demo directories](screenshots/console-overview.jpg) | October 27 console/operator |
| 3 | [Current tenant console in light theme](screenshots/console-overview-light.jpg) | October 27 console/operator |
| 4 | [Users · current incident walkthrough](screenshots/console-users.jpg) | October 27 console/operator |
| 5 | [Teams · current incident walkthrough](screenshots/console-teams.jpg) | October 27 console/operator |
| 6 | [Typed roles · current incident walkthrough](screenshots/console-roles.jpg) | October 27 console/operator |
| 7 | [Human identities · current incident walkthrough](screenshots/console-identityBindings.jpg) | October 27 console/operator |
| 8 | [MCP servers · current incident walkthrough](screenshots/console-mcp-servers.jpg) | October 27 console/operator |
| 9 | [Server · current incident walkthrough](screenshots/console-config-server.jpg) | October 27 console/operator |
| 10 | [Backup/Restore · current incident walkthrough](screenshots/console-config-backup.jpg) | October 27 console/operator |
| 11 | [Local vault · current incident environment](screenshots/console-local.jpg) | October 27 console/operator |
| 12 | [About · current incident walkthrough](screenshots/console-about.jpg) | October 27 console/operator |

## Individual full-page views

- [Full tenant Analytics page with per-tool call counts](screenshots/console-analytics-full.jpg)
