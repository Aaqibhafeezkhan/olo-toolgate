<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Local Ollama incident summary

A text-only bot uses a configured summarization agent and local `qwen3:0.6b`
thinking model. It needs no device files, tools or triggers. The model is served
by optional `ollama/ollama:latest` companions in the [debug runbook](../../../debug/profiled/README.md).
Ollama supports Qwen3 thinking and OpenAI-compatible structured output. Its
reasoning is not displayed or retained as execution progress.

`bot.json` defines the provider, model, generation limits, graph and agent prompt.
`referenced-config.json` defines scoped agent service grants. There are no
embedded packages, credentials, container settings or model weights in the `.olo`.
The destination supplies the Ollama server, TLS trust and `ollama-local-key`
vault secret. The TLS front end validates that key; Ollama's HTTP API is private
to the Docker network. PREPARE mode refuses tool mutations.

Build from the repository root:

```powershell
& $debugPython bot/build.py --configuration ollama-summary --version 1.0.0-demo.ollama
```

Import `bot/bin/ollama-summary-ollama-summary-1.0.0-demo.ollama.olo` on Bots,
configure the destination secret/provider, then publish. The runbook provides
an explicit operator activation command which performs those Admin API steps.
Click **Run** and enter:

```text
The database connection pool was exhausted. Restarting the pool restored service.
```

Check recorded progress and the semantic `summary` in the same Run dialog. The
wording is model-generated, not a fixed expected string. No folders or file
permissions are needed. Use short inputs; this small demo model is not a
production quality or high-volume recommendation. Model downloads persist in
the installation's dedicated Ollama volume. No model port is exposed to the host.
