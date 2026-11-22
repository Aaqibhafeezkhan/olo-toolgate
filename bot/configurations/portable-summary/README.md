<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Portable summary

A standard text-only bot with one summarizer and the existing JSON graph runtime.
It requires no device files, tool packages or trigger. Its prompt and workflow
can be customized through existing authoring, saved/published and exported as
an immutable `.olo` for another independently configured installation.

## Build and run

```powershell
python bot/build.py --configuration portable-summary --version 1.0.0-demo.local --output bot/bin/portable-summary
```

Import the `.olo`, or use `quickstart/configure.bat` / `configure.sh` with
`--bundles-dir` pointing at that output. The standard startup menu lists this
bot. Configure model custody, resolve readiness, review/prepare/publish and use
**Run** with [sample-input.txt](sample-input.txt). The expected result contains
a factual, nonempty `summary`; generated wording may vary.

## Preserve a customized revision across environments

1. Customize the summarizer prompt to include `PORTABLE-REVISION-01` in its
   summary, preserving the finish-decision/result schema. Save/review/prepare/
   publish, run the sample and verify that assertion.
2. Export the intended bot using the existing `.olo` export action. Inspect the
   exported workflow to confirm the saved customization and retain its checksum.
3. Import those exact bytes into the destination tenant. Independently bind its
   model keys, authority and graph readiness, then review/prepare/publish.
4. Inspect the destination prompt and run the same functional assertion.
5. For two tenants, import separately, run concurrently, change A independently,
   and verify B is unchanged and foreign private result access is denied.

The destination imports bot logic, not accounts, secrets, model weights or
infrastructure. An unchanged starter-bundle import is not proof that user edits
survived export. A checksum is not execution evidence. Use the existing
[authoring guide](../../../docs/help/author-bots.md),
[multitenant deployment](../../../deploy/docker/multitenant/README.md) and
[resource matrix](../../PERMISSIONS.md).

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
