<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Tenant incident triage

Two agents classify a supplied incident and produce a factual handover using the existing JSON graph
runtime. No device tool, file mutation, trigger or service repair is performed.
The bot does not select its tenant from incident text; authenticated Control
context and destination-local authority determine ownership.

## Build and deploy in Quickstart

From the repository root:

```powershell
python bot/build.py --configuration tenant-incident-triage --version 1.0.0-demo.tenant --output bot/bin/tenant-incident-triage
```

The `.olo`, inventory and checksums are in `bot/bin/tenant-incident-triage/`. Import through **Bots >
Import bot .olo**, configure the destination's local model vault key, complete
readiness, review, prepare and publish. Or use
`quickstart/configure.bat` / `sh quickstart/configure.sh` with the existing
[managed setup](../../README.md#quick-demo-setup); `--bundles-dir` selects the
local inventory. That helper operates the normal single-tenant managed stack;
use independent tenant administration when importing into shared Control.

The normal `bot/start.bat` menu includes **Tenant incident triage** (currently
option 11). Until a published release contains this new configuration, use your
local build inventory instead of requesting an older release's bot assets:

```powershell
.\bot\start.bat -Bots 11 -BundlesDir D:\git\olo-toolgate\bot\bin\tenant-incident-triage
```

With no `-Bots` argument the regular use-case menu prompts for a selection. The
same `-BundlesDir` option also works with this bot's standalone configurator.

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

## Run

Paste [sample-incident.txt](sample-incident.txt) into **Run**. Select entry agent
`tenant-incident-triage-analyzer`. The result contains `triage.severity`,
`triage.assessment` and `summary`; both agent stages must finish. Verify facts and
schema rather than identical generated wording. Do not describe the reported
historical restart as an action this bot performed.

## Export your changes and import in both tenants

1. In Quickstart, edit the handover prompt to include the marker
   `INCIDENT-REVISION-01` in its summary. Save/review/prepare/publish and run
   the fixture. Record the published revision and actual result.
2. Export this bot using the existing `.olo` export action. Inspect the archive's
   workflow to confirm the changed prompt is present; retain its SHA-256 digest.
3. In the [multitenant installation](../../../deploy/docker/multitenant/README.md),
   import the same exported bytes separately as Tenant A and Tenant B. Resolve
   model custody, current user/service authority and graph readiness per tenant.
4. Run the same fixture in both. Verify the saved prompt and marker are preserved,
   with distinct tenant-owned run state. Change Tenant A's prompt/publication and
   confirm Tenant B's configuration remains unchanged.
5. Attempt a foreign run/result read with the other tenant's identity. Confirm
   denial and no private result disclosure using the actual authenticated API.

Success means the customized exported bot runs in both destinations. Importing
the original source bundle alone does not establish that user edits survived.
An archive digest verifies bytes, not runtime authority. Verify this path on the
selected images; these instructions do not attest that it has passed on them.

See the [permission matrix](../../PERMISSIONS.md),
[authoring guide](../../../docs/help/author-bots.md) and
[workflow boundaries](../../../docs/architecture/workflows.md).
