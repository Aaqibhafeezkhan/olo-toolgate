<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Publication switch — revision B

A standard file-processing bot revision using the existing watched-folder
trigger, native tools and JSON graph. Use this after finishing a run with [A](../publication-switch-a/README.md).
Both configurations share profile/workflow ID `publication-switch`; they are
alternative publications, not independently active bot IDs. The startup menu
refuses selecting both together.

## Build and run

```powershell
python bot/build.py --configuration publication-switch-b --version 1.0.0-demo.local --output bot/bin/publication-switch-b
```

Import the `.olo`, or use its `quickstart/configure.bat` / `configure.sh` with
`--bundles-dir` pointing at that inventory. Complete device/native-tool setup
and readiness before publication; use [device setup](../../../docs/help/device.md)
and the [resource matrix](../../PERMISSIONS.md).

Prepare `publication-switch/inbox-b`, `publication-switch/processed`
and `publication-switch/summaries` under the device file root. Place
[sample-input.txt](sample-input.txt) at `publication-switch/inbox-b/source.txt`
after the watcher reports ready. Expected summary is
`publication-switch/summaries/summary-b.txt`; the unchanged source moves
to `publication-switch/processed/source-b.txt`.

Show the actual published revision, output and run identity, with unchanged
runtime/container image identities across the publication switch. Finish A
before switching to B. Imports do not clean files or create directory access.
Inspect outcomes and explicitly reset fixed fixtures before repeating.

This sequence establishes publication changes only after both real runs pass.
It does not establish watcher drain, in-flight continuation, failover or
exactly-once effects.

## Models and destination custody

Active order is QLoRA (`olo-qlora-hotfolder:e376fb40`) in Ollama, lightweight
Qwen (`qwen3:0.6b`) in Ollama, then premium OpenRouter. These are ordered
candidates, not three mandatory calls per request. Eligible inference failures
advance the chain; authorization/configuration denials and tool effects do not.

Both `ollama-local-key` and `openrouter-key` must exist in their declared vault
scopes. Permit the configured local HTTPS model origin and `https://openrouter.ai`
in the destination Gateway. For private model CA trust, include the required
public-provider roots too. Missing premium custody is a readiness blocker; it is
not silently detached. Follow the [model guide](../../../docs/help/models.md).
OpenRouter credentials, provider availability and live model behavior must be
verified at the destination. No credentials or model weights belong in `.olo`.
