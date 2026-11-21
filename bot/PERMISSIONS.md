<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
<!-- GENERATED FILE: python tools/workflows/bot_permissions.py -->
# Bot users, permissions and required resources

This guide covers all checked-in bot configurations. IDs and dependencies below come
from their source JSON, not a running installation. It does not grant access or prove
runtime readiness. Follow each bot README for its exact input and file commands.

## Who needs which authority

| Identity | Required access |
|---|---|
| Installation operator | A live Super Admin for bot staging/review/preparation/publication and installation custody; administer owned devices, packages, model endpoints and scoped grants independently |
| Manual/API caller | Existing enabled OLO user; username/password login for the selected tenant; membership in an enabled Team with explicit HUMAN grants, directly or through a HUMAN runtime role. A BASIC portal role is sufficient for the external APIs |
| Caller reading their run | `workflow.progress.read` and/or `workflow.result.read` on that workflow, with current required resource access. V1 private run reads are owner-scoped; bot access does not reveal another user's result |
| Caller registering live events/callbacks | `workflow.subscribe` plus the selected progress/result read permissions, current at registration and each delivery |
| Trigger-status observer | `trigger.read` on `trigger-assignment:<actual-assignment-UID>`, scoped to its target Device Groups; add `trigger.subscribe` for status events/streams/callbacks. No workflow execution is granted by observation |
| Runtime agents | Enabled agent/group membership, ACTOR_SERVICE roles and current SERVICE/CAPABILITY/SECRET grants, workload credentials and execution bindings. Human login tokens never replace these |

Super Admin and device approval do not replace runtime grants. Imports preserve human
accounts and do not automatically put a person into a runtime role. Choose the actual
OLO users who should run each bot; the source `admin` identity reference is not a
requirement to share an administrator account.

## Human grant recipe for every bot

Use enabled HUMAN grants sourced from the user's enabled Team or its HUMAN role.
For the exact IDs in each section, grant these CUSTOM/EXACT resource/action pairs:

| Action | Resource locator |
|---|---|
| `workflow.execute` | `workflow:<workflow ID>` |
| `agent.execute` | `agent:<agent ID>` for every bound agent, including later handoff agents |
| `model.infer` | `model-group:<model group ID>` for the workflow ceiling and every bound agent model group |
| `workflow.progress.read`, `workflow.result.read`, `workflow.subscribe` | `workflow:<workflow ID>` as needed for observing that caller's runs |

Also cover the complete SERVICE/CAPABILITY scopes of all bound agents: tool groups,
device groups, actions and resource match rules. This includes `workflow-group:...`
and model resources present in their service grant. Cover any agent SECRET scopes
with corresponding human SECRET grants. The grant IDs listed below identify those
source scopes; they are not credentials or permissions to attach an ACTOR_SERVICE
role to a human Team.

For these file examples the tool action is **`invoke`**, scoped to the listed
`tool://...` resources and Tool/Device Groups. Do not substitute invented `read` or
`write` action strings. V1 human admission scopes need conditions that can be checked
at admission: no online/high-risk/network/posture/amount/quota evidence conditions.
Keep resource scopes narrow; service grants, bindings, device checks and policies still
enforce their own current execution conditions. Copying a service scope containing
`requireOnline: true` unchanged into a human admission grant is not sufficient.

Native/JSON graphs can select tools dynamically, so V1 checks the complete declared
agent capability ceiling, including capabilities not exercised by a particular input.
A single human scope must cover each required declared scope; do not assume separate
partial grants are automatically combined into a larger cross-product permission.

## All supplied bots

| Configuration | Profile / workflow ID | Entry agents | Device/tool work |
|---|---|---|---|
| [hotswap-demo-a](configurations/hotswap-demo-a/README.md) | `hotswap-demo` / `hotswap-demo` | `hotswap-langchain-linux-agent` | File tools and watcher |
| [hotswap-demo-b](configurations/hotswap-demo-b/README.md) | `hotswap-demo` / `hotswap-demo` | `hotswap-langchain-linux-agent` | File tools and watcher |
| [interactive-summary](configurations/interactive-summary/README.md) | `interactive-summary` / `interactive-summary` | `interactive-summarizer` | Text/models only |
| [issue-triage](configurations/issue-triage/README.md) | `issue-triage` / `issue-triage` | `issue-triage-langchain-linux-agent` | File tools and watcher |
| [log-triage](configurations/log-triage/README.md) | `log-triage` / `log-triage` | `log-triage-langchain-linux-agent` | File tools and watcher |
| [multiagent-incident](configurations/multiagent-incident/README.md) | `multiagent-incident` / `multiagent-incident` | `incident-triage-agent` | Text/models only |
| [native-hotfolder](configurations/native-hotfolder/README.md) | `native-hotfolder` / `native-hotfolder` | `native-hotfolder-langchain-linux-agent` | File tools and watcher |
| [native-log](configurations/native-log/README.md) | `native-log` / `native-log` | `native-log-langchain-linux-agent` | File tools and watcher |
| [ollama-summary](configurations/ollama-summary/README.md) | `ollama-summary` / `ollama-summary` | `ollama-summarizer` | Text/models only |
| [text-summary](configurations/text-summary/README.md) | `text-summary` / `text-summary` | `text-summary-langchain-linux-agent` | File tools and watcher |

## Installation requirements shared by the examples

Configure and approve the declared HTTPS model endpoints and installation-owned
vault references. Model group permission does not disclose the provider secret.
Local inference uses the model IDs listed in the source; standalone premium
standby groups are not a required grant until explicitly assigned. Review actual
graph/model routing in the bot README before downloading weights.

File examples require exact verified `.olotool` package versions, an approved and
enabled owned device in both the watcher-placement and tool-execution groups,
ready executors/workload bindings, and the qualified device file boundary.
Sources ship empty Device Groups; no physical device ID is implied. Paths below
are relative to the configured device file root, normally `/var/lib/olo-toolgate/files`.
Prepare broker-owned directories/files with the permissions in the device guide.

These example extractors grant tool invocation, not per-bot directory isolation.
A file tool grant covers its allowed arguments within the device's file boundary.
Use separately scoped devices/roots when bots must not share file access.

`hotswap-demo-a` and `hotswap-demo-b` intentionally share IDs and runtime groups.
Publishing B changes new admissions; it does not move A's files or replace human
permissions. Existing runs retain their pinned graph and admitted ceiling.

## hotswap-demo-a

[Bot instructions](configurations/hotswap-demo-a/README.md) · [Bot definition](configurations/hotswap-demo-a/bot.json) · [Directory grants/resources](configurations/hotswap-demo-a/referenced-config.json)

- Workflow execution/observation resource: `workflow:hotswap-demo`.
- Workflow service resources: `workflow-group:hotswap`.
- Human agent execution resources: `agent:hotswap-langchain-linux-agent`, `agent:hotswap-text-summarizer`.
- Bound Agent Groups: `hotswap-langchain-linux-agents`, `hotswap-summary-agents`.
- Model inference resources: `model-group:general-models`, `model-group:hotfolder-models`, `model-group:local-ceiling`.
- Service/capability source grants to cover: `hotswap-configured-workflow-service`, `hotswap-langchain-linux-capability`, `hotswap-langchain-linux-service`.
- Declared reachable model deployments: `local-lightweight → qwen3:0.6b`, `local-qlora → olo-qlora-hotfolder:e376fb40`.
- Installation model secret references: `ollama-local-key`.
- Packages: `examples.hotfolder-watch@0.1.0`, `examples.hotfolder@0.1.0`.
- Tool IDs: `examples.hotfolder/move`, `examples.hotfolder/read_text`, `examples.hotfolder/write_text`.
- Execution binding `hotswap-langchain-linux-binding`: Tool Group `langchain-package-tools`, Device Group `hotswap-langchain-linux-devices`, action(s) `invoke`.
- Watcher `demo-a`: Device Group(s) `hotswap-ReadAndWriteDeviceGroup`; configuration `{"folder":"inbox-a","extension":".txt"}`.
- Graph file paths: `input path resolved from {"$path":"/message"}`, `processed/source-a.txt`, `summaries/summary-a.txt`.

## hotswap-demo-b

[Bot instructions](configurations/hotswap-demo-b/README.md) · [Bot definition](configurations/hotswap-demo-b/bot.json) · [Directory grants/resources](configurations/hotswap-demo-b/referenced-config.json)

- Workflow execution/observation resource: `workflow:hotswap-demo`.
- Workflow service resources: `workflow-group:hotswap`.
- Human agent execution resources: `agent:hotswap-langchain-linux-agent`, `agent:hotswap-text-summarizer`.
- Bound Agent Groups: `hotswap-langchain-linux-agents`, `hotswap-summary-agents`.
- Model inference resources: `model-group:general-models`, `model-group:hotfolder-models`, `model-group:local-ceiling`.
- Service/capability source grants to cover: `hotswap-configured-workflow-service`, `hotswap-langchain-linux-capability`, `hotswap-langchain-linux-service`.
- Declared reachable model deployments: `local-lightweight → qwen3:0.6b`, `local-qlora → olo-qlora-hotfolder:e376fb40`.
- Installation model secret references: `ollama-local-key`.
- Packages: `examples.hotfolder-watch@0.1.0`, `examples.hotfolder@0.1.0`.
- Tool IDs: `examples.hotfolder/move`, `examples.hotfolder/read_text`, `examples.hotfolder/write_text`.
- Execution binding `hotswap-langchain-linux-binding`: Tool Group `langchain-package-tools`, Device Group `hotswap-langchain-linux-devices`, action(s) `invoke`.
- Watcher `demo-b`: Device Group(s) `hotswap-ReadAndWriteDeviceGroup`; configuration `{"folder":"inbox-b","extension":".txt"}`.
- Graph file paths: `input path resolved from {"$path":"/message"}`, `processed/source-b.txt`, `summaries/summary-b.txt`.

## interactive-summary

[Bot instructions](configurations/interactive-summary/README.md) · [Bot definition](configurations/interactive-summary/bot.json) · [Directory grants/resources](configurations/interactive-summary/referenced-config.json)

- Workflow execution/observation resource: `workflow:interactive-summary`.
- Workflow service resources: `workflow-group:interactive-summary`.
- Human agent execution resources: `agent:interactive-summarizer`.
- Bound Agent Groups: `interactive-summary-agents`.
- Model inference resources: `model-group:interactive-summary-general-models`, `model-group:interactive-summary-local-ceiling`.
- Service/capability source grants to cover: `interactive-summary-service`.
- Declared reachable model deployments: `interactive-summary-local-lightweight → qwen3:0.6b`, `interactive-summary-local-qlora → olo-qlora-hotfolder:e376fb40`.
- Installation model secret references: `ollama-local-key`.
- Tool packages, tool-execution devices and file paths: none in this configuration.
- Trigger assignments: none.

## issue-triage

[Bot instructions](configurations/issue-triage/README.md) · [Bot definition](configurations/issue-triage/bot.json) · [Directory grants/resources](configurations/issue-triage/referenced-config.json)

- Workflow execution/observation resource: `workflow:issue-triage`.
- Workflow service resources: `workflow-group:issue-triage`.
- Human agent execution resources: `agent:issue-triage-langchain-linux-agent`, `agent:issue-triage-text-summarizer`.
- Bound Agent Groups: `issue-triage-langchain-linux-agents`, `issue-triage-summary-agents`.
- Model inference resources: `model-group:issue-triage-general-models`, `model-group:issue-triage-hotfolder-models`, `model-group:issue-triage-local-ceiling`.
- Service/capability source grants to cover: `issue-triage-configured-workflow-service`, `issue-triage-langchain-linux-capability`, `issue-triage-langchain-linux-service`.
- Declared reachable model deployments: `issue-triage-local-lightweight → qwen3:0.6b`, `issue-triage-local-qlora → olo-qlora-hotfolder:e376fb40`.
- Installation model secret references: `ollama-local-key`.
- Packages: `examples.hotfolder-watch@0.1.0`, `examples.hotfolder@0.1.0`.
- Tool IDs: `examples.hotfolder/move`, `examples.hotfolder/read_text`, `examples.hotfolder/write_text`.
- Execution binding `issue-triage-langchain-linux-binding`: Tool Group `langchain-package-tools`, Device Group `issue-triage-langchain-linux-devices`, action(s) `invoke`.
- Watcher `issue-triage-watch`: Device Group(s) `issue-triage-ReadAndWriteDeviceGroup`; configuration `{"folder":"issue-triage/inbox","extension":".txt"}`.
- Graph file paths: `input path resolved from {"$path":"/message"}`, `issue-triage/processed/source.txt`, `issue-triage/summaries/result.json`.

## log-triage

[Bot instructions](configurations/log-triage/README.md) · [Bot definition](configurations/log-triage/bot.json) · [Directory grants/resources](configurations/log-triage/referenced-config.json)

- Workflow execution/observation resource: `workflow:log-triage`.
- Workflow service resources: `workflow-group:log-triage`.
- Human agent execution resources: `agent:log-triage-langchain-linux-agent`, `agent:log-triage-text-summarizer`.
- Bound Agent Groups: `log-triage-langchain-linux-agents`, `log-triage-summary-agents`.
- Model inference resources: `model-group:log-triage-general-models`, `model-group:log-triage-hotfolder-models`, `model-group:log-triage-local-ceiling`.
- Service/capability source grants to cover: `log-triage-configured-workflow-service`, `log-triage-langchain-linux-capability`, `log-triage-langchain-linux-service`.
- Declared reachable model deployments: `log-triage-local-lightweight → qwen3:0.6b`, `log-triage-local-qlora → olo-qlora-hotfolder:e376fb40`.
- Installation model secret references: `ollama-local-key`.
- Packages: `examples.hotfolder@0.1.0`, `examples.log-watch@0.1.0`.
- Tool IDs: `examples.hotfolder/move`, `examples.hotfolder/read_text`, `examples.hotfolder/write_text`.
- Execution binding `log-triage-langchain-linux-binding`: Tool Group `langchain-package-tools`, Device Group `log-triage-langchain-linux-devices`, action(s) `invoke`.
- Watcher `log-triage-watch`: Device Group(s) `log-triage-ReadAndWriteDeviceGroup`; configuration `{"file":"log-triage/logs/app.log","pattern":"ERROR|FATAL","threshold":2,"windowSeconds":60,"cooldownSeconds":60}`.
- Graph file paths: `log-triage/logs/app.log`, `log-triage/summaries/result.json`.

## multiagent-incident

[Bot instructions](configurations/multiagent-incident/README.md) · [Bot definition](configurations/multiagent-incident/bot.json) · [Directory grants/resources](configurations/multiagent-incident/referenced-config.json)

- Workflow execution/observation resource: `workflow:multiagent-incident`.
- Workflow service resources: `workflow-group:multiagent-incident`.
- Human agent execution resources: `agent:incident-summary-agent`, `agent:incident-triage-agent`.
- Bound Agent Groups: `multiagent-incident-agents`.
- Model inference resources: `model-group:multiagent-incident-general-models`, `model-group:multiagent-incident-local-ceiling`.
- Service/capability source grants to cover: `multiagent-incident-service`.
- Declared reachable model deployments: `multiagent-incident-local-lightweight → qwen3:0.6b`, `multiagent-incident-local-qlora → olo-qlora-hotfolder:e376fb40`.
- Installation model secret references: `ollama-local-key`.
- Tool packages, tool-execution devices and file paths: none in this configuration.
- Trigger assignments: none.

## native-hotfolder

[Bot instructions](configurations/native-hotfolder/README.md) · [Bot definition](configurations/native-hotfolder/bot.json) · [Directory grants/resources](configurations/native-hotfolder/referenced-config.json)

- Workflow execution/observation resource: `workflow:native-hotfolder`.
- Workflow service resources: `workflow-group:native-hotfolder`.
- Human agent execution resources: `agent:native-hotfolder-langchain-linux-agent`, `agent:native-hotfolder-text-summarizer`.
- Bound Agent Groups: `native-hotfolder-langchain-linux-agents`, `native-hotfolder-summary-agents`.
- Model inference resources: `model-group:native-hotfolder-general-models`, `model-group:native-hotfolder-hotfolder-models`, `model-group:native-hotfolder-local-ceiling`.
- Service/capability source grants to cover: `native-hotfolder-configured-workflow-service`, `native-hotfolder-langchain-linux-capability`, `native-hotfolder-langchain-linux-service`.
- Declared reachable model deployments: `native-hotfolder-local-lightweight → qwen3:0.6b`, `native-hotfolder-local-qlora → olo-qlora-hotfolder:e376fb40`.
- Installation model secret references: `ollama-local-key`.
- Packages: `examples.hotfolder-watch@0.1.0`, `examples.hotfolder@0.1.0`.
- Tool IDs: `examples.hotfolder/move`, `examples.hotfolder/read_text`, `examples.hotfolder/write_text`.
- Execution binding `native-hotfolder-langchain-linux-binding`: Tool Group `langchain-package-tools`, Device Group `native-hotfolder-langchain-linux-devices`, action(s) `invoke`.
- Watcher `native-hotfolder-watch`: Device Group(s) `native-hotfolder-ReadAndWriteDeviceGroup`; configuration `{"folder":"native-hotfolder/inbox","extension":".txt"}`.
- Graph file paths: `input path resolved from {"$path":"/message"}`, `native-hotfolder/processed/source.txt`, `native-hotfolder/summaries/result.json`.

## native-log

[Bot instructions](configurations/native-log/README.md) · [Bot definition](configurations/native-log/bot.json) · [Directory grants/resources](configurations/native-log/referenced-config.json)

- Workflow execution/observation resource: `workflow:native-log`.
- Workflow service resources: `workflow-group:native-log`.
- Human agent execution resources: `agent:native-log-langchain-linux-agent`, `agent:native-log-text-summarizer`.
- Bound Agent Groups: `native-log-langchain-linux-agents`, `native-log-summary-agents`.
- Model inference resources: `model-group:native-log-general-models`, `model-group:native-log-hotfolder-models`, `model-group:native-log-local-ceiling`.
- Service/capability source grants to cover: `native-log-configured-workflow-service`, `native-log-langchain-linux-capability`, `native-log-langchain-linux-service`.
- Declared reachable model deployments: `native-log-local-lightweight → qwen3:0.6b`, `native-log-local-qlora → olo-qlora-hotfolder:e376fb40`.
- Installation model secret references: `ollama-local-key`.
- Packages: `examples.hotfolder@0.1.0`, `examples.log-watch@0.1.0`.
- Tool IDs: `examples.hotfolder/move`, `examples.hotfolder/read_text`, `examples.hotfolder/write_text`.
- Execution binding `native-log-langchain-linux-binding`: Tool Group `langchain-package-tools`, Device Group `native-log-langchain-linux-devices`, action(s) `invoke`.
- Watcher `native-log-watch`: Device Group(s) `native-log-ReadAndWriteDeviceGroup`; configuration `{"file":"native-log/logs/app.log","pattern":"ERROR|FATAL","threshold":2,"windowSeconds":60,"cooldownSeconds":60}`.
- Graph file paths: `native-log/logs/app.log`, `native-log/summaries/result.json`.

## ollama-summary

[Bot instructions](configurations/ollama-summary/README.md) · [Bot definition](configurations/ollama-summary/bot.json) · [Directory grants/resources](configurations/ollama-summary/referenced-config.json)

- Workflow execution/observation resource: `workflow:ollama-summary`.
- Workflow service resources: `workflow-group:ollama-summary`.
- Human agent execution resources: `agent:ollama-summarizer`.
- Bound Agent Groups: `ollama-summary-agents`.
- Model inference resources: `model-group:ollama-summary-general-models`, `model-group:ollama-summary-local-ceiling`.
- Service/capability source grants to cover: `ollama-summary-service`.
- Declared reachable model deployments: `ollama-summary-local-lightweight → qwen3:0.6b`, `ollama-summary-local-qlora → olo-qlora-hotfolder:e376fb40`.
- Installation model secret references: `ollama-local-key`.
- Tool packages, tool-execution devices and file paths: none in this configuration.
- Trigger assignments: none.

## text-summary

[Bot instructions](configurations/text-summary/README.md) · [Bot definition](configurations/text-summary/bot.json) · [Directory grants/resources](configurations/text-summary/referenced-config.json)

- Workflow execution/observation resource: `workflow:text-summary`.
- Workflow service resources: `workflow-group:text-summary`.
- Human agent execution resources: `agent:text-summary-langchain-linux-agent`, `agent:text-summary-text-summarizer`.
- Bound Agent Groups: `text-summary-langchain-linux-agents`, `text-summary-summary-agents`.
- Model inference resources: `model-group:text-summary-general-models`, `model-group:text-summary-hotfolder-models`, `model-group:text-summary-local-ceiling`.
- Service/capability source grants to cover: `text-summary-configured-workflow-service`, `text-summary-langchain-linux-capability`, `text-summary-langchain-linux-service`.
- Declared reachable model deployments: `text-summary-local-lightweight → qwen3:0.6b`, `text-summary-local-qlora → olo-qlora-hotfolder:e376fb40`.
- Installation model secret references: `ollama-local-key`.
- Packages: `examples.hotfolder-watch@0.1.0`, `examples.hotfolder@0.1.0`.
- Tool IDs: `examples.hotfolder/move`, `examples.hotfolder/read_text`, `examples.hotfolder/write_text`.
- Execution binding `text-summary-langchain-linux-binding`: Tool Group `langchain-package-tools`, Device Group `text-summary-langchain-linux-devices`, action(s) `invoke`.
- Watcher `text-summary-watch`: Device Group(s) `text-summary-ReadAndWriteDeviceGroup`; configuration `{"folder":"text-summary/inbox","extension":".txt"}`.
- Graph file paths: `input path resolved from {"$path":"/message"}`, `text-summary/processed/source.txt`, `text-summary/summaries/result.json`.

## Configure and verify

Use [run/import guidance](../docs/help/run-bots.md), [model setup](../docs/help/models.md),
[device/file requirements](../docs/help/device.md) and the
[external API/Postman guide](../deploy/docker/quickstart/dev/externa-workflow-integration/postman/README.md).
Obtain actual published profile digests/generations, trigger UIDs and device IDs
from the destination. Review permissions again after changing a bot or role.

Regenerate this matrix with `python tools/workflows/bot_permissions.py`;
verify source agreement with `python tools/workflows/bot_permissions.py --check`.
