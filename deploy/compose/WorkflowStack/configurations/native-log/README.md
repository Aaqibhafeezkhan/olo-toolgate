<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Native graph: managed-log summary

This configuration uses the independent `examples.native-summary` Python graph
installed read-only under `graphs/`. The JSON graph config selects `log-diagnoser`.
The log-watch trigger dispatches its `/sample` into that graph, which invokes the
configured agent through ToolGate and returns its model-produced summary.
The hot-folder delivery adapter is not enabled for this configuration.

Put `model.gguf` in `../../models/`. Install the exact trigger artifact specified
in workflow-profile.json under `packages/` (see its README). Start with `start.bat`
and select `native-log`, or `python manage.py start --configuration native-log`.
Its Quickstart/runtime image tags match `native-hotfolder`; switching workflows
does not rebuild the worker image. Use fresh storage for the new contracts.

The watched file is the device's managed `logs/app.log`, not an arbitrary host
log. Two newline-terminated ERROR/FATAL records within the configured window
trigger the workflow. From the repository root run
`python tools/workflows/verify_log_demo.py --configuration native-log` to create
the owned fixture, prove INFO noise does not trigger a run, append two matching
records and independently verify Control's result. It refuses to overwrite an
existing log fixture. Inspect `../../state/native-log/workflow/results/` and
`../../state/native-log/verification/` for output/evidence.

Console: http://localhost:62491/console/. Private admin credentials are under
`../../state/native-log/credentials/admin/`. Workflow and agent JSON text boxes
edit the same draft; stage/review/publish to activate changes. Graph `config`
controls the selected native factory. Model/prompt/tool authority remains in the
published workflow/agent configuration and current ToolGate grants.

Stop this stack before switching to `native-hotfolder` if running one model at
a time. `stop.bat --configuration native-log` preserves storage; use
`destroy.bat --configuration native-log` to remove this stack and its storage.
Native checkpoints,
interrupt resume, arbitrary tenant code upload and automatic effect retry are not implemented by this example; see the current runtime guide.
