# OLO ToolGate

> **Control what AI can do.**

OLO ToolGate is a self-hosted control plane for building, distributing and governing
AI tools across people, agents and devices. It combines group-based access,
ALLOW / ASK / BLOCK policies, reviewed configuration, device enrollment and
auditable execution.

[Get started](docs/getting-started/one-minute-quickstart.md) ·
[Documentation](docs/README.md) · [Build a tool](TOOL-SDK-GUIDE.md) ·
[Contribute](CONTRIBUTING.md) · [Enterprise evaluation](docs/enterprise-access-control/README.md) ·
[Multitenant deployment](docs/deployment/multitenant.md)

[Watch the captioned overview](https://github.com/olo-labs/olo-toolgate/raw/refs/heads/main/docs/media/toolgate-overview.mp4)
or read the [transcript and screenshot guide](docs/media/README.md).

## Run ToolGate

Use the [Quickstart walkthrough](docs/getting-started/one-minute-quickstart.md)
for a local, single-node evaluation. The [Compose helpers](deploy/compose/QuickStart/README.md)
manage deployment and retain the data volume. For a source build, follow the
[development guide](docs/development/README.md).

Fresh installations create management identities and protected default groups.
Tools and agents require explicit enablement, grants and execution bindings.
Device approval alone never grants permission to run a tool.

## What is available

| Area | Start here |
|---|---|
| Console, users, teams, groups and reviewed configuration | [Administration](docs/control-plane/README.md) |
| Current authorization, approvals and single-use permits | [Security model](docs/security/README.md) |
| Device enrollment, package tools on devices and the files area | [Endpoint client](docs/client/README.md) |
| Java tool authoring, packaging and standalone MCP | [Java SDK](tool-sdk/java-sdk/README.md) |
| Server Java tools, placement and direct or queued dispatch | [Tool Host](apps/tool-host/README.md) |
| Deployment, recovery and diagnosis | [Operations](docs/operations/README.md) |

This repository is a development release. Tool Host sandbox isolation and several
SDK, integration and distribution features remain unfinished. The Marketplace
applications are scaffolds. Each [component guide](docs/README.md) describes its
current behavior and limitations.

## Four focused demo flows

Each set contains 10–15 screens, with one screen per step. Individual full-resolution
images remain available in the [snapshot index](docs/media/README.md) and the
[step-by-step tour guide](docs/media/TOURS.md).

### 1. Audit, approvals and tool calls · 15 steps

<p align="center">
  <img src="docs/media/audit-flow.gif" width="100%" alt="15-step audit flow: requests, independent approval, outcomes, Analytics tool calls and Operator Insights">
</p>

Follow a request through independent review, its recorded outcome, audit evidence,
tenant Analytics and Operator Insights. The OLO example uses **Qwen2.5 7B through
Ollama on four Quadro P4000 GPUs**, with one short RAG runbook; separate scripted
acceptance traffic is labelled.

### 2. Devices and device client · 14 steps

<p align="center">
  <img src="docs/media/device-flow.gif" width="100%" alt="14-step device flow: native client, enrollment, fingerprint review, approval, connection, activity log and device administration">
</p>

Open the client, enroll a device, compare its fingerprint, approve it and inspect
connection/activity before browsing device groups, evidence and packages. Eight
existing enrollment/client images are labelled **Earlier capture** in the tour;
they are not from the October 27 OLO session. Device enrollment requires no model.

### 3. Tools and delegated access · 15 steps

<p align="center">
  <img src="docs/media/console-tools-tour.gif" width="100%" alt="15-step tools and access flow: authoring, packages, groups, agents, delegation, grants, policies, execution bindings and access simulation">
</p>

Build and distribute tools, map agent capabilities, constrain delegated access and
inspect the final execution boundary. These are the ToolGate controls around the
Qwen2.5 7B OLO example; the console itself requires no model.

### 4. Workspace administration · 12 steps

<p align="center">
  <img src="docs/media/console-administration.gif" width="100%" alt="12-step workspace administration flow: sign-in, overview, themes, people, identities, MCP servers, settings, backup, vault and version">
</p>

Sign in, inspect the workspace and configure people, identities and server settings.
These administration screens require no model.

Current console and operator screens were captured from a fresh local `0.10.0-dev`
installation on October 27, 2026, including tenant Analytics and tool drilldowns.
Earlier device/client frames are identified individually in the tour guide.
[Browse every captured page and the verification notes](docs/media/README.md).
Enabled tools and grants are fictional demo configuration, not installation defaults.
The operator tour uses a labelled local test gateway holding an ephemeral operator
client certificate; the operator listener retains its separate mTLS protection.

## Evaluate for your organization

Control evaluates current authority before protected effects. Gateway routes
requests, and registered executors enforce the permit and resource boundary.
Unavailable authority fails closed; cached metadata does not permit offline execution.

Start with the [enterprise evaluation guide](docs/enterprise-access-control/README.md)
for supported scope, implementation evidence, deployment requirements and known
limitations. [Standards alignment](docs/enterprise-access-control/standards-alignment.md)
describes design relationships; it is not a certification claim.

## Contribute

Read [CONTRIBUTING.md](CONTRIBUTING.md), choose a component from the
[architecture map](docs/architecture/component-map.md), and use the
[development commands](docs/development/README.md). Small fixes, tests,
documentation and examples are welcome. Discuss proposed work in GitHub issues.

[Support](SUPPORT.md) · [Report a vulnerability](SECURITY.md) ·
[Governance](GOVERNANCE.md) · [Code of conduct](CODE_OF_CONDUCT.md) ·
[Changelog](CHANGELOG.md) · [Apache-2.0 license](LICENSE)
