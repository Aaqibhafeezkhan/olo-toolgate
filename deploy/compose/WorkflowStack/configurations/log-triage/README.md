<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Managed-log incident triage

This profile assigns `examples.log-watch/error-spike` to its managed Linux
device. Two newly observed `ERROR` or `FATAL` records within 60 seconds trigger
the configured `log-triage` workflow. Its `log-diagnoser` agent summarizes the
first matching sample; Control records the result. The 60-second cooldown
suppresses repeated alerts. This demo has **no remediation effects**.

## Install and start

Place reviewed `log-watch-0.1.0.olotool` in this configuration's `packages/`
subfolder. Build it using the [package instructions](../../../../../examples/java-tools/log-watch/README.md).
The profile pins package digest
`sha256:1a924aebfc10080d0a970233ec0eeb401c2c6e509119add9a40a3f0f9210dca9`;
if a reviewed rebuild differs, update `deployment.source.packageDigest` to
the digest printed by `toolgate verify`. Never remove the pin to bypass refusal.
An uninstalled artifact fails startup explicitly. Keep `model.gguf` in
`WorkflowStack/models/`, as with the other profiles, then run:

```bat
start.bat --configuration log-triage
```

The profile selects `olo-toolgate-device:managed-log-v1`, a local acceptance image
containing the updated managed-file policy (`txt`, `md`, `json`, `csv`, `log`).
Build the normal [device image](../../../../../apps/device/Dockerfile) from a
release Quickstart image with client-download artifacts and tag it accordingly,
or select a published device image containing this policy. The existing local
Quickstart source-check image omits those download artifacts. For local
verification, the existing device image can be extended with the current
`examples/langchain/device.py` at `/opt/toolgate/langchain-device.py`, which is
the same file copied by the normal device release build. Older device images
refuse `.log`; do not broaden the managed root or remove extension checking.

The console is exposed at `http://localhost:62091`. Credentials are generated
under `state/log-triage/credentials/admin/{username.txt,password.txt}`.
Model, engine, source, agent, monitoring and ports all belong to this profile.
Use [MONITORING.md](../../MONITORING.md) for Langfuse, LangSmith and the local
observability services; put secret values in this configuration's private
credential custody, never in the profile or source text.

## Produce the input

The watched file is `logs/app.log` **inside the device managed files area**.
This profile does not mount arbitrary host logs or subscribe to Windows Event
Logs. It also does not run the optional host file projection adapter. For a
synthetic demo, create the file from PowerShell:

```powershell
@'
import os
from pathlib import Path
os.umask(0o077)
path=Path('/var/lib/olo-toolgate/hotfolder/logs/app.log')
path.parent.mkdir(mode=0o700,exist_ok=True)
with path.open('a',encoding='utf-8') as stream:
    stream.write('INFO ready\nERROR INFRAFAIL-LOG: database pool exhausted\nERROR INFRAFAIL-LOG: checkout timed out\n')
'@ | docker exec -i toolgate-workflow-log-triage-linux-device-1 python -
```

Keep the file below 65536 bytes. The trigger reads complete newline-terminated
records every five seconds. Noise does not trigger an LLM call. Avoid real
secrets or personal data in this sample; event content is untrusted model input.

## Check the output and stop

Inspect the workflow run in Control's console/API and the JSON result under
`state/log-triage/workflow/results/run-*.json`. Agent execution metadata is in
`workflow-worker` container logs. There is no host summary folder or processed
file move: the source log is read-only and Control owns the workflow result.
Missing/oversized files report degraded source health without advancing state.
This is POLL observation, not native PUSH or a complete log history.

`stop.bat --configuration log-triage` stops this configuration and preserves storage.
`destroy.bat --configuration log-triage` removes its owned stack and generated
state/storage. Preserve any results needed before destroying it.
