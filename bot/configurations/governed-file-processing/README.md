<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Governed file processing

A standard device-triggered bot that reads an approved text input, obtains a
model proposal, writes a summary and archives the unchanged source using the
existing native file tools and JSON graph runtime.

## Build and configure

```powershell
python bot/build.py --configuration governed-file-processing --version 1.0.0-demo.local --output bot/bin/governed-file-processing
```

Import the `.olo`, or run `quickstart/configure.bat --bundles-dir` with that
output directory (`sh quickstart/configure.sh` on Linux/macOS). The standard
`bot/start.bat` menu lists this bot. Configure the model keys before activation,
resolve readiness, review/prepare/publish and wait for the watcher to be ready.

The operator must independently approve the device, allocate its declared
Device Groups, qualify native tools and create the configured folders with the
device account as owner. See [device setup](../../../docs/help/device.md) and
the [resource matrix](../../PERMISSIONS.md). Import is not runtime authority.

## Input and result

Place [sample-input.txt](sample-input.txt) as `governed-file-processing/inbox/source.txt`
under the configured device file root (`/var/lib/olo-toolgate/files` in the
managed stack). The existing watcher selects the run. Expected destinations:

- Summary: `governed-file-processing/summaries/result.json`.
- Unchanged archived source: `governed-file-processing/processed/source.txt`.

Confirm real outputs and attributable run/tool/device identities. Fixed paths
can collide on a second run: inspect outcomes and explicitly reset fixtures or
review changed paths before repeating. Never resend an unknown effect.

For manual Run, stop the watcher first, create the input and submit its relative
path. Do not submit the same file through both trigger and manual entry points.

## Verify the authority boundary

Use an ordinary enabled user with reviewed HUMAN workflow, agent, model and
resource coverage plus independent service/effect authority. First run an
authorized baseline. Inspect its terminal outcome and reset the fixed fixture
explicitly. Remove one required grant through existing administration, then make
a fresh manual request as the same ordinary user. Confirm refusal and that no
protected output or source move occurred for that request.

A current tenant Super Admin has different live authority and is unsuitable for
this missing-grant test. Prompt text never chooses permissions. This is a
specific denied-effect scenario, not comprehensive prompt-injection resistance.

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
