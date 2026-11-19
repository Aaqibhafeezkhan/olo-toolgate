<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# OLO bot: ollama-summary

A text-only bot uses `qwen3:0.6b` through the configured agent. It needs
no device/tool/trigger. QLoRA is declared but not selected for this general agent;
OpenRouter is unattached standby with no automatic paid fallback.

Start with the centralized [device setup/update guide](../../../docs/help/device.md)
and [model setup/update guide](../../../docs/help/models.md). Also read
[import/publish/run](../../../docs/help/run-bots.md),
[observability](../../../docs/help/observability.md) and
[authoring](../../../docs/help/author-bots.md). Installation settings, credentials,
model weights and folder permissions are outside this bot.

Build from the repository root:

```powershell
. .\debug\profiled\python.ps1
$debugPython = Find-DebugPython
& $debugPython bot/build.py --configuration ollama-summary --version 1.0.0-demo.local
```

The `.olo` and version/hash inventory are in `bot/bin/`. Import in **Bots**,
resolve every persistent red readiness bullet, review, provision packages,
prepare graphs and publish. The first Super Admin can perform these operations
and run the published bot. Runtime grants still govern every effect.

Start optional Ollama with `debug\profiled\start.bat -WithOllama` and, after
referenced groups exist, run `& $debugPython debug/profiled/ollama.py vault`.
Click this published bot's run symbol and enter:

```text
The database connection pool was exhausted. Restarting the pool restored service.
```

The dialog shows progress and a JSON object with a generated `summary`. No
folder/output file is used. Wording is model-generated, not an exact fixture.
Edit the agent prompt/group and deployments in `bot.json` or the corresponding
Admin editors, then publish a new revision. Export includes referenced data but
never the model secret or weights; use the two central guides on a new system.

Model-group/deployment identifiers in this bot are prefixed with `ollama-summary-`
to keep its independently published configuration separate from other bots.
The provider model IDs and ordered routing are unchanged.
