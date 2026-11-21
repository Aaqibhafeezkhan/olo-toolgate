<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# OLO bot troubleshooting

| Symptom | Check |
|---|---|
| Publish disabled; dependencies not ready | Every persistent red blocker: packages, graphs, scoped secret, approved device/groups. |
| Package installation pending | Device active/enabled, groups, current image build and installation status. |
| Missing Ollama secret | Import referenced groups, explicitly run `ollama.py vault`; see [models](models.md). |
| Model forbidden | Workflow ceiling, agent group, current `model.infer` grant and HTTPS allowlist. Fallback cannot bypass these. |
| Model timeout/malformed decision | Gateway candidate logs, warmed models, GPU memory and closed JSON output. Premium is inactive. |
| File denied/absent | Folders, root/extensions, UID/ACL, native qualification, bindings/grants; see [device](device.md). |
| Log lines do not trigger | Correct `.log` path, regex, two matching lines within 60 seconds, cooldown and active mapping. Append after watcher readiness. |
| Unknown file write | Inspect its durable outcome; never automatically retry an unknown effect. |
| Old run uses old model after switch | Runs keep their admitted revision; new admissions use the new publication. |
| Installation settings unchanged after import | Apply operator settings explicitly; they stay outside `.olo`. |
| Missing cloud trace | Private collector/export logs and exact project. Traces do not establish effect success. |
| Blank after storage removal | New storage is a fresh installation. `start.bat` reconciles custody; normal restarts reuse it. |

Read [workflow architecture](../architecture/workflows.md) for current
boundaries and [unfinished work](../plans/workflows.md) for remaining acceptance. Report run ID, profile digest, package
version and sanitized errors; never attach credentials or private prompts.
