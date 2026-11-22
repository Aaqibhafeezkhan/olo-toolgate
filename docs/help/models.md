<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Configure and update OLO models

Model routing is JSON in each bot's `bot.json`, under `workflows`:
`modelDeployments` identifies HTTPS origins, model IDs, vault references and
generation limits; `modelGroups[].deploymentIds` is an ordered candidate list;
`agentDefinitions[].modelGroupId` selects an agent's list. A workflow's
`modelGroupId` is its allowed deployment ceiling. Selection follows the agent's
order, intersected with that ceiling. OLO currently uses the OpenAI-compatible
`/v1/chat/completions` protocol and closed JSON decisions.

The supplied lightweight deployments set `generation.reasoningEffort` to `none`
for short structured results. A `/no_think` prompt alone is insufficient for
reliable latency with Ollama's OpenAI endpoint. This optional setting is sent as
`reasoning_effort`; use `low`, `medium` or `high` only if your provider supports
it and your run/request budgets permit it, or omit it for provider defaults.
Gateway bounds inference within its configured request deadline and never
retries a completed file operation when inference fails.

## Supplied routing

| Agent | First model | Inference failure fallback |
|---|---|---|
| Hot-folder/file tool agent | `olo-qlora-hotfolder:e376fb40` | `qwen3:0.6b` |
| Summarization, triage and other agents | `qwen3:0.6b` | None until explicitly attached |
| Premium declaration | `premium-openrouter`, group `premium-standby` | Inactive |

Independently owned bots prefix model group/deployment IDs with their case name;
use the exact IDs in that bot's JSON. A/B share their IDs intentionally.
The logical routing suffixes below are common to every bot.

File agents use `hotfolder-models`; other agents use `general-models`; workflows
allow `local-ceiling`. File graphs read, request a file-agent model proposal,
summarize with the other agent, then write/archive. The proposal is data: a tool
proposal returned by a `model` node is not executed. Explicit tool nodes remain
separately authorized.

## Local Ollama and the real adapter

For the [published-image launcher](../../deploy/docker/quickstart/dev/README.md),
use its `start.bat` choices and `manage.ps1 ollama-vault`; its private model and
Admin custody is separate from `debug/profiled`. Application images must first
be published. The commands below describe the existing source-build launcher.

From the repository root on Windows, with Docker Desktop's Linux engine running:

```powershell
.\debug\profiled\start.bat -WithOllama -WithoutMonitoring
. .\debug\profiled\python.ps1
$debugPython = Find-DebugPython
```

The launcher bootstraps private Python when needed. Use the `$debugPython` path
in the [launcher guide](../../debug/profiled/README.md). Select startup choice
**1** to install/reuse Ollama, **2** to retain another provider. The launcher probes
Docker NVIDIA access before exposing all GPUs with `gpus: all`; otherwise it uses
CPU. `OLLAMA_SCHED_SPREAD=1` allows two models to stay loaded. CPU inference can
be slower; no host model installation is required.

Startup pulls `qwen3:0.6b` and prepares the real
[OLO QLoRA adapter](https://huggingface.co/olo-labs/olo-qwen2.5-0.5b-hotfolder)
at `e376fb40c313a8827c4d5ce6ca2abe2b6fe7a4c6`, based on
`Qwen/Qwen2.5-0.5B-Instruct` at `7ae557604adf67be50417f59c2c2f167def9a775`.
The repository contains a PEFT adapter, not a runnable GGUF. An isolated CPU
Docker builder merges the pinned Safetensors base/adapter and converts to F16
GGUF using pinned llama.cpp source. No host Torch/Transformers install, training
or upload occurs. Ollama imports the source-qualified tag above. First
preparation needs Internet, several GB of disk and up to 8 GB builder RAM;
restarts reuse weights. The model retains its upstream licensing and its source
manifest stays with local weights. A base-model alias is not the fine-tuned model.

The private HTTPS bridge is `https://ollama-provider:9443`; no Ollama host port
is opened. After importing referenced groups, explicitly copy this installation's
bridge credential into OLO's vault:

```powershell
& $debugPython debug/profiled/ollama.py vault
```

It sets `ollama-local-key` with `default-tools` / `default-devices` scope, reuses
the saved Admin login and does not change its password or import a bot. Rerun
for a fresh destination; an exported bundle contains no secret. Startup owns
TLS/bridge custody in the private custody volume.

## Change a model

Pull/create a replacement in Ollama, then edit its deployment's `modelId`
in **Bots > Models**, preserving the exact provider tag. Change an agent's
model group in **Agents > Models & prompts**, or edit source JSON and rebuild.
Stage, review, prepare and publish a new revision. Existing runs keep their
pinned revision; new runs use the new publication. Bot changes require no
device/runtime restart. A new origin, CA or process environment is an explicit
installation change and may require applying container settings.

For a different QLoRA artifact, update pinned base/adapter coordinates in
`tools/models/prepare_qlora.py`, choose a new cache directory and tag in
`debug/profiled/ollama.py`, rebuild the preparation image, then update bot model
IDs. Incompatible/incomplete source-qualified caches are not silently reused.
Weights are installation artifacts and are never embedded in `.olo`.

## Attach OpenRouter later

Every supplied bot declares `premium-openrouter` at `https://openrouter.ai`,
model ID `openai/gpt-4.1-mini`, with vault reference `openrouter-key`.
`premium-standby` has `standby: true` and is unattached: no key is required, no premium request is sent
and local failure cannot silently cause paid usage.

When explicitly needed, choose your provider's current model ID, create a key at
[OpenRouter keys](https://openrouter.ai/settings/keys), and store it in the
console vault with the deployment's tool/device scope. Add `premium-openrouter`
after the local candidate in the intended agent's group **and** to
`local-ceiling`. Ensure the service group has `model.infer` for the chosen agent
group. Add `https://openrouter.ai` to `TOOLGATE_WORKFLOW_MODEL_ORIGINS` in
`debug/profiled/installation.env`. If custom-only CA trust is configured, arrange
public-provider trust with the operator; do not disable TLS verification.
Apply installation settings, then stage/review/publish. Attaching an
unconfigured provider becomes a visible readiness blocker.

Fallback tries each eligible candidate once within a 25-second inference
budget, shared among remaining candidates. HTTP, transport and malformed-decision
failures may advance it. Permission revocation, disallowed origins and invalid
installation settings stop the request. A valid but poor decision does not
trigger quality fallback. Tool denial, approval, reads, writes and completed
effects are never retried by this mechanism. Gateway logs identify the attempted
deployment/model/index without keys or prompts. Runtime traces currently record
structural model spans, not token/cost or selected-candidate attributes.

OLO sends the pinned agent/workflow finish-result schema to model decoding and
validates the returned decision against it before downstream graph file effects.
Local JSON Schema references are rebased within the decision envelope; remote
references and resource `$id` declarations are refused before provider IO.
