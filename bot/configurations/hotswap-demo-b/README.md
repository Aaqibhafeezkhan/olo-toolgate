<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# OLO bot: hotswap-demo-b

This device-triggered flow explicitly reads the original file, asks the
OLO QLoRA file agent for a proposal, uses the other agent with lightweight
Ollama for the result, then writes the confirmed output. File agents try
`olo-qlora-hotfolder:e376fb40` first and `qwen3:0.6b` on inference failure;
other agents use `qwen3:0.6b`. OpenRouter remains unattached standby.
A model proposal alone cannot cause a file operation.

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
& $debugPython bot/build.py --configuration hotswap-demo-b --version 1.0.0-demo.local
```

The `.olo` and version/hash inventory are in `bot/bin/`. Import in **Bots**,
resolve every persistent red readiness bullet, review, provision packages,
prepare graphs and publish. The first Super Admin can perform these operations
and run the published bot. Runtime grants still govern every effect.

Workflow/bot ID: `hotswap-demo`. Trigger: `examples.hotfolder-watch/new-file`;
assignment `demo-b`; target group `hotswap-ReadAndWriteDeviceGroup`.
Add your real device to that group and every execution-binding group in
`referenced-config.json`. Then provision and qualify the reviewed native file
tools as explained in the device guide. Set `ollama-local-key` using
`& $debugPython debug/profiled/ollama.py vault`.

## Explicit folder setup

Import/switch never creates or changes device files. Run this separate operator
action to create only this case's directories and retain the debug device owner's access. Inputs must be owned by the device account with mode 0600. For host mounts, use the host permission commands in the device guide.

```powershell
if (-not $compose) { $compose = @('compose','-p','toolgate-profiled','-f','debug/profiled/state/compose.json') }
docker @compose exec -T device python3 -c "from pathlib import Path; import os; root=Path('/var/lib/olo-toolgate/files'); [(p.mkdir(parents=True,exist_ok=True),p.chmod(0o700)) for p in [root/x for x in ['inbox-b', 'processed', 'summaries']]]"
```

Allow `.txt` and `.json` in Administration > Device. Wait until the trigger
reports Running/Ready before creating a new input file.

## Trigger with this exact content

```powershell
@'
ISSUE-123 HIGH: The database connection pool was exhausted. Restarting the pool restored service.
'@ | docker @compose exec -T device python3 -c "import sys,os; from pathlib import Path; os.umask(0o077); p=Path('/var/lib/olo-toolgate/files/inbox-b/source.txt'); p.open('w',encoding='utf-8').write(sys.stdin.read())"
```

Input is `inbox-b/source.txt` under `/var/lib/olo-toolgate/files`.
The result is `summaries/summary-b.txt`. The original moves to `processed/source-b.txt` unchanged.
Use **Bots** run observations/results for progress and the final JSON.
Generated wording can vary; the configured output schema still applies.

```powershell
docker @compose exec -T device python3 -c "from pathlib import Path; print(Path('/var/lib/olo-toolgate/files/summaries/summary-b.txt').read_text(encoding='utf-8'))"
```

For manual Run, first stop this trigger assignment to avoid submitting the same
file through both entry points. Then use **Run**: enter `inbox-b/source.txt` after creating that file.
Output/archive paths are fixed demo settings. Before repeating, explicitly
choose new paths or inspect/remove prior artifacts yourself; imports do not
clean them. Do not resend an unknown write outcome.

A/B share `hotswap-demo` intentionally. Publishing the other bundle changes
new admissions to its trigger/configuration. Already admitted runs stay pinned.
No container restart, Admin change or automatic folder mutation occurs.
Explicitly stop the retired trigger assignment when no longer wanted.

Change folder/trigger settings in the package declaration and matching
`trigger-connections.json`, and graph paths in `bot.json`. Keep references
consistent, rebuild/import or edit the Admin JSON pages, then publish a new
revision. Use the centralized device/model guides for a different destination.
